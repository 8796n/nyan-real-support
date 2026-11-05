// SPDX-License-Identifier: MIT
package jp.nyan.spatialwall.probe;

import android.os.CancellationSignal;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// OS getaddrinfo is not cancellable. Keep its work bounded even across repeated stop/start.
final class PcAddress {
    private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "pc-address"); thread.setDaemon(true); return thread;
            });
    private PcAddress() {}

    static InetAddress resolve(String host, Socket cancellation, long deadline) throws IOException {
        if (!WsClient.validHost(host)) throw new IOException("Invalid PC address");
        check(cancellation, deadline);
        // Numeric input never goes through a resolver (including rejected public/loopback addresses).
        if (host.matches("[0-9.]+")) {
            try { return InetAddress.getByName(PhoneControllerAddress.parse(host)); }
            catch (IllegalArgumentException failure) { throw new IOException("A LAN IPv4 address is required"); }
        }
        CancellationSignal signal = new CancellationSignal();
        try { return awaitLookup(cancellation, deadline, () -> RdpNetwork.resolveAddresses(host, signal)); }
        finally { signal.cancel(); }
    }

    static InetAddress awaitLookup(Socket cancellation, long deadline,
                                   java.util.concurrent.Callable<InetAddress[]> lookup) throws IOException {
        check(cancellation, deadline);
        Future<InetAddress[]> result;
        try { result = DNS.submit(lookup); }
        catch (RejectedExecutionException failure) { throw new IOException("PC resolver is busy"); }
        try {
            for (;;) {
                check(cancellation, deadline);
                try {
                    InetAddress address = local(result.get(50, TimeUnit.MILLISECONDS));
                    check(cancellation, deadline);
                    return address;
                } catch (TimeoutException ignored) {}
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IOException("PC lookup cancelled");
        } catch (ExecutionException failure) { throw new IOException("PC lookup failed"); }
        finally { result.cancel(false); DNS.purge(); }
    }

    static InetAddress local(InetAddress[] addresses) throws IOException {
        for (InetAddress address : addresses)
            if (address instanceof Inet4Address && !address.isLoopbackAddress() && RdpNetwork.localAddress(address))
                return address;
        throw new IOException("A LAN IPv4 address is required");
    }

    private static void check(Socket cancellation, long deadline) throws IOException {
        if (cancellation.isClosed()) throw new IOException("PC lookup cancelled");
        if (System.nanoTime() - deadline >= 0) throw new SocketTimeoutException("PC connection deadline exceeded");
    }
}
