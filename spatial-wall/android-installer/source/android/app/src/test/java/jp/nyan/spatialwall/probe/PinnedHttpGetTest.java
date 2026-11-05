// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class PinnedHttpGetTest {
    @Test public void downloadRequiresRemoteGrantBeforeCreatingACredentialCopy() throws Exception {
        for (int grants : new int[]{2, 4, 6}) {
            try (var entry = PcConnectionTest.entry(grants); PcConnection pc = new PcConnection("192.168.1.10", 8797, entry)) {
                assertThrows(IllegalArgumentException.class, () -> new PinnedHttpGet(pc));
                byte[] header = pc.authorization(); PairingClient.wipe(header);
            }
        }
    }
    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
    private static byte[] wire(String status, String type, String length, byte[] body) throws IOException {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write(ascii("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + length + "\r\n\r\n"));
        wire.write(body); return wire.toByteArray();
    }
    private static class Connection implements PinnedHttpGet.Connection {
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        InputStream reply;
        byte[] auth;
        boolean verified, closed, rejectPin, bodyRead;
        int connects, authorizations;
        Connection(byte[] response) { reply = new ByteArrayInputStream(response); }
        public Socket connect(Socket raw) throws IOException {
            ++connects;
            if (rejectPin) throw new IOException("Fixture wrong pin");
            verified = true;
            return new Socket() {
                @Override public InputStream getInputStream() { return reply; }
                @Override public OutputStream getOutputStream() { return written; }
            };
        }
        public String host() { return "192.168.1.10"; }
        public int port() { return 8797; }
        public byte[] authorization() {
            assertTrue(verified); ++authorizations;
            return auth = ascii("Device " + "a".repeat(32) + "." + "b".repeat(64));
        }
        public void close() { closed = true; }
    }

    @Test public void previewAuthenticatesBeforeHeaderAndClosesItsOwnedConnection() throws Exception {
        Connection connection = new Connection(wire("200 OK", "application/json", "2", ascii("{}")));
        try (PinnedHttpGet get = new PinnedHttpGet(connection)) {
            assertArrayEquals(ascii("{}"), get.preview());
            assertTrue(connection.closed); assertEquals(1, connection.authorizations);
            assertArrayEquals(new byte[connection.auth.length], connection.auth);
            String request = connection.written.toString(StandardCharsets.US_ASCII.name());
            assertTrue(request.startsWith("GET /preview HTTP/1.1\r\n"));
            assertTrue(request.contains("\r\nAuthorization: Device "));
            assertTrue(request.contains("\r\nCache-Control: no-store\r\n")); assertFalse(request.contains("?t="));
            assertThrows(IOException.class, get::preview); assertEquals(1, connection.connects);
        }
        Connection wrongPin = new Connection(new byte[0]); wrongPin.rejectPin = true;
        try (PinnedHttpGet get = new PinnedHttpGet(wrongPin)) { assertThrows(IOException.class, get::preview); }
        assertEquals(0, wrongPin.authorizations); assertEquals(0, wrongPin.written.size()); assertTrue(wrongPin.closed);
    }

    @Test public void apkStreamsBoundedChunksAndVerifiesTheAuthenticatedOffer() throws Exception {
        byte[] body = new byte[100003]; for (int i = 0; i < body.length; ++i) body[i] = (byte) i;
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(body), originalHash = hash.clone();
        Connection connection = new Connection(wire("200 OK", "application/vnd.android.package-archive", "100003", body));
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int size) {
                assertTrue(size <= 32768); super.write(bytes, offset, size);
            }
            @Override public void close() { fail("The caller owns its staging stream"); }
        };
        try (PinnedHttpGet get = new PinnedHttpGet(connection)) { get.apk(body.length, hash, output); }
        assertArrayEquals(body, output.toByteArray()); assertArrayEquals(originalHash, hash);
        assertTrue(connection.written.toString(StandardCharsets.US_ASCII.name()).startsWith("GET /app.apk HTTP/1.1\r\n"));
        assertArrayEquals(new byte[connection.auth.length], connection.auth);
        hash[0] ^= 1;
        Connection mismatch = new Connection(wire("200 OK", "application/vnd.android.package-archive", "100003", body));
        try (PinnedHttpGet get = new PinnedHttpGet(mismatch)) {
            assertThrows(IOException.class, () -> get.apk(body.length, hash, new ByteArrayOutputStream()));
        }
    }

    @Test public void sizeMismatchOversizeRedirectEncodingAndForbiddenNeverConsumeBody() throws Exception {
        for (String header : new String[]{
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2097153\r\n\r\n",
                "HTTP/1.1 302 Found\r\nContent-Length: 0\r\nLocation: https://other\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1\r\nContent-Encoding: gzip\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 01\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1\r\ncontent-length: 1\r\n\r\n",
                "HTTP/1.1 403 Forbidden\r\nContent-Length: 100\r\n\r\n"}) {
            Connection connection = new Connection(ascii(header));
            connection.reply = new ByteArrayInputStream(ascii(header)) {
                @Override public int read(byte[] into, int offset, int length) {
                    fail("Rejected response advanced to the body"); return -1;
                }
            };
            try (PinnedHttpGet get = new PinnedHttpGet(connection)) {
                IOException failure = assertThrows(IOException.class, get::preview);
                if (header.startsWith("HTTP/1.1 403")) assertTrue(failure instanceof WsClient.AuthRejected);
            }
            assertEquals(1, connection.connects);
        }
        Connection length = new Connection(wire("200 OK", "application/vnd.android.package-archive", "3", new byte[0]));
        try (PinnedHttpGet get = new PinnedHttpGet(length)) {
            assertThrows(IOException.class, () -> get.apk(4, new byte[32], new ByteArrayOutputStream()));
        }
        Connection invalid = new Connection(new byte[0]);
        try (PinnedHttpGet get = new PinnedHttpGet(invalid)) {
            assertThrows(IOException.class, () -> get.apk(PinnedHttpGet.MAX_APK + 1L, new byte[32], new ByteArrayOutputStream()));
        }
        assertEquals(0, invalid.connects);
    }

    @Test public void bodyCopyHandlesShortReadsTruncationAndCancellation() throws Exception {
        byte[] body = ascii("abcdef"), hash = MessageDigest.getInstance("SHA-256").digest(body);
        InputStream shortReads = new ByteArrayInputStream(body) {
            @Override public synchronized int read(byte[] into, int offset, int size) {
                return super.read(into, offset, Math.min(1, size));
            }
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PinnedHttpGet.copyBody(shortReads, output, body.length, hash, () -> {});
        assertArrayEquals(body, output.toByteArray());
        assertThrows(IOException.class, () -> PinnedHttpGet.copyBody(new ByteArrayInputStream(body),
                new ByteArrayOutputStream(), body.length + 1, hash, () -> {}));
        ByteArrayOutputStream cancelled = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> PinnedHttpGet.copyBody(new ByteArrayInputStream(body),
                cancelled, body.length, hash, () -> { throw new IOException("Cancelled fixture"); }));
        assertEquals(0, cancelled.size());
    }

    private static <T> FutureTask<T> start(java.util.concurrent.Callable<T> action) {
        FutureTask<T> future = new FutureTask<>(action); Thread thread = new Thread(future, "pinned-get-test");
        thread.setDaemon(true); thread.start(); return future;
    }
    @Test public void closeUnblocksRealPendingTlsAndPartialResponse() throws Exception {
        for (boolean tls : new boolean[]{true, false}) {
            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                CountDownLatch waiting = new CountDownLatch(1);
                FutureTask<Void> peer = start(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(3000);
                        if (tls) assertTrue(socket.getInputStream().read() >= 0);
                        else {
                            WsClient.readHttpHeader(socket.getInputStream());
                            socket.getOutputStream().write(ascii("HTTP/1.1 200 OK\r\n"));
                            socket.getOutputStream().flush();
                        }
                        waiting.countDown();
                        try { while (socket.getInputStream().read() >= 0) {} } catch (IOException ignored) {}
                    }
                    return null;
                });
                Connection connection = new Connection(new byte[0]) {
                    public Socket connect(Socket raw) throws IOException {
                        InetSocketAddress endpoint = new InetSocketAddress(server.getInetAddress(), server.getLocalPort());
                        if (tls) {
                            try { return new PinnedTlsConnector(new byte[32]).connect(raw, endpoint); }
                            catch (java.security.GeneralSecurityException failure) { throw new IOException(failure); }
                        }
                        raw.connect(endpoint); verified = true; return raw;
                    }
                };
                try (PinnedHttpGet get = new PinnedHttpGet(connection)) {
                    FutureTask<IOException> operation = start(() -> {
                        try { get.preview(); throw new AssertionError("Cancelled download succeeded"); }
                        catch (IOException expected) { return expected; }
                    });
                    assertTrue(waiting.await(3, TimeUnit.SECONDS)); get.close();
                    assertNotNull(operation.get(2, TimeUnit.SECONDS)); peer.get(3, TimeUnit.SECONDS);
                    if (tls) assertEquals(0, connection.authorizations);
                }
            }
        }
    }

    @Test public void absoluteDeadlineStopsTricklingReadsAndCanBeCancelled() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket raw = new Socket(server.getInetAddress(), server.getLocalPort()); Socket peer = server.accept()) {
            try (SocketDeadline expired = new SocketDeadline(raw, 100)) {
                FutureTask<Void> writer = start(() -> {
                    try {
                        for (int i = 0; i < 100; ++i) { peer.getOutputStream().write('x'); Thread.sleep(10); }
                    } catch (IOException expected) {}
                    return null;
                });
                IOException failure = assertThrows(IOException.class, () -> {
                    while (raw.getInputStream().read() >= 0) expired.check();
                    expired.check();
                });
                assertTrue(expired.failure(failure) instanceof SocketTimeoutException);
                // check() may observe the deadline before the timer finishes closing the socket.
                peer.setSoTimeout(2000);
                try { assertEquals(-1, peer.getInputStream().read()); }
                catch (java.net.SocketException closedPeer) { /* Reset/abort also confirms transport closure. */ }
                writer.get(2, TimeUnit.SECONDS);
            }
        }
        try (Socket unused = new Socket()) {
            SocketDeadline cancelled = new SocketDeadline(unused, 25); cancelled.close();
            Thread.sleep(60); assertFalse(unused.isClosed());
        }
    }
}
