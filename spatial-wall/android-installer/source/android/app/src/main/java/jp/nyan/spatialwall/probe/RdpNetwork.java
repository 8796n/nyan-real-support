// SPDX-License-Identifier: MIT
package jp.nyan.spatialwall.probe;

import android.net.DnsResolver;
import android.os.CancellationSignal;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

final class RdpNetwork {
    private RdpNetwork() {}
    static boolean localAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
    static final class NonLocalAddressException extends Exception {}

    static String directAddress(String host, CancellationSignal cancellation)
            throws UnknownHostException, NonLocalAddressException {
        return directAddress(resolveAddresses(host, cancellation));
    }

    static InetAddress[] resolveAddresses(String host, CancellationSignal cancellation) throws UnknownHostException {
        InetAddress[] addresses;
        try { addresses = InetAddress.getAllByName(host); }
        catch (UnknownHostException e) {
            String name = host.toLowerCase(Locale.ROOT);
            if (!name.endsWith(".local") && !name.endsWith(".local.")) throw e;
            // Android getaddrinfo sends .local to mDNS; AD zones may instead live in normal DNS.
            addresses = unicastAddresses(host, cancellation);
        }
        return addresses;
    }

    static String directAddress(InetAddress[] addresses)
            throws UnknownHostException, NonLocalAddressException {
        if (addresses.length == 0) throw new UnknownHostException();
        for (InetAddress address : addresses)
            if (localAddress(address)) return address.getHostAddress();
        throw new NonLocalAddressException();
    }

    private static InetAddress[] unicastAddresses(String host, CancellationSignal cancellation)
            throws UnknownHostException {
        CompletableFuture<List<InetAddress>> answer = new CompletableFuture<>();
        try {
            DnsResolver.getInstance().query(null, host, DnsResolver.FLAG_NO_CACHE_LOOKUP, Runnable::run,
                    cancellation, new DnsResolver.Callback<List<InetAddress>>() {
                        @Override public void onAnswer(List<InetAddress> addresses, int rcode) {
                            if (rcode == 0 && !addresses.isEmpty()) answer.complete(addresses);
                            else answer.completeExceptionally(new UnknownHostException());
                        }
                        @Override public void onError(DnsResolver.DnsException error) {
                            answer.completeExceptionally(error);
                        }
                    });
            return answer.get(8, TimeUnit.SECONDS).toArray(new InetAddress[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnknownHostException();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            UnknownHostException failure = new UnknownHostException();
            failure.initCause(e);
            throw failure;
        } finally { cancellation.cancel(); }
    }
}
