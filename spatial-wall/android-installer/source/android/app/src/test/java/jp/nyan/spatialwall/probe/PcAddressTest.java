// SPDX-License-Identifier: MIT
package jp.nyan.spatialwall.probe;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class PcAddressTest {
    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(5); }

    @Test public void addressSelectionNeverPermitsPublicLoopbackOrIpv6() throws Exception {
        InetAddress lan = InetAddress.getByName("192.168.1.8");
        InetAddress publicIp = InetAddress.getByName("8.8.8.8"), loopback = InetAddress.getByName("127.0.0.1");
        assertEquals(lan, PcAddress.local(new InetAddress[]{publicIp, loopback, lan}));
        assertThrows(IOException.class, () -> PcAddress.local(new InetAddress[]{publicIp, loopback, InetAddress.getByName("fd00::1")}));
        try (Socket cancelled = new Socket()) {
            assertThrows(SocketTimeoutException.class, () -> PcAddress.resolve("192.168.1.8", cancelled, System.nanoTime() - 1));
            assertFalse(cancelled.isConnected());
            cancelled.close();
            assertThrows(IOException.class, () -> PcAddress.resolve("192.168.1.8", cancelled, deadline()));
        }
    }

    @Test public void uncancellableLookupHasOneWorkerOneQueueAndCloseReleasesCaller() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Socket first = new Socket(); Socket second = new Socket(); Socket third = new Socket()) {
            FutureTask<Boolean> blocked = new FutureTask<>(() -> {
                try {
                    PcAddress.awaitLookup(first, deadline(), () -> {
                        entered.countDown(); release.await();
                        return new InetAddress[]{InetAddress.getByName("192.168.1.8")};
                    });
                    return false;
                } catch (IOException expected) { return true; }
            });
            Thread worker = new Thread(blocked); worker.setDaemon(true); worker.start();
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            first.close();
            assertTrue(blocked.get(1, TimeUnit.SECONDS)); // The OS lookup is deliberately still blocked.
            CountDownLatch queued = new CountDownLatch(1);
            FutureTask<Boolean> waiting = new FutureTask<>(() -> {
                queued.countDown();
                try { PcAddress.awaitLookup(second, deadline(), () -> { fail("Cancelled queued lookup ran"); return null; }); return false; }
                catch (IOException expected) { return true; }
            });
            Thread waiter = new Thread(waiting); waiter.setDaemon(true); waiter.start();
            assertTrue(queued.await(1, TimeUnit.SECONDS));
            // Poll the bounded executor state rather than assuming scheduler timing.
            var field = PcAddress.class.getDeclaredField("DNS"); field.setAccessible(true);
            var dns = (java.util.concurrent.ThreadPoolExecutor) field.get(null);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (dns.getQueue().size() != 1 && System.nanoTime() < end) Thread.yield();
            assertEquals(1, dns.getQueue().size());
            assertThrows(IOException.class, () -> PcAddress.awaitLookup(third, deadline(), () -> { fail("Excess lookup ran"); return null; }));
            second.close(); assertTrue(waiting.get(1, TimeUnit.SECONDS));
            assertTrue(dns.getQueue().isEmpty());
        } finally { release.countDown(); }
    }
}
