// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import static org.junit.Assert.*;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import org.junit.Test;

public final class PairingLinkTest {
    private static final String HTTPS = "https://support.nyanreal.jp/spatial-wall/remote/#";
    private static String encoded(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static byte[] spki(String curve) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curve));
        return generator.generateKeyPair().getPublic().getEncoded();
    }
    private static String fields(byte[] spki) {
        return "v=2&host=192.168.1.10&port=8797&spki=" + encoded(spki)
                + "&cert=" + encoded(new byte[32]) + "&invite=" + encoded(new byte[32]) + "&grants=7";
    }

    @Test public void officialQrAndExplicitAppHandoffDescribeTheSameIdentity() throws Exception {
        byte[] spki = spki("secp256r1");
        try (PairingLink qr = PairingLink.parse(HTTPS + fields(spki));
             PairingLink app = PairingLink.parse("nyanreal://pair?" + fields(spki))) {
            assertEquals("192.168.1.10", qr.host); assertEquals(8797, qr.port); assertEquals(7, qr.grants);
            assertEquals(qr.host, app.host); assertEquals(qr.port, app.port);
            assertArrayEquals(spki, qr.spkiDer());
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(spki), qr.spkiSha256());
            assertArrayEquals(qr.spkiSha256(), app.spkiSha256());
            assertArrayEquals(new byte[32], qr.leafSha256());
            byte[] invite = qr.invitation(); invite[0] = 1;
            assertArrayEquals(new byte[32], qr.invitation());
            qr.close(); qr.close();
            assertThrows(IllegalStateException.class, qr::invitation);
            assertArrayEquals(new byte[32], app.invitation());
        }
    }

    @Test public void onlyTheFixedHttpsOriginFragmentAndCustomQueryAreAccepted() throws Exception {
        String fields = fields(spki("secp256r1"));
        for (String prefix : new String[]{"http://support.nyanreal.jp/spatial-wall/remote/#",
                "https://support.nyanreal.jp.evil.test/spatial-wall/remote/#",
                "https://user@support.nyanreal.jp/spatial-wall/remote/#",
                "https://support.nyanreal.jp:443/spatial-wall/remote/#",
                "https://support.nyanreal.jp/spatial-wall/remote?",
                "https://support.nyanreal.jp/spatial-wall/remote/?",
                "https://support.nyanreal.jp/spatial-wall/remote/?x=1#",
                "nyanreal://pair#", "nyanreal://pair/?", "nyanreal://pair:80?",
                "nyanreal://other?", "intent://pair?"})
            assertThrows(prefix, IllegalArgumentException.class, () -> PairingLink.parse(prefix + fields));
        assertThrows(IllegalArgumentException.class, () -> PairingLink.parse("nyanreal://pair?" + fields + "#extra"));
        assertThrows(IllegalArgumentException.class, () -> PairingLink.parse(HTTPS + fields + "\n"));
        assertThrows(IllegalArgumentException.class, () -> PairingLink.parse(null));
        assertThrows(IllegalArgumentException.class, () -> PairingLink.parse(HTTPS + "x".repeat(2048)));
    }

    @Test public void duplicatesLegacyFieldsAndNonCanonicalNumbersAreRejected() throws Exception {
        String fields = fields(spki("secp256r1"));
        for (String changed : new String[]{fields + "&v=2", fields.replace("&grants=7", "&v=2"),
                fields.replace("v=2", "v=1"), fields.replace("v=2", "%76=2"),
                fields.replace("port=8797", "port=08797"), fields.replace("port=8797", "port=0"),
                fields.replace("port=8797", "port=65536"), fields.replace("port=8797", "port=+8797"),
                fields.replace("grants=7", "grants=8"), fields.replace("grants=7", "grants=0"),
                fields.replace("grants=7", "grants=01"), fields.replace("grants=7", "t=AAAAAAAAAAAA"),
                fields.replace("&grants=7", ""), fields + "&", fields.replace("&port", "&&port")})
            assertThrows(IllegalArgumentException.class, () -> PairingLink.parse(HTTPS + changed));
        assertThrows(IllegalArgumentException.class,
                () -> PairingLink.parse("nyanreal://pair?host=192.168.1.10&port=8797&t=AAAAAAAAAAAA"));
    }

    @Test public void qrHostMustBeCanonicalNumericLanIpv4() throws Exception {
        String fields = fields(spki("secp256r1"));
        for (String host : new String[]{"127.0.0.1", "8.8.8.8", "0.0.0.0", "192.168.001.10",
                "home.local", "[::1]", "192%2e168.1.10", "192.168.1.10:80", "192.168.1.10/evil"})
            assertThrows(host, IllegalArgumentException.class,
                    () -> PairingLink.parse(HTTPS + fields.replace("192.168.1.10", host)));
        for (String host : new String[]{"10.0.0.1", "172.16.0.1", "169.254.1.2"})
            try (PairingLink link = PairingLink.parse(HTTPS + fields.replace("192.168.1.10", host))) {
                assertEquals(host, link.host);
            }
    }

    @Test public void publicKeyAndSecretEncodingsAreStrictAndExceptionsOmitTheUrl() throws Exception {
        String fields = fields(spki("secp256r1")), zero = encoded(new byte[32]);
        for (String key : new String[]{"cert", "invite"}) {
            for (String value : new String[]{zero + "=", zero.substring(1), zero + "A", zero.substring(0, 42) + "B",
                    zero.replace('A', '/'), "%41" + zero.substring(1)}) {
                String uri = HTTPS + fields.replace(key + "=" + zero, key + "=" + value);
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> PairingLink.parse(uri));
                assertEquals("Invalid v2 Spatial Wall pairing link", failure.getMessage());
                assertNull(failure.getCause());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> PairingLink.parse(HTTPS + fields(new byte[32])));
        assertThrows(IllegalArgumentException.class, () -> PairingLink.parse(HTTPS + fields(spki("secp384r1"))));
        byte[] der = spki("secp256r1"), trailing = Arrays.copyOf(der, der.length + 1);
        assertThrows(IllegalArgumentException.class, () -> PairingLink.parse(HTTPS + fields(trailing)));
    }
}
