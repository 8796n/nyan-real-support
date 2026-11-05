// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import org.junit.Test;

public final class PairingClientTest {
    private static final byte[] CODE = {'0', '1', '2', '3', '4', '5'};
    private static byte[] bytes(int size, int value) {
        byte[] bytes = new byte[size]; Arrays.fill(bytes, (byte) value); return bytes;
    }
    private static final class Crypto implements PairingClient.Crypto {
        boolean peer, verified, confirmed, closed, reject;
        byte[] codeCopy, proof;
        public byte[] message() { return bytes(32, 11); }
        public void processPeer(byte[] message) { assertArrayEquals(bytes(32, 22), message); peer = true; }
        public void verifyConfirmation(byte[] mac) {
            assertTrue(peer); assertArrayEquals(bytes(32, 33), mac);
            if (reject) throw new IllegalStateException("Rejected fixture server");
            verified = true;
        }
        public byte[] confirmation() { assertTrue(verified); return bytes(32, 44); }
        public byte[] enrollmentProof() { assertTrue(confirmed); proof = bytes(32, 55); return proof; }
        public void close() { closed = true; }
    }
    private static final class Fixture implements PairingClient.Transport {
        final byte[] context = bytes(98, 9), spki, result;
        final Crypto crypto = new Crypto();
        int calls;
        boolean closed, invite, rejectConfirm;
        byte[] authReference, resultReference, decoded;
        Fixture() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            spki = generator.generateKeyPair().getPublic().getEncoded();
            context[0] = 2; context[97] = 7;
            System.arraycopy(MessageDigest.getInstance("SHA-256").digest(spki), 0, context, 33, 32);
            result = new byte[52 + spki.length]; result[0] = 2;
            Arrays.fill(result, 1, 17, (byte) 66); Arrays.fill(result, 17, 49, (byte) 77);
            result[49] = 7; result[51] = (byte) spki.length;
            System.arraycopy(spki, 0, result, 52, spki.length);
        }
        byte[] pin() { return Arrays.copyOfRange(context, 33, 65); }
        byte[] leaf() { return Arrays.copyOfRange(context, 65, 97); }
        PairingClient client() {
            return new PairingClient(this, (contextCopy, code) -> {
                assertArrayEquals(context, contextCopy); assertArrayEquals(CODE, code);
                crypto.codeCopy = code;
                return crypto;
            });
        }
        public PairingClient.Reply request(String method, String path, byte[] pin, byte[] leaf,
                                           byte[] authorization, byte[] body) {
            assertFalse(closed); ++calls;
            if ("/pair-v2".equals(path)) {
                assertNull(pin); assertNull(leaf); assertNull(authorization);
                if (calls == 1) {
                    assertEquals("GET", method); assertEquals(0, body.length);
                    return new PairingClient.Reply(200, context.clone());
                }
                assertEquals("POST", method);
                assertArrayEquals(Arrays.copyOfRange(context, 1, 33), Arrays.copyOfRange(body, 1, 33));
                if (calls == 2) {
                    assertEquals(65, body.length); assertEquals(1, body[0]);
                    assertArrayEquals(bytes(32, 11), Arrays.copyOfRange(body, 33, 65));
                    byte[] reply = new byte[65]; reply[0] = 5;
                    Arrays.fill(reply, 1, 33, (byte) 22); Arrays.fill(reply, 33, 65, (byte) 33);
                    return new PairingClient.Reply(200, reply);
                }
                assertEquals(3, calls); assertEquals(66, body.length); assertEquals(2, body[0]);
                assertEquals(5, body[33]);
                assertArrayEquals(bytes(32, 44), Arrays.copyOfRange(body, 34, 66));
                crypto.confirmed = true;
                return new PairingClient.Reply(rejectConfirm ? 403 : 204, new byte[0]);
            }
            assertEquals("/enroll-v2", path); assertEquals("POST", method); assertEquals(0, body.length);
            assertArrayEquals(pin(), pin); assertArrayEquals(leaf(), leaf);
            assertEquals(invite ? 1 : 4, calls);
            byte[] prefix = (invite ? "Invite " : "Pairing ").getBytes(StandardCharsets.US_ASCII);
            assertArrayEquals(prefix, Arrays.copyOf(authorization, prefix.length));
            byte[] encoded = Arrays.copyOfRange(authorization, prefix.length, authorization.length);
            decoded = Base64.getUrlDecoder().decode(encoded);
            assertArrayEquals(encoded, Base64.getUrlEncoder().withoutPadding().encode(decoded));
            authReference = authorization;
            resultReference = result.clone();
            return new PairingClient.Reply(200, resultReference);
        }
        public void close() { closed = true; }
    }

    @Test public void codeExchangeConfirmsBeforeTlsAndErasesOwnedSecrets() throws Exception {
        Fixture fixture = new Fixture();
        byte[] callerCode = CODE.clone();
        PairingClient client = fixture.client();
        PairingClient.Result result = client.pairCode(callerCode, "スマホ");
        assertEquals(4, fixture.calls); assertTrue(fixture.closed); assertTrue(fixture.crypto.closed);
        assertArrayEquals(CODE, callerCode);
        assertArrayEquals(new byte[6], fixture.crypto.codeCopy);
        assertArrayEquals(new byte[32], fixture.crypto.proof);
        assertArrayEquals(new byte[fixture.authReference.length], fixture.authReference);
        assertArrayEquals(new byte[fixture.resultReference.length], fixture.resultReference);
        assertArrayEquals(Arrays.copyOfRange(fixture.context, 1, 33), Arrays.copyOf(fixture.decoded, 32));
        assertEquals(5, fixture.decoded[32]);
        assertArrayEquals(bytes(32, 55), Arrays.copyOfRange(fixture.decoded, 33, 65));
        assertArrayEquals("スマホ".getBytes(StandardCharsets.UTF_8),
                Arrays.copyOfRange(fixture.decoded, 65, fixture.decoded.length));
        assertEquals(7, result.grants); assertArrayEquals(bytes(16, 66), result.deviceId());
        assertArrayEquals(fixture.spki, result.spkiDer()); assertArrayEquals(fixture.pin(), result.spkiSha256());
        assertArrayEquals(fixture.leaf(), result.leafSha256());
        byte[] credential = result.credential(); credential[0] = 0;
        assertArrayEquals(bytes(32, 77), result.credential());
        result.close(); result.close();
        assertThrows(IllegalStateException.class, result::credential);
        assertThrows(IOException.class, () -> client.pairCode(CODE, "retry"));
        assertEquals(4, fixture.calls);
    }

    @Test public void failedServerMacNeverSendsConfirmationOrProofAndCannotRetry() throws Exception {
        Fixture fixture = new Fixture(); fixture.crypto.reject = true;
        PairingClient client = fixture.client();
        assertThrows(IOException.class, () -> client.pairCode(CODE, "phone"));
        assertEquals(2, fixture.calls); assertNull(fixture.crypto.proof); assertTrue(fixture.crypto.closed);
        assertArrayEquals(new byte[6], fixture.crypto.codeCopy);
        assertThrows(IOException.class, () -> client.pairCode(CODE, "phone"));
        assertEquals(2, fixture.calls);
    }

    @Test public void failedHttpConfirmationDoesNotExposeProof() throws Exception {
        Fixture fixture = new Fixture(); fixture.rejectConfirm = true;
        assertThrows(IOException.class, () -> fixture.client().pairCode(CODE, "phone"));
        assertEquals(3, fixture.calls); assertNull(fixture.crypto.proof); assertTrue(fixture.crypto.closed);
    }

    @Test public void trustedQrGoesDirectlyToPinnedTlsAndPreservesCallerArrays() throws Exception {
        Fixture fixture = new Fixture(); fixture.invite = true;
        byte[] invite = bytes(32, 88), pin = fixture.pin(), leaf = fixture.leaf();
        try (PairingClient.Result result = fixture.client().enrollInvite(pin, leaf, 7, invite, "phone")) {
            assertEquals(1, fixture.calls); assertArrayEquals(bytes(32, 88), invite);
            assertArrayEquals(fixture.pin(), pin); assertArrayEquals(fixture.leaf(), leaf);
            assertArrayEquals(invite, Arrays.copyOf(fixture.decoded, 32));
            assertArrayEquals("phone".getBytes(StandardCharsets.UTF_8),
                    Arrays.copyOfRange(fixture.decoded, 32, fixture.decoded.length));
            assertArrayEquals(bytes(32, 77), result.credential());
            assertNull(fixture.crypto.proof);
        }
    }

    @Test public void malformedInputClosesWithoutNetwork() throws Exception {
        for (String label : new String[]{"", "a\n", "a\u007f", "\ud800", "あ".repeat(33)}) {
            Fixture fixture = new Fixture();
            assertThrows(IllegalArgumentException.class, () -> fixture.client().pairCode(CODE, label));
            assertTrue(fixture.closed); assertEquals(0, fixture.calls);
        }
        Fixture fixture = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> fixture.client().pairCode(bytes(6, 'a'), "phone"));
        assertEquals(0, fixture.calls);
    }

    @Test public void responseRequiresExactVersionLengthPinAndGrants() throws Exception {
        Fixture fixture = new Fixture();
        for (int index : new int[]{0, 49, 50, 51, 52}) {
            byte[] changed = fixture.result.clone(); changed[index] ^= 1;
            assertThrows(IOException.class, () -> PairingClient.parseResult(changed, fixture.pin(), fixture.leaf(), 7));
        }
        for (int size : new int[]{0, 52, fixture.result.length - 1, fixture.result.length + 1}) {
            byte[] changed = Arrays.copyOf(fixture.result, size);
            assertThrows(IOException.class, () -> PairingClient.parseResult(changed, fixture.pin(), fixture.leaf(), 7));
        }
        byte[] wrongPin = fixture.pin(); wrongPin[0] ^= 1;
        assertThrows(IOException.class, () -> PairingClient.parseResult(fixture.result, wrongPin, fixture.leaf(), 7));
        assertThrows(IOException.class, () -> PairingClient.parseResult(fixture.result, fixture.pin(), fixture.leaf(), 1));
        byte[] invalidDer = fixture.result.clone(); invalidDer[52] = 0;
        byte[] pin = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOfRange(invalidDer, 52, invalidDer.length));
        assertThrows(IOException.class, () -> PairingClient.parseResult(invalidDer, pin, fixture.leaf(), 7));
    }
}
