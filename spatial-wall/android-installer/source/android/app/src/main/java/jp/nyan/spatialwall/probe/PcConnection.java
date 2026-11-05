// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.net.ssl.SSLSocket;

// One caller-owned credential snapshot. Each channel owns a copy and erases it on close.
// The endpoint is a location; only the saved SPKI authenticates the PC.
final class PcConnection implements AutoCloseable {
    final String profileId, host;
    final int port, grants;
    private final byte[] pin, deviceId, secret;
    private final PinnedTlsConnector tls;
    private boolean closed;
    private String connectedHost;

    PcConnection(String host, int port, SpatialCredentials.Entry entry) throws GeneralSecurityException {
        if (entry == null) throw new IllegalArgumentException("Missing saved PC credential");
        if (!WsClient.validHost(host)) throw new IllegalArgumentException("Invalid PC host");
        this.host = host;
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid PC port");
        this.port = port; this.profileId = entry.profileId; this.grants = entry.grants;
        pin = entry.spkiSha256(); deviceId = entry.deviceId();
        tls = new PinnedTlsConnector(pin);
        secret = entry.credential();
    }

    private PcConnection(PcConnection source) throws GeneralSecurityException {
        profileId = source.profileId; host = source.host; port = source.port; grants = source.grants;
        pin = source.pin.clone(); deviceId = source.deviceId.clone();
        tls = new PinnedTlsConnector(pin); // A context never crosses PC identities.
        secret = source.secret.clone();
    }
    synchronized PcConnection copy() throws GeneralSecurityException {
        requireOpen();
        return new PcConnection(this);
    }
    byte[] spkiSha256() { return pin.clone(); }

    // The caller registers raw before this call and closes it to cancel connect/handshake.
    // Connection close erases the credential; it does not own a channel's active socket.
    SSLSocket connect(Socket raw) throws IOException {
        synchronized (this) {
            if (closed) {
                try { raw.close(); } catch (IOException ignored) {}
                throw new IOException("PC credential is closed");
            }
        }
        SSLSocket connected;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(PinnedTlsConnector.TIMEOUT_MS);
        try (SocketDeadline timeout = new SocketDeadline(raw, PinnedTlsConnector.TIMEOUT_MS)) {
            java.net.InetAddress address = PcAddress.resolve(host, raw, deadline);
            timeout.check();
            connected = tls.connect(raw, new InetSocketAddress(address, port));
            timeout.check();
        } catch (IOException | RuntimeException failure) {
            try { raw.close(); } catch (IOException ignored) {}
            throw failure;
        }
        synchronized (this) {
            if (closed) {
                try { raw.close(); } catch (IOException ignored) {}
                try { connected.close(); } catch (IOException ignored) {}
                throw new IOException("PC credential closed during connection");
            }
            connectedHost = connected.getInetAddress().getHostAddress();
        }
        return connected;
    }

    synchronized String wireHost() {
        requireOpen();
        if (connectedHost == null) throw new IllegalStateException("PC is not connected");
        return connectedHost;
    }

    // Caller writes this only to authenticated TLS and erases the returned buffer immediately.
    synchronized byte[] authorization() {
        requireOpen();
        byte[] header = new byte[104];
        byte[] prefix = "Device ".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(prefix, 0, header, 0, prefix.length);
        hex(deviceId, header, 7); header[39] = '.'; hex(secret, header, 40);
        return header;
    }
    @Override public synchronized void close() { closed = true; Arrays.fill(secret, (byte) 0); }
    private void requireOpen() {
        if (closed) throw new IllegalStateException("PC credential is closed");
    }
    private static void hex(byte[] value, byte[] output, int offset) {
        final byte[] digits = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        for (byte current : value) {
            output[offset++] = digits[(current & 255) >>> 4];
            output[offset++] = digits[current & 15];
        }
    }
}
