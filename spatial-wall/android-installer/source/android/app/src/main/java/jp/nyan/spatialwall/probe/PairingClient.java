// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

// One explicit user attempt. No retries, persistence, legacy tokens, or UI ownership.
final class PairingClient implements AutoCloseable {
    interface Crypto extends AutoCloseable {
        byte[] message();
        void processPeer(byte[] peer);
        void verifyConfirmation(byte[] mac);
        byte[] confirmation();
        byte[] enrollmentProof();
        @Override void close();
    }
    interface CryptoFactory { Crypto create(byte[] context, byte[] code); }
    interface Transport extends AutoCloseable {
        // Null pin/leaf is restricted to public /pair-v2 traffic.
        Reply request(String method, String path, byte[] pin, byte[] leaf,
                      byte[] authorization, byte[] body) throws IOException;
        @Override void close();
    }
    static final class Reply implements AutoCloseable {
        final int status;
        final byte[] body;
        Reply(int status, byte[] body) { this.status = status; this.body = body; }
        @Override public void close() { wipe(body); }
    }
    static final class Result implements AutoCloseable {
        private final byte[] id, secret, spki, pin, leaf;
        final int grants;
        private boolean closed;
        private Result(byte[] id, byte[] secret, byte[] spki, byte[] pin, byte[] leaf, int grants) {
            this.id = id; this.secret = secret; this.spki = spki;
            this.pin = pin.clone(); this.leaf = leaf.clone(); this.grants = grants;
        }
        byte[] deviceId() { return id.clone(); }
        byte[] spkiDer() { return spki.clone(); }
        byte[] spkiSha256() { return pin.clone(); }
        byte[] leafSha256() { return leaf.clone(); }
        // The caller owns and must erase this copy after protected persistence/use.
        synchronized byte[] credential() {
            if (closed) throw new IllegalStateException("Pairing result is closed");
            return secret.clone();
        }
        @Override public synchronized void close() { closed = true; wipe(secret); }
    }

    private static final byte[] EMPTY = new byte[0];
    private final Transport transport;
    private final CryptoFactory crypto;
    private boolean used, closed;

    PairingClient(InetSocketAddress endpoint) {
        this(new PairingHttp(endpoint));
    }
    PairingClient(Transport transport) {
        this(transport, PairingClient::nativeSession);
    }
    PairingClient(Transport transport, CryptoFactory crypto) {
        if (transport == null || crypto == null) throw new IllegalArgumentException("Missing pairing stage");
        this.transport = transport; this.crypto = crypto;
    }

    Result pairCode(byte[] code, String label) throws IOException {
        begin();
        byte[] codeCopy = null, labelBytes = null, proof = null, enrollment = null;
        try {
            codeCopy = copy(code, 6);
            for (byte digit : codeCopy) if (digit < '0' || digit > '9')
                throw new IllegalArgumentException("Pairing code must contain six digits");
            labelBytes = label(label);
            byte[] context;
            try (Reply reply = request("GET", "/pair-v2", null, null, null, EMPTY)) {
                require(reply, 200, 98);
                context = reply.body.clone();
            }
            if (context[0] != 2 || !validGrants(context[97] & 255))
                throw new IOException("Unsupported pairing context");
            byte[] nonce = Arrays.copyOfRange(context, 1, 33);
            byte[] pin = Arrays.copyOfRange(context, 33, 65);
            byte[] leaf = Arrays.copyOfRange(context, 65, 97);
            try (Crypto session = crypto.create(context, codeCopy)) {
                wipe(codeCopy);
                byte[] exchange = new byte[65];
                exchange[0] = 1;
                System.arraycopy(nonce, 0, exchange, 1, 32);
                byte[] message = session.message();
                if (message == null || message.length != 32) throw new IOException("Invalid pairing message");
                System.arraycopy(message, 0, exchange, 33, 32);
                int attempt;
                try (Reply reply = request("POST", "/pair-v2", null, null, null, exchange)) {
                    require(reply, 200, 65);
                    attempt = reply.body[0] & 255;
                    if (attempt < 1 || attempt > 5) throw new IOException("Invalid pairing attempt");
                    session.processPeer(Arrays.copyOfRange(reply.body, 1, 33));
                    session.verifyConfirmation(Arrays.copyOfRange(reply.body, 33, 65));
                }
                // NativePairing permits this only after verifying the PC's MAC.
                byte[] confirm = new byte[66], mac = session.confirmation();
                if (mac == null || mac.length != 32) throw new IOException("Invalid pairing confirmation");
                confirm[0] = 2;
                System.arraycopy(nonce, 0, confirm, 1, 32);
                confirm[33] = (byte) attempt;
                System.arraycopy(mac, 0, confirm, 34, 32);
                try (Reply reply = request("POST", "/pair-v2", null, null, null, confirm)) {
                    require(reply, 204, 0);
                }
                proof = session.enrollmentProof();
                if (proof == null || proof.length != 32) throw new IOException("Invalid enrollment proof");
                enrollment = new byte[65 + labelBytes.length];
                System.arraycopy(nonce, 0, enrollment, 0, 32);
                enrollment[32] = (byte) attempt;
                System.arraycopy(proof, 0, enrollment, 33, 32);
                System.arraycopy(labelBytes, 0, enrollment, 65, labelBytes.length);
                return enroll("Pairing ", enrollment, pin, leaf, context[97] & 255);
            }
        } catch (IllegalStateException failure) {
            throw new IOException("Pairing confirmation failed", failure);
        } finally {
            wipe(codeCopy); wipe(labelBytes); wipe(proof); wipe(enrollment);
            close();
        }
    }

