// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import static org.junit.Assert.*;
import java.io.IOException;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Map;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Test;

public final class SpatialCredentialsTest {
    private static final class Disk {
        byte[] encrypted;
        SecretKey key;
        int creates, writes;
    }
    private static final class Storage implements SpatialCredentials.Storage {
        final Disk disk;
        boolean closed, failRead, failWrite;
        byte[] readBuffer, writeBuffer;
        Storage(Disk disk) { this.disk = disk; }
        public byte[] read() throws IOException {
            if (failRead) throw new IOException("Fixture read failure");
            return readBuffer = disk.encrypted == null ? null : disk.encrypted.clone();
        }
        public SecretKey existingKey() { return disk.key; }
        public SecretKey createKey() throws java.security.GeneralSecurityException {
            assertNull(disk.key); ++disk.creates;
            KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256);
            return disk.key = generator.generateKey();
        }
        public void write(byte[] encrypted) throws IOException {
            writeBuffer = encrypted;
            if (failWrite) throw new IOException("Fixture atomic replacement failure");
            disk.encrypted = encrypted.clone(); ++disk.writes;
        }
        public void close() { closed = true; }
    }
    private static byte[] spki() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair().getPublic().getEncoded();
    }
    private static PairingClient.Result result(byte[] spki, int secret) throws Exception {
        byte[] wire = new byte[52 + spki.length]; wire[0] = 2;
        Arrays.fill(wire, 1, 17, (byte) 7); Arrays.fill(wire, 17, 49, (byte) secret);
        wire[49] = 7; wire[51] = (byte) spki.length;
        System.arraycopy(spki, 0, wire, 52, spki.length);
        try {
            return PairingClient.parseResult(wire, MessageDigest.getInstance("SHA-256").digest(spki), new byte[32], 7);
        } finally { Arrays.fill(wire, (byte) 0); }
    }
    private static void checkSecret(SpatialCredentials.Entry entry, int value) {
        byte[] secret = entry.credential(), expected = new byte[32]; Arrays.fill(expected, (byte) value);
        try { assertArrayEquals(expected, secret); }
        finally { Arrays.fill(secret, (byte) 0); Arrays.fill(expected, (byte) 0); }
    }

    @Test public void encryptedRoundTripUsesIndependentCopiesAndStableProfileIds() throws Exception {
        Disk disk = new Disk(); Storage first = new Storage(disk); byte[] spki = spki();
        try (SpatialCredentials store = new SpatialCredentials(first); PairingClient.Result result = result(spki, 88)) {
            assertTrue(store.profileIds().isEmpty()); assertNull(store.get("unknown"));
            store.put("profile-1", result);
            result.close();
            try (SpatialCredentials.Entry saved = store.get("profile-1")) {
                assertEquals("profile-1", saved.profileId); assertEquals(7, saved.grants);
                assertArrayEquals(spki, saved.spkiDer());
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(spki), saved.spkiSha256());
                checkSecret(saved, 88);
                byte[] copy = saved.credential(); Arrays.fill(copy, (byte) 0); checkSecret(saved, 88);
            }
            assertArrayEquals(new byte[first.writeBuffer.length], first.writeBuffer);
        }
        assertTrue(first.closed); assertEquals(1, disk.creates);
        Storage second = new Storage(disk);
        try (SpatialCredentials reopened = new SpatialCredentials(second);
             SpatialCredentials.Entry saved = reopened.get("profile-1")) {
            checkSecret(saved, 88);
            assertArrayEquals(new byte[second.readBuffer.length], second.readBuffer);
            assertThrows(UnsupportedOperationException.class, () -> reopened.profileIds().clear());
        }
    }

    @Test public void successfulReplacementAndRemovalCannotRestoreOldCredential() throws Exception {
        Disk disk = new Disk(); byte[] spki = spki();
        try (SpatialCredentials store = new SpatialCredentials(new Storage(disk));
             PairingClient.Result first = result(spki, 33); PairingClient.Result next = result(spki, 44)) {
            store.put("pc", first); byte[] old = disk.encrypted.clone();
            store.put("pc", next); assertFalse(Arrays.equals(old, disk.encrypted));
            try (SpatialCredentials.Entry saved = store.get("pc")) { checkSecret(saved, 44); }
            assertTrue(store.remove("pc")); assertFalse(store.remove("pc")); assertNull(store.get("pc"));
        }
        try (SpatialCredentials reopened = new SpatialCredentials(new Storage(disk))) {
            assertTrue(reopened.profileIds().isEmpty()); assertNull(reopened.get("pc"));
        }
        assertEquals(1, disk.creates);
    }

    @Test public void failedWritePoisonsOwnerWithoutPublishingMemoryOrReplacingDisk() throws Exception {
        Disk disk = new Disk(); Storage storage = new Storage(disk); byte[] spki = spki();
        try (SpatialCredentials store = new SpatialCredentials(storage);
             PairingClient.Result first = result(spki, 33); PairingClient.Result next = result(spki, 44)) {
            store.put("pc", first); byte[] before = disk.encrypted.clone(); storage.failWrite = true;
            assertThrows(IOException.class, () -> store.put("pc", next));
            assertTrue(storage.closed); assertArrayEquals(before, disk.encrypted);
            assertArrayEquals(new byte[storage.writeBuffer.length], storage.writeBuffer);
            assertThrows(IOException.class, store::profileIds);
            assertThrows(IOException.class, () -> store.get("pc"));
            assertThrows(IOException.class, () -> store.remove("pc"));
        }
        try (SpatialCredentials explicitReload = new SpatialCredentials(new Storage(disk));
             SpatialCredentials.Entry saved = explicitReload.get("pc")) { checkSecret(saved, 33); }
    }

    @Test public void failedRemovalAlsoPoisonsOwner() throws Exception {
        Disk disk = new Disk(); Storage storage = new Storage(disk);
        try (SpatialCredentials store = new SpatialCredentials(storage); PairingClient.Result result = result(spki(), 77)) {
            store.put("pc", result); storage.failWrite = true;
            assertThrows(IOException.class, () -> store.remove("pc"));
            assertThrows(IOException.class, () -> store.get("pc")); assertTrue(storage.closed);
        }
    }

    @Test public void missingKeyMissingFileAndReadFailureNeverCreateReplacementKey() throws Exception {
        Disk disk = new Disk();
        try (SpatialCredentials ignored = new SpatialCredentials(new Storage(disk))) {}
        SecretKey original = disk.key; byte[] originalFile = disk.encrypted;
        disk.key = null; Storage lostKey = new Storage(disk);
        assertThrows(IOException.class, () -> new SpatialCredentials(lostKey));
        assertTrue(lostKey.closed); assertEquals(1, disk.creates); assertNull(disk.key);
        disk.key = original; disk.encrypted = null; Storage lostFile = new Storage(disk);
        assertThrows(IOException.class, () -> new SpatialCredentials(lostFile));
        assertTrue(lostFile.closed); assertEquals(1, disk.creates);
        disk.encrypted = originalFile; Storage readFailure = new Storage(disk); readFailure.failRead = true;
        assertThrows(IOException.class, () -> new SpatialCredentials(readFailure));
        assertTrue(readFailure.closed); assertEquals(1, disk.creates);
    }

    @Test public void tamperedCiphertextIvVersionAndWrongKeyFailClosed() throws Exception {
        Disk disk = new Disk();
        try (SpatialCredentials ignored = new SpatialCredentials(new Storage(disk))) {}
        byte[] original = disk.encrypted.clone();
        for (int index : new int[]{0, 4, 15, 16, original.length - 1}) {
            disk.encrypted = original.clone(); disk.encrypted[index] ^= 1;
            Storage storage = new Storage(disk);
            assertThrows(IOException.class, () -> new SpatialCredentials(storage));
            assertTrue(storage.closed); assertArrayEquals(new byte[storage.readBuffer.length], storage.readBuffer);
        }
        disk.encrypted = original;
        KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256); disk.key = generator.generateKey();
        assertThrows(IOException.class, () -> new SpatialCredentials(new Storage(disk)));
        assertEquals(1, disk.creates);
    }

    @Test public void canonicalSnapshotRejectsTruncationTrailingBytesDuplicatesAndBadFields() throws Exception {
        Disk disk = new Disk(); byte[] spki = spki();
        try (SpatialCredentials store = new SpatialCredentials(new Storage(disk)); PairingClient.Result result = result(spki, 77)) {
            store.put("a", result); store.put("b", result);
        }
        byte[] plain = SpatialCredentials.decrypt(disk.key, disk.encrypted);
        try {
            for (int size = 0; size < plain.length; ++size) {
                byte[] shorter = Arrays.copyOf(plain, size);
                try { assertThrows(IOException.class, () -> SpatialCredentials.decode(shorter)); }
                finally { Arrays.fill(shorter, (byte) 0); }
            }
            byte[] extra = Arrays.copyOf(plain, plain.length + 1);
            try { assertThrows(IOException.class, () -> SpatialCredentials.decode(extra)); }
            finally { Arrays.fill(extra, (byte) 0); }
            int secondId = 5 + 1 + 1 + 2 + spki.length + 49 + 1;
            for (int[] change : new int[][]{{0, 0}, {4, 65}, {5, 0}, {6, '_'}, {7, 1},
                    {secondId, 'a'}, {6, 'z'}, {plain.length - 1, 0}}) {
                byte[] bad = plain.clone(); bad[change[0]] = (byte) change[1];
                try { assertThrows(IOException.class, () -> SpatialCredentials.decode(bad)); }
                finally { Arrays.fill(bad, (byte) 0); }
            }
            byte[] bad = plain.clone(); bad[4] = 65;
            disk.encrypted = SpatialCredentials.encrypt(disk.key, bad); Arrays.fill(bad, (byte) 0);
            Storage storage = new Storage(disk);
            assertThrows(IOException.class, () -> new SpatialCredentials(storage)); assertTrue(storage.closed);
        } finally { Arrays.fill(plain, (byte) 0); }
    }

    @Test public void samePlaintextUsesFreshIvAndRejectsOversizedOrLegacyFiles() throws Exception {
        Disk disk = new Disk();
        try (SpatialCredentials ignored = new SpatialCredentials(new Storage(disk))) {}
        byte[] plain = SpatialCredentials.decrypt(disk.key, disk.encrypted);
        try {
            byte[] other = SpatialCredentials.encrypt(disk.key, plain);
            assertFalse(Arrays.equals(Arrays.copyOfRange(disk.encrypted, 4, 16), Arrays.copyOfRange(other, 4, 16)));
            assertArrayEquals(plain, SpatialCredentials.decrypt(disk.key, other));
        } finally { Arrays.fill(plain, (byte) 0); }
        for (int size : new int[]{0, 36, SpatialCredentials.MAX_ENCRYPTED + 1}) {
            disk.encrypted = new byte[size];
            assertThrows(IOException.class, () -> new SpatialCredentials(new Storage(disk)));
        }
        disk.encrypted = "<map><string name='token'>ABCDEFABCDEF</string></map>".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertThrows(IOException.class, () -> new SpatialCredentials(new Storage(disk)));
    }

    @Test public void capacityAndProfileIdAreBoundedAndClosedResultsCannotBeSaved() throws Exception {
        Disk disk = new Disk();
        try (SpatialCredentials store = new SpatialCredentials(new Storage(disk)); PairingClient.Result result = result(spki(), 1)) {
            for (int i = 0; i < 64; ++i) store.put("pc-" + i, result);
            assertEquals(64, store.profileIds().size()); store.put("pc-0", result);
            assertThrows(IOException.class, () -> store.put("pc-64", result));
            assertThrows(IOException.class, store::profileIds);
        }
        for (String id : new String[]{"", "a_b", "a".repeat(65)}) {
            try (SpatialCredentials store = new SpatialCredentials(new Storage(new Disk())); PairingClient.Result result = result(spki(), 1)) {
                assertThrows(IOException.class, () -> store.put(id, result));
            }
        }
        try (SpatialCredentials store = new SpatialCredentials(new Storage(new Disk())); PairingClient.Result result = result(spki(), 1)) {
            result.close(); assertThrows(IOException.class, () -> store.put("pc", result));
        }
    }
}
