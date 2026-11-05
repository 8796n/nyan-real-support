// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.Proxy;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import org.junit.Test;

public final class PcConnectionTest {
    static SpatialCredentials.Entry entry() throws Exception {
        return entry(7);
    }
    static SpatialCredentials.Entry entry(int grants) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        byte[] spki = generator.generateKeyPair().getPublic().getEncoded();
        byte[] plain = new byte[5 + 1 + 2 + 2 + spki.length + 16 + 32 + 1];
        byte[] id = new byte[16], secret = new byte[32];
        for (int i = 0; i < id.length; ++i) id[i] = (byte) (i + 0xa0);
        for (int i = 0; i < secret.length; ++i) secret[i] = (byte) i;
        ByteBuffer.wrap(plain).putInt(0x4e595332).put((byte) 1).put((byte) 2).put((byte) 'p').put((byte) 'c')
                .putShort((short) spki.length).put(spki).put(id).put(secret).put((byte) grants);
        try { return SpatialCredentials.decode(plain).get("pc"); }
        finally { Arrays.fill(plain, (byte) 0); Arrays.fill(secret, (byte) 0); }
    }

    @Test public void deviceHeaderIsExactLowerHexAndCopiesOwnTheirSecrets() throws Exception {
        try (SpatialCredentials.Entry entry = entry(); PcConnection pc = new PcConnection("192.168.1.10", 8797, entry)) {
            assertEquals("pc", pc.profileId); assertEquals("192.168.1.10", pc.host);
            assertEquals(8797, pc.port); assertEquals(7, pc.grants);
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(entry.spkiDer()), pc.spkiSha256());
            entry.close();
            byte[] expected = ("Device a0a1a2a3a4a5a6a7a8a9aaabacadaeaf."
                    + "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
                    .getBytes(StandardCharsets.US_ASCII);
            try (PcConnection copy = pc.copy()) {
                byte[] header = pc.authorization();
                try { assertEquals(104, header.length); assertArrayEquals(expected, header); }
                finally { PairingClient.wipe(header); }
                pc.close(); pc.close();
                assertThrows(IllegalStateException.class, pc::authorization);
                assertThrows(IllegalStateException.class, pc::copy);
                header = copy.authorization();
                try { assertArrayEquals(expected, header); }
                finally { PairingClient.wipe(header); }
                byte[] pin = copy.spkiSha256(); pin[0] ^= 1;
                assertFalse(Arrays.equals(pin, copy.spkiSha256()));
            } finally { PairingClient.wipe(expected); }
        }
    }

    @Test public void unavailableCredentialOrNonLanEndpointCannotStartAConnection() throws Exception {
        try (SpatialCredentials.Entry entry = entry()) {
            for (String host : new String[]{"192.168.1.1/evil", "host\r\ninjected"})
                assertThrows(IllegalArgumentException.class, () -> new PcConnection(host, 8797, entry));
            try (PcConnection named = new PcConnection("host.local", 8797, entry)) {
                assertEquals("host.local", named.host);
                assertThrows(IllegalStateException.class, named::wireHost);
            }
            for (String host : new String[]{"127.0.0.1", "8.8.8.8"}) {
                try (PcConnection pc = new PcConnection(host, 8797, entry); Socket raw = new Socket(Proxy.NO_PROXY)) {
                    assertThrows(IOException.class, () -> pc.connect(raw));
                    assertTrue(raw.isClosed()); assertFalse(raw.isConnected());
                }
            }
            for (int port : new int[]{0, 65536})
                assertThrows(IllegalArgumentException.class, () -> new PcConnection("192.168.1.10", port, entry));
            entry.close();
            assertThrows(IllegalStateException.class, () -> new PcConnection("192.168.1.10", 8797, entry));
        }
        assertThrows(IllegalArgumentException.class, () -> new PcConnection("192.168.1.10", 8797, null));
    }

    @Test public void closedSnapshotClosesCallerSocketWithoutNetwork() throws Exception {
        try (SpatialCredentials.Entry entry = entry(); PcConnection pc = new PcConnection("192.168.1.10", 8797, entry);
             Socket raw = new Socket(Proxy.NO_PROXY)) {
            pc.close();
            assertThrows(IOException.class, () -> pc.connect(raw));
            assertTrue(raw.isClosed()); assertFalse(raw.isConnected());
        }
    }
}
