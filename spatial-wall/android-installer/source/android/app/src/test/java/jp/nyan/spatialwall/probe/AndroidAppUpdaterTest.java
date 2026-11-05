// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import java.io.IOException;

public final class AndroidAppUpdaterTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void identityRequiresNewerExactPackageVersionAndSignerSet() {
        AndroidAppUpdater.Offer offer =
                new AndroidAppUpdater.Offer(9001, "0.9.1", 4_000_000, HASH);
        byte[][] installed = { {1, 2, 3}, {4, 5, 6} };
        byte[][] sameReordered = { {4, 5, 6}, {1, 2, 3} };

        assertTrue(AndroidAppUpdater.identityMatches(offer,
                "jp.nyanreal.spatialwall.remote", 9000,
                "jp.nyanreal.spatialwall.remote", 9001, "0.9.1",
                installed, sameReordered));
        assertFalse(AndroidAppUpdater.identityMatches(offer,
                "jp.nyanreal.spatialwall.remote", 9000,
                "jp.nyanreal.spatialwall.remote", 9001, "0.9.1",
                installed, new byte[][] { {1, 2, 3}, {7} }));
        assertFalse(AndroidAppUpdater.identityMatches(offer,
                "jp.nyanreal.spatialwall.remote", 9000,
                "jp.nyanreal.spatialwall.remote.fake", 9001, "0.9.1",
                installed, sameReordered));
        assertFalse(AndroidAppUpdater.identityMatches(offer,
                "jp.nyanreal.spatialwall.remote", 9000,
                "jp.nyanreal.spatialwall.remote", 9002, "0.9.1",
                installed, sameReordered));
        assertFalse(AndroidAppUpdater.identityMatches(offer,
                "jp.nyanreal.spatialwall.remote", 9002,
                "jp.nyanreal.spatialwall.remote", 9001, "0.9.1",
                installed, sameReordered));
    }

    @Test
    public void sameVersionRequiresDifferentApkAndNeverDowngrades() {
        AndroidAppUpdater.Offer offer = new AndroidAppUpdater.Offer(9000, "0.9.0", 100, HASH);
        assertTrue(offer.isUpdateFor(8999, HASH));
        assertTrue(offer.isUpdateFor(9000, "f".repeat(64)));
        assertFalse(offer.isUpdateFor(9000, HASH));
        assertFalse(offer.isUpdateFor(9001, "f".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new AndroidAppUpdater.Offer(9000, "0.9.0", 100, ""));
        assertFalse(offer.sameAs(new AndroidAppUpdater.Offer(9000, "0.9.0", 100, "f".repeat(64))));
        assertFalse(AndroidAppUpdater.validSha256(""));
        assertFalse(AndroidAppUpdater.validSha256("g".repeat(64)));
        assertFalse(AndroidAppUpdater.validSha256(HASH + "0"));
        byte[][] signer = {{1, 2, 3}};
        assertTrue(AndroidAppUpdater.identityMatches(offer, "app", 9000,
                "app", 9000, "0.9.0", signer, signer));
        assertFalse(AndroidAppUpdater.identityMatches(offer, "app", 9001,
                "app", 9000, "0.9.0", signer, signer));
        assertFalse(AndroidAppUpdater.identityMatches(offer, "app", 9000,
                "app", 9000, "0.9.0", signer, new byte[][] {{4, 5, 6}}));
    }

    @Test public void offerFieldsRequireExactSchemaPackageTypesBoundsAndHash() throws Exception {
        Object[] valid = {1, BuildConfig.APPLICATION_ID, 9001L, "0.9.1", 1234L, HASH};
        AndroidAppUpdater.Offer offer = fields(valid);
        assertEquals(9001, offer.versionCode); assertEquals(1234, offer.size); assertEquals(HASH, offer.sha256);
        Object[][] invalid = {
                {0, 2}, {0, "1"}, {1, "different.app"}, {2, "9001"}, {2, 1.5}, {2, 0},
                {2, Integer.MAX_VALUE + 1L}, {3, ""}, {3, "x".repeat(65)}, {3, "bad\nname"},
                {4, "1234"}, {4, 0}, {4, AndroidAppUpdater.MAX_APK_BYTES + 1},
                {5, null}, {5, ""}, {5, HASH.toUpperCase(java.util.Locale.ROOT)}
        };
        for (Object[] change : invalid) {
            Object[] values = valid.clone(); values[(Integer) change[0]] = change[1];
            IOException error = assertThrows(IOException.class, () -> fields(values));
            assertEquals("Invalid Android APK offer", error.getMessage());
        }
    }

    private static AndroidAppUpdater.Offer fields(Object[] values) throws IOException {
        return AndroidAppUpdater.parseOfferFields(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    @Test
    public void apkFingerprintUsesSha256() throws Exception {
        File file = File.createTempFile("android-apk", ".apk");
        try {
            Files.write(file.toPath(), new byte[] {'a', 'b', 'c'});
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    AndroidAppUpdater.sha256(file));
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test
    public void payloadSizeIsBounded() {
        assertFalse(AndroidAppUpdater.isSupportedSize(0));
        assertTrue(AndroidAppUpdater.isSupportedSize(
                AndroidAppUpdater.MAX_APK_BYTES));
        assertFalse(AndroidAppUpdater.isSupportedSize(
                AndroidAppUpdater.MAX_APK_BYTES + 1));
    }
}
