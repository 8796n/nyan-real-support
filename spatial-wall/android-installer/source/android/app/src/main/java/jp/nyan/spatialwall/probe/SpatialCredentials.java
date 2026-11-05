// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

// Only v2 authenticated PC identities and device credentials, indexed by stable UI profile id.
// The owner keeps this open across sessions; every returned Entry is a caller-owned copy.
// On storage failure the caller also stops existing sessions and closes their credential copies.
final class SpatialCredentials implements AutoCloseable {
    static final int MAX_PLAIN = 32768, MAX_ENCRYPTED = MAX_PLAIN + 32;
    private static final int MAGIC = 0x4e595332; // NYS2, both envelope and authenticated snapshot.
    private static final byte[] AAD = "nyan-spatial-credentials-v2".getBytes(StandardCharsets.US_ASCII);
    interface Storage extends AutoCloseable {
        byte[] read() throws IOException; // null means only the authoritative file is absent.
        SecretKey existingKey() throws GeneralSecurityException, IOException;
        SecretKey createKey() throws GeneralSecurityException, IOException;
        void write(byte[] encrypted) throws IOException;
        @Override void close();
    }
    static final class Entry implements AutoCloseable {
        final String profileId;
        final int grants;
        private final byte[] spki, deviceId, secret;
        private boolean closed;
        private Entry(String profileId, byte[] spki, byte[] deviceId, byte[] secret, int grants)
                throws IOException {
            if (!validId(profileId) || deviceId == null || deviceId.length != 16
                    || secret == null || secret.length != 32 || grants < 1 || grants > 7
                    || spki == null || spki.length < 1 || spki.length > 256)
                throw new IOException("Invalid saved Spatial Wall credential");
            try {
                if (!Arrays.equals(spki, KeyFactory.getInstance("EC")
                        .generatePublic(new X509EncodedKeySpec(spki)).getEncoded()))
                    throw new IOException("Invalid saved PC public key");
            } catch (GeneralSecurityException failure) {
                throw new IOException("Invalid saved PC public key", failure);
            }
            this.profileId = profileId; this.grants = grants;
            this.spki = spki.clone(); this.deviceId = deviceId.clone(); this.secret = secret.clone();
        }
        byte[] spkiDer() { return spki.clone(); }
        byte[] deviceId() { return deviceId.clone(); }
        byte[] spkiSha256() {
            try { return MessageDigest.getInstance("SHA-256").digest(spki); }
            catch (GeneralSecurityException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
        }
        synchronized byte[] credential() {
            if (closed) throw new IllegalStateException("Saved credential is closed");
            return secret.clone();
        }
        private synchronized Entry copy() throws IOException {
            if (closed) throw new IOException("Saved credential is closed");
            return new Entry(profileId, spki, deviceId, secret, grants);
        }
        @Override public synchronized void close() { closed = true; PairingClient.wipe(secret); }
    }

    private final Storage storage;
    private SecretKey key;
    private Map<String, Entry> entries = new TreeMap<>();
    private boolean closed;

    SpatialCredentials(Storage storage) throws IOException {
        if (storage == null) throw new IllegalArgumentException("Missing credential storage");
        this.storage = storage;
        byte[] encrypted = null, plain = null;
        try {
            encrypted = storage.read();
            key = storage.existingKey();
            if (encrypted == null) {
                // A key without its snapshot may be an interrupted creation or lost file, never a new store.
                if (key != null) throw new IOException("Saved credential file is missing");
                key = storage.createKey();
                if (key == null) throw new IOException("Credential key is unavailable");
                persist(entries);
            } else {
                if (key == null) throw new IOException("Saved credential key is missing");
                plain = decrypt(key, encrypted);
                entries = decode(plain);
            }
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            close();
            throw new IOException("Could not open Spatial Wall credentials", failure);
        } finally { PairingClient.wipe(plain); PairingClient.wipe(encrypted); }
    }

    synchronized Set<String> profileIds() throws IOException { requireOpen(); return Set.copyOf(entries.keySet()); }
    synchronized Entry get(String profileId) throws IOException {
        requireOpen();
        Entry entry = entries.get(profileId);
        return entry == null ? null : entry.copy();
    }
    // Only a fully verified enrollment Result can add/replace a saved identity.
    synchronized void put(String profileId, PairingClient.Result result) throws IOException {
        requireOpen();
        Entry candidate = null;
        byte[] secret = null;
        try {
            if (result == null) throw new IOException("Missing verified enrollment result");
            secret = result.credential();
            candidate = new Entry(profileId, result.spkiDer(), result.deviceId(), secret, result.grants);
            Map<String, Entry> next = new TreeMap<>(entries);
            Entry previous = next.put(profileId, candidate);
            if (next.size() > 64) throw new IOException("Too many saved PC credentials");
            persist(next);
            entries = next;
            candidate = null;
            if (previous != null) previous.close();
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            close();
            throw new IOException("Could not save Spatial Wall credentials", failure);
        } finally {
            PairingClient.wipe(secret);
            if (candidate != null) candidate.close();
        }
    }
    synchronized boolean remove(String profileId) throws IOException {
        requireOpen();
        if (!entries.containsKey(profileId)) return false;
        try {
            Map<String, Entry> next = new TreeMap<>(entries);
            Entry removed = next.remove(profileId);
            persist(next);
            entries = next;
            removed.close();
            return true;
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            close();
            throw new IOException("Could not remove Spatial Wall credential", failure);
        }
    }
    private void persist(Map<String, Entry> next) throws GeneralSecurityException, IOException {
        byte[] plain = null, encrypted = null;
        try {
            plain = encode(next);
            encrypted = encrypt(key, plain);
            storage.write(encrypted);
        } finally { PairingClient.wipe(plain); PairingClient.wipe(encrypted); }
    }
    private void requireOpen() throws IOException {
        if (closed) throw new IOException("Spatial Wall credentials are unavailable");
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Entry entry : entries.values()) entry.close();
        entries.clear();
        key = null;
        storage.close();
    }

