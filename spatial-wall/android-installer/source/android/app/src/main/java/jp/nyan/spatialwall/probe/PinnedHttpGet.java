// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

// One request, two fixed Device-authenticated routes. The bootstrap APK enrolls before using this too.
final class PinnedHttpGet implements AutoCloseable {
    static final int MAX_PREVIEW = 2 * 1024 * 1024;
    static final int MAX_APK = (int) AndroidAppUpdater.MAX_APK_BYTES;
    private static final int APK_DEADLINE_MS = 120000;
    private static final int BUFFER_SIZE = 32 * 1024;
    interface Connection extends AutoCloseable {
        Socket connect(Socket raw) throws IOException;
        String host();
        int port();
        byte[] authorization();
        @Override void close();
    }
    private final Connection connection;
    private Socket active;
    private boolean used, closed;

    PinnedHttpGet(PcConnection source) throws GeneralSecurityException {
        if (source == null || (source.grants & 1) == 0)
            throw new IllegalArgumentException("Remote permission is required for PC downloads");
        PcConnection owned = source.copy();
        connection = new Connection() {
            public Socket connect(Socket raw) throws IOException { return owned.connect(raw); }
            public String host() { return owned.wireHost(); }
            public int port() { return owned.port; }
            public byte[] authorization() { return owned.authorization(); }
            public void close() { owned.close(); }
        };
    }
    // Isolated transport stage for tests; production only uses the saved-PC constructor above.
    PinnedHttpGet(Connection connection) {
        if (connection == null) throw new IllegalArgumentException("Missing PC connection");
        this.connection = connection;
    }

    byte[] preview() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transfer("/preview", "application/json", MAX_PREVIEW, -1, null, output, PinnedTlsConnector.TIMEOUT_MS);
        return output.toByteArray();
    }

    // The caller owns the private staging file. Delete partial output on any failure; verify APK
    // package, signer and version after success before handing it to PackageInstaller.
    void apk(long expectedSize, byte[] expectedSha256, OutputStream staging) throws IOException {
        byte[] hash = null;
        try {
            if (expectedSize < 1 || expectedSize > MAX_APK || expectedSha256 == null
                    || expectedSha256.length != 32 || staging == null)
                throw new IOException("Invalid APK offer");
            hash = expectedSha256.clone();
            transfer("/app.apk", "application/vnd.android.package-archive", MAX_APK, (int) expectedSize,
                    hash, staging, APK_DEADLINE_MS);
        } finally { PairingClient.wipe(hash); close(); }
    }

    private void transfer(String path, String contentType, int limit, int expectedSize, byte[] hash,
                          OutputStream output, int timeoutMs) throws IOException {
        Socket raw = new Socket(Proxy.NO_PROXY), channel = raw;
        synchronized (this) {
            if (closed || used) { closeSocket(raw); throw new IOException("PC request is closed or already used"); }
            used = true; active = raw;
        }
        byte[] authorization = null;
        try (SocketDeadline deadline = new SocketDeadline(raw, timeoutMs)) {
            try {
                channel = connection.connect(raw); // No HTTP byte or credential is sent until pin verification succeeds.
                checkOpen(); deadline.check();
                channel.setSoTimeout(Math.min(timeoutMs, 15000));
                authorization = connection.authorization();
                try { writeRequest(channel.getOutputStream(), path, contentType, authorization); }
                finally { PairingClient.wipe(authorization); authorization = null; }
                InputStream input = channel.getInputStream();
                RemoteHttpHeaders headers = RemoteHttpHeaders.read(input, limit);
                if (headers.status == 403) throw new WsClient.AuthRejected("PC rejected the device credential");
                if (headers.status != 200 || !contentType.equals(headers.contentType)
                        || (expectedSize >= 0 && headers.length != expectedSize))
                    throw new IOException("PC download response rejected");
                copyBody(input, output, headers.length, hash, () -> { checkOpen(); deadline.check(); });
                checkOpen(); deadline.check();
            } catch (GeneralSecurityException failure) {
                throw new IOException("PC download verification failed", failure);
            } catch (IllegalStateException | IllegalArgumentException failure) {
                throw new IOException("PC credential is no longer available", failure);
            } catch (IOException failure) { throw deadline.failure(failure); }
        } finally {
            PairingClient.wipe(authorization);
            closeSocket(raw); closeSocket(channel);
            synchronized (this) { if (active == raw) active = null; }
            close();
        }
    }

    private void writeRequest(OutputStream output, String path, String type, byte[] authorization) throws IOException {
        String host = connection.host(); int port = connection.port();
        if (!PhoneControllerAddress.parse(host).equals(host) || port < 1 || port > 65535
                || !WsClient.validAuthorization(authorization, false))
            throw new IOException("Invalid authenticated PC request");
        output.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + ":" + port
                + "\r\nConnection: close\r\nCache-Control: no-store\r\nAccept: " + type
                + "\r\nContent-Length: 0\r\nAuthorization: ").getBytes(StandardCharsets.US_ASCII));
        output.write(authorization);
        output.write(new byte[]{'\r', '\n', '\r', '\n'});
        output.flush();
    }

    interface Check { void run() throws IOException; }
    static void copyBody(InputStream input, OutputStream output, int size, byte[] hash, Check check)
            throws IOException, GeneralSecurityException {
        if (size < 0 || size > MAX_APK || (hash != null && hash.length != 32))
            throw new IOException("Invalid bounded PC response");
        MessageDigest digest = hash == null ? null : MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[Math.min(size, BUFFER_SIZE)];
        try {
            for (int remaining = size; remaining > 0;) {
                check.run();
                int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (count <= 0) throw new EOFException("Truncated PC response");
                check.run();
                if (digest != null) digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
                remaining -= count;
            }
            check.run();
            if (digest != null && !MessageDigest.isEqual(hash, digest.digest()))
                throw new IOException("APK does not match the authenticated offer");
        } finally { PairingClient.wipe(buffer); }
    }
    private synchronized void checkOpen() throws IOException {
        if (closed) throw new IOException("PC download was cancelled");
    }
    @Override public void close() {
        Socket raw;
        synchronized (this) { closed = true; raw = active; }
        closeSocket(raw);
        connection.close();
    }
    private static void closeSocket(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
    }
}
