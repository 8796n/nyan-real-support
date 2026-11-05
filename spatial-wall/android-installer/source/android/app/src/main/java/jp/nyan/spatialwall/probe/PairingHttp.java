// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLSocket;

// Fixed pairing routes, no redirects/proxies/cookies/cache or persistent HTTP connection.
final class PairingHttp implements PairingClient.Transport {
    static final int LIMIT = 8192;
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private final InetSocketAddress endpoint;
    private Socket active;
    private boolean closed;

    PairingHttp(InetSocketAddress endpoint) {
        if (endpoint == null || endpoint.isUnresolved() || endpoint.getPort() < 1)
            throw new IllegalArgumentException("A resolved PC endpoint is required");
        this.endpoint = endpoint;
    }

    @Override public PairingClient.Reply request(String method, String path, byte[] pin, byte[] leaf,
                                                byte[] authorization, byte[] body) throws IOException {
        boolean enrollment = "/enroll-v2".equals(path);
        if (body == null || body.length > LIMIT || !("GET".equals(method) || "POST".equals(method))
                || (!enrollment && !"/pair-v2".equals(path))
                || ("GET".equals(method) && body.length != 0)
                || (enrollment && (!"POST".equals(method) || body.length != 0 || authorization == null
                    || pin == null || pin.length != 32 || leaf == null || leaf.length != 32))
                || (!enrollment && (pin != null || leaf != null || authorization != null)))
            throw new IOException("Invalid pairing HTTP request");
        if (authorization != null) {
            if (authorization.length > 256) throw new IOException("Invalid pairing authorization");
            for (byte value : authorization) if (value < 32 || value > 126)
                throw new IOException("Invalid pairing authorization");
        }
        Socket raw = new Socket(Proxy.NO_PROXY);
        synchronized (this) {
            if (closed || active != null) { closeSocket(raw); throw new IOException("Pairing HTTP is closed or busy"); }
            active = raw;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PinnedTlsConnector.TIMEOUT_MS);
        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> timeout = DEADLINES.schedule(() -> {
            expired.set(true);
            closeSocket(raw);
        }, PinnedTlsConnector.TIMEOUT_MS, TimeUnit.MILLISECONDS);
        Socket channel = raw;
        try {
            if (enrollment) {
                SSLSocket secure = new PinnedTlsConnector(pin).connect(raw, endpoint);
                channel = secure;
                byte[] actualLeaf = MessageDigest.getInstance("SHA-256")
                        .digest(secure.getSession().getPeerCertificates()[0].getEncoded());
                if (!MessageDigest.isEqual(leaf, actualLeaf))
                    throw new IOException("Pairing TLS certificate changed");
            } else {
                raw.connect(endpoint, PinnedTlsConnector.TIMEOUT_MS);
            }
            channel.setSoTimeout(PinnedTlsConnector.TIMEOUT_MS);
            writeRequest(channel.getOutputStream(), method, path, authorization, body);
            PairingClient.Reply reply = readReply(channel.getInputStream());
            synchronized (this) {
                if (closed || expired.get() || System.nanoTime() - deadline >= 0) {
                    reply.close();
                    throw new IOException("Pairing HTTP was cancelled or expired");
                }
            }
            return reply;
        } catch (GeneralSecurityException failure) {
            throw new IOException("Pairing TLS authentication failed", failure);
        } catch (IOException failure) {
            if (expired.get() || System.nanoTime() - deadline >= 0) {
                SocketTimeoutException timedOut = new SocketTimeoutException("Pairing request deadline exceeded");
                timedOut.initCause(failure);
                throw timedOut;
            }
            throw failure;
        } finally {
            timeout.cancel(false);
            closeSocket(raw); // Unblock TLS close as well; no waiting for peer close_notify.
            closeSocket(channel);
            synchronized (this) { if (active == raw) active = null; }
        }
    }

    private void writeRequest(OutputStream output, String method, String path,
                              byte[] authorization, byte[] body) throws IOException {
        String address = endpoint.getAddress().getHostAddress();
        // A resolved numeric address avoids a second DNS lookup and header injection.
        if (address.indexOf(':') >= 0) address = "[" + address + "]";
        output.write((method + " " + path + " HTTP/1.1\r\nHost: " + address + ":" + endpoint.getPort()
                + "\r\nConnection: close\r\nCache-Control: no-store\r\nAccept: application/octet-stream\r\n"
                + "Content-Type: application/octet-stream\r\nContent-Length: " + body.length + "\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        if (authorization != null) {
            output.write("Authorization: ".getBytes(StandardCharsets.US_ASCII));
            output.write(authorization);
            output.write(new byte[]{'\r', '\n'});
        }
        output.write(new byte[]{'\r', '\n'});
        output.write(body);
        output.flush();
    }

    // Bounded framing only, deliberately no general-purpose HTTP client features.
    static PairingClient.Reply readReply(InputStream input) throws IOException {
        RemoteHttpHeaders header = RemoteHttpHeaders.read(input, LIMIT);
        int status = header.status, size = header.length;
        if (status == 200 && !"application/octet-stream".equals(header.contentType))
            throw new IOException("Invalid pairing HTTP content type");
        if (status != 200 && status != 204) throw new IOException("Pairing HTTP request rejected (" + status + ")");
        byte[] body = new byte[size];
        boolean complete = false;
        try {
            for (int offset = 0; offset < size;) {
                int count = input.read(body, offset, size - offset);
                if (count <= 0) throw new EOFException("Incomplete pairing HTTP body");
                offset += count;
            }
            complete = true;
            return new PairingClient.Reply(status, body);
        } finally { if (!complete) PairingClient.wipe(body); }
    }

    @Override public void close() {
        Socket owned;
        synchronized (this) { closed = true; owned = active; }
        closeSocket(owned);
    }
    private static void closeSocket(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
    }
    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread worker = new Thread(task, "pairing-http-deadline");
            worker.setDaemon(true);
            return worker;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