    static byte[] encode(Map<String, Entry> entries) throws IOException {
        if (entries.size() > 64) throw new IOException("Too many saved PC credentials");
        int size = 5;
        for (Entry entry : entries.values()) size += 1 + entry.profileId.length() + 2 + entry.spki.length + 16 + 32 + 1;
        if (size > MAX_PLAIN) throw new IOException("Saved credentials exceed the size limit");
        byte[] plain = new byte[size];
        boolean complete = false;
        try {
            ByteBuffer out = ByteBuffer.wrap(plain);
            out.putInt(MAGIC).put((byte) entries.size());
            for (Entry entry : new TreeMap<>(entries).values()) {
                byte[] id = entry.profileId.getBytes(StandardCharsets.US_ASCII);
                out.put((byte) id.length).put(id).putShort((short) entry.spki.length).put(entry.spki)
                        .put(entry.deviceId).put(entry.secret).put((byte) entry.grants);
            }
            complete = true;
            return plain;
        } finally { if (!complete) PairingClient.wipe(plain); }
    }
    static Map<String, Entry> decode(byte[] plain) throws IOException {
        if (plain == null || plain.length < 5 || plain.length > MAX_PLAIN)
            throw new IOException("Invalid credential snapshot size");
        Map<String, Entry> result = new TreeMap<>();
        boolean complete = false;
        try {
            ByteBuffer input = ByteBuffer.wrap(plain);
            if (input.getInt() != MAGIC) throw new IOException("Unsupported credential snapshot version");
            int count = input.get() & 255;
            if (count > 64) throw new IOException("Too many saved PC credentials");
            String previous = "";
            for (int i = 0; i < count; ++i) {
                byte[] id = take(input, 1, 64);
                String profileId = new String(id, StandardCharsets.US_ASCII);
                if (!validId(profileId) || profileId.compareTo(previous) <= 0)
                    throw new IOException("Invalid or duplicate saved profile id");
                previous = profileId;
                int spkiSize = input.getShort() & 65535;
                if (spkiSize < 1 || spkiSize > 256 || input.remaining() < spkiSize + 49)
                    throw new IOException("Invalid saved credential length");
                byte[] spki = new byte[spkiSize], deviceId = new byte[16], secret = new byte[32];
                try {
                    input.get(spki).get(deviceId).get(secret);
                    Entry entry = new Entry(profileId, spki, deviceId, secret, input.get() & 255);
                    result.put(profileId, entry);
                } finally { PairingClient.wipe(secret); }
            }
            if (input.hasRemaining()) throw new IOException("Trailing credential snapshot data");
            complete = true;
            return result;
        } catch (RuntimeException failure) {
            throw new IOException("Invalid credential snapshot", failure);
        } finally { if (!complete) for (Entry entry : result.values()) entry.close(); }
    }
    private static byte[] take(ByteBuffer input, int minimum, int maximum) throws IOException {
        int size = input.get() & 255;
        if (size < minimum || size > maximum || input.remaining() < size)
            throw new IOException("Invalid saved profile id length");
        byte[] value = new byte[size]; input.get(value); return value;
    }
    private static boolean validId(String id) { return id != null && id.matches("[a-zA-Z0-9-]{1,64}"); }

    static byte[] encrypt(SecretKey key, byte[] plain) throws GeneralSecurityException, IOException {
        if (plain == null || plain.length < 5 || plain.length > MAX_PLAIN)
            throw new IOException("Invalid credential snapshot size");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key); // Provider generates a fresh randomized IV.
        cipher.updateAAD(AAD);
        byte[] ciphertext = cipher.doFinal(plain);
        try {
            byte[] iv = cipher.getIV();
            if (iv.length != 12) throw new GeneralSecurityException("Invalid credential GCM IV");
            byte[] envelope = new byte[16 + ciphertext.length];
            ByteBuffer.wrap(envelope).putInt(MAGIC).put(iv).put(ciphertext);
            return envelope;
        } finally { PairingClient.wipe(ciphertext); }
    }
    static byte[] decrypt(SecretKey key, byte[] encrypted) throws GeneralSecurityException, IOException {
        if (encrypted.length < 37 || encrypted.length > MAX_ENCRYPTED
                || ByteBuffer.wrap(encrypted).getInt() != MAGIC)
            throw new IOException("Invalid encrypted credential snapshot");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, encrypted, 4, 12));
        cipher.updateAAD(AAD);
        return cipher.doFinal(encrypted, 16, encrypted.length - 16);
    }
}