    // Only an independently trusted PC-screen QR may supply these values.
    Result enrollInvite(byte[] spkiPin, byte[] leafHash, int grants, byte[] invite, String label)
            throws IOException {
        begin();
        byte[] inviteCopy = null, labelBytes = null, enrollment = null;
        try {
            byte[] pin = copy(spkiPin, 32), leaf = copy(leafHash, 32);
            if (!validGrants(grants)) throw new IllegalArgumentException("Invalid pairing grants");
            inviteCopy = copy(invite, 32);
            labelBytes = label(label);
            enrollment = new byte[32 + labelBytes.length];
            System.arraycopy(inviteCopy, 0, enrollment, 0, 32);
            System.arraycopy(labelBytes, 0, enrollment, 32, labelBytes.length);
            return enroll("Invite ", enrollment, pin, leaf, grants);
        } finally {
            wipe(inviteCopy); wipe(labelBytes); wipe(enrollment);
            close();
        }
    }

    private Result enroll(String scheme, byte[] payload, byte[] pin, byte[] leaf, int grants)
            throws IOException {
        byte[] encoded = Base64.getUrlEncoder().withoutPadding().encode(payload);
        byte[] prefix = scheme.getBytes(StandardCharsets.US_ASCII);
        byte[] authorization = new byte[prefix.length + encoded.length];
        try {
            System.arraycopy(prefix, 0, authorization, 0, prefix.length);
            System.arraycopy(encoded, 0, authorization, prefix.length, encoded.length);
            try (Reply reply = request("POST", "/enroll-v2", pin, leaf, authorization, EMPTY)) {
                if (reply.status != 200) throw new IOException("Enrollment was rejected");
                Result result = parseResult(reply.body, pin, leaf, grants);
                try { checkOpen(); return result; }
                catch (IOException failure) { result.close(); throw failure; }
            }
        } finally { wipe(encoded); wipe(authorization); }
    }

    static Result parseResult(byte[] body, byte[] pin, byte[] leaf, int grants) throws IOException {
        if (pin == null || pin.length != 32 || leaf == null || leaf.length != 32 || !validGrants(grants)
                || body == null || body.length < 53 || body[0] != 2 || (body[49] & 255) != grants)
            throw new IOException("Invalid enrollment response");
        int size = ((body[50] & 255) << 8) | (body[51] & 255);
        if (size < 1 || size > 256 || body.length != 52 + size)
            throw new IOException("Invalid enrollment public key length");
        byte[] spki = Arrays.copyOfRange(body, 52, body.length);
        try {
            if (!MessageDigest.isEqual(pin, MessageDigest.getInstance("SHA-256").digest(spki))
                    || !Arrays.equals(spki, KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(spki)).getEncoded()))
                throw new IOException("Enrollment PC identity changed");
        } catch (GeneralSecurityException failure) {
            throw new IOException("Invalid enrollment PC identity", failure);
        }
        return new Result(Arrays.copyOfRange(body, 1, 17), Arrays.copyOfRange(body, 17, 49),
                spki, pin, leaf, grants);
    }

    private Reply request(String method, String path, byte[] pin, byte[] leaf,
                          byte[] authorization, byte[] body) throws IOException {
        checkOpen();
        Reply reply = transport.request(method, path, pin, leaf, authorization, body);
        try { checkOpen(); return reply; }
        catch (IOException failure) { reply.close(); throw failure; }
    }
    private synchronized void begin() throws IOException {
        if (used || closed) throw new IOException("Pairing attempt is no longer available");
        used = true;
    }
    private synchronized void checkOpen() throws IOException {
        if (closed) throw new IOException("Pairing was cancelled");
    }
    @Override public void close() {
        synchronized (this) { closed = true; }
        transport.close();
    }
    private static void require(Reply reply, int status, int size) throws IOException {
        if (reply.status != status || reply.body.length != size)
            throw new IOException("Pairing response was rejected");
    }
    private static boolean validGrants(int grants) { return grants > 0 && grants <= 7; }
    private static byte[] copy(byte[] value, int size) {
        if (value == null || value.length != size) throw new IllegalArgumentException("Invalid pairing input size");
        return value.clone();
    }
    private static byte[] label(String value) {
        if (value == null || value.isEmpty() || value.length() > 96)
            throw new IllegalArgumentException("Invalid device label");
        for (int i = 0; i < value.length(); ++i) if (value.charAt(i) < 32 || value.charAt(i) == 127)
            throw new IllegalArgumentException("Invalid device label");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            if (encoded.remaining() > 96) throw new IllegalArgumentException("Device label is too long");
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Invalid device label", failure);
        }
    }
    static void wipe(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }
    private static Crypto nativeSession(byte[] context, byte[] code) {
        NativePairing session = new NativePairing(NativePairing.CLIENT, context, code);
        return new Crypto() {
            public byte[] message() { return session.message(); }
            public void processPeer(byte[] peer) { session.processPeer(peer); }
            public void verifyConfirmation(byte[] mac) { session.verifyConfirmation(mac); }
            public byte[] confirmation() { return session.confirmation(); }
            public byte[] enrollmentProof() { return session.enrollmentProof(); }
            public void close() { session.close(); }
        };
    }
}
