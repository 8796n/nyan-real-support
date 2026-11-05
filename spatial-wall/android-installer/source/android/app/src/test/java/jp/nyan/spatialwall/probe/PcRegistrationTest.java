// SPDX-License-Identifier: MIT
package jp.nyan.spatialwall.probe;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class PcRegistrationTest {
    @Test public void cancellationBeforeStartNeverConnectsAndErasesOnlyOwnedCode() throws Exception {
        byte[] code = "123456".getBytes(StandardCharsets.US_ASCII);
        PcRegistration registration = new PcRegistration("192.168.1.10", 8797, code);
        registration.close(); registration.close();
        assertThrows(IOException.class, () -> registration.perform("Android"));
        assertArrayEquals("123456".getBytes(StandardCharsets.US_ASCII), code);
        var field = PcRegistration.class.getDeclaredField("code"); field.setAccessible(true);
        assertArrayEquals(new byte[6], (byte[]) field.get(registration));
    }

    @Test public void rejectedEndpointDoesNotRetryAndInvalidCodeNeverCreatesAnAttempt() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new PcRegistration("pc.local", 8797, new byte[6]));
        try (PcRegistration registration = new PcRegistration("8.8.8.8", 8797, "123456".getBytes(StandardCharsets.US_ASCII))) {
            assertThrows(IOException.class, () -> registration.perform("Android"));
            assertThrows(IOException.class, () -> registration.perform("Android"));
        }
    }
}
