// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class PairingHttpTest {
    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
    private static PairingClient.Reply parse(String response) throws IOException {
        return PairingHttp.readReply(new ByteArrayInputStream(ascii(response)));
    }
    private static String ok(String fields, String body) {
        return "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" + fields + "\r\n" + body;
    }

    @Test public void canonicalBinaryShortReadsAndNoContentSucceed() throws Exception {
        byte[] wire = ascii(ok("Content-Length: 3\r\n", "abc"));
        InputStream shortReads = new ByteArrayInputStream(wire) {
            @Override public synchronized int read(byte[] into, int offset, int length) {
                return super.read(into, offset, Math.min(length, 1));
            }
        };
        try (PairingClient.Reply reply = PairingHttp.readReply(shortReads)) {
            assertEquals(200, reply.status); assertArrayEquals(ascii("abc"), reply.body);
        }
        try (PairingClient.Reply reply = parse("HTTP/1.1 204 No Content\r\n\r\n")) {
            assertEquals(0, reply.body.length);
        }
    }

    @Test public void ambiguousOrUnsupportedResponsesAreRejectedBeforeBody() throws Exception {
        for (String fields : new String[]{
                "Content-Length: 3\r\nContent-Length: 3\r\n", "Content-Length: 03\r\n",
                "Content-Length: +3\r\n", "Content-Length: -1\r\n", "Content-Length: 8193\r\n",
                "Content-Length: 99999999999999999\r\n", "Transfer-Encoding: chunked\r\n",
                "Content-Length: 3\r\nTransfer-Encoding: identity\r\n",
                "Content-Length: 3\r\nContent-Encoding: gzip\r\n", "Content-Length : 3\r\n",
                "Content-Length: 3\r\nSet-Cookie: a=b\r\n", "Content-Length: 3\nOther: value\r\n",
                "Content-Length: 3\r\n folded: value\r\n", ""}) {
            byte[] header = ascii(ok(fields, ""));
            InputStream onlyHeader = new ByteArrayInputStream(header) {
                @Override public synchronized int read(byte[] target, int offset, int size) {
                    fail("Invalid header advanced to body allocation/read"); return -1;
                }
            };
            assertThrows(IOException.class, () -> PairingHttp.readReply(onlyHeader));
        }
        for (String wire : new String[]{
                "HTTP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n",
                "HTTP/1.1 302 Found\r\nContent-Length: 0\r\nLocation: http://other\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 0\r\n\r\n",
                "HTTP/1.1 204 No Content\r\nContent-Length: 1\r\n\r\nx",
                ok("Content-Length: 3\r\n", "ab")})
            assertThrows(IOException.class, () -> parse(wire));
    }

    @Test public void headerAndBodyCapsAreExact() throws Exception {
        String start = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 0\r\nX: ";
        String header = start + "a".repeat(PairingHttp.LIMIT - start.length() - 4) + "\r\n\r\n";
        try (PairingClient.Reply reply = parse(header)) { assertEquals(0, reply.body.length); }
        assertThrows(IOException.class, () -> parse(header.replace("X: ", "X: aa")));
        try (PairingClient.Reply reply = parse(ok("Content-Length: 8192\r\n", "a".repeat(8192)))) {
            assertEquals(8192, reply.body.length);
        }
    }

    @Test public void plaintextRoutesCannotCarryProofOrInvite() throws Exception {
        try (PairingHttp http = new PairingHttp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 1))) {
            assertThrows(IOException.class, () -> http.request("POST", "/pair-v2", null, null,
                    ascii("Pairing abc"), new byte[0]));
            assertThrows(IOException.class, () -> http.request("POST", "/enroll-v2", null, null,
                    ascii("Invite abc"), new byte[0]));
            assertThrows(IOException.class, () -> http.request("GET", "http://other/", null, null,
                    null, new byte[0]));
        }
    }

    private static ServerSocket listener() throws IOException {
        return new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    }
    private static InetSocketAddress endpoint(ServerSocket listener) {
        return new InetSocketAddress(listener.getInetAddress(), listener.getLocalPort());
    }
    private static <T> FutureTask<T> start(java.util.concurrent.Callable<T> task) {
        FutureTask<T> future = new FutureTask<>(task);
        Thread thread = new Thread(future, "pairing-http-test"); thread.setDaemon(true); thread.start();
        return future;
    }

    @Test public void localhostRequestIsDirectFixedRouteAndConnectionCloses() throws Exception {
        try (ServerSocket listener = listener(); PairingHttp http = new PairingHttp(endpoint(listener))) {
            FutureTask<String> server = start(() -> {
                try (Socket peer = listener.accept()) {
                    peer.setSoTimeout(2000);
                    String request = WsClient.readHttpHeader(peer.getInputStream());
                    peer.getOutputStream().write(ascii(ok("Content-Length: 3\r\n", "abc")));
                    peer.getOutputStream().flush();
                    assertEquals(-1, peer.getInputStream().read());
                    return request;
                }
            });
            try (PairingClient.Reply reply = http.request("GET", "/pair-v2", null, null, null, new byte[0])) {
                assertArrayEquals(ascii("abc"), reply.body);
            }
            String request = server.get(3, TimeUnit.SECONDS);
            assertTrue(request.startsWith("GET /pair-v2 HTTP/1.1\r\n"));
            assertTrue(request.contains("\r\nConnection: close\r\n"));
            assertTrue(request.contains("\r\nCache-Control: no-store\r\n"));
            assertFalse(request.contains("Authorization:")); assertFalse(request.contains("Cookie:"));
        }
    }

    @Test public void cancellationUnblocksPartialHeaderAndTlsHandshake() throws Exception {
        for (boolean tls : new boolean[]{false, true}) {
            try (ServerSocket listener = listener(); PairingClient client = new PairingClient(endpoint(listener))) {
                CountDownLatch accepted = new CountDownLatch(1);
                FutureTask<Void> server = start(() -> {
                    try (Socket peer = listener.accept()) {
                        peer.setSoTimeout(3000);
                        if (!tls) {
                            WsClient.readHttpHeader(peer.getInputStream());
                            peer.getOutputStream().write(ascii("HTTP/1.1 200 "));
                            peer.getOutputStream().flush();
                        } else {
                            assertTrue(peer.getInputStream().read() >= 0);
                        }
                        accepted.countDown();
                        try { while (peer.getInputStream().read() >= 0) {} } catch (IOException ignored) {}
                        return null;
                    }
                });
                FutureTask<IOException> result = start(() -> {
                    try {
                        if (tls) client.enrollInvite(new byte[32], new byte[32], 1, new byte[32], "phone");
                        else client.pairCode(ascii("123456"), "phone");
                        throw new AssertionError("Cancelled pairing returned credentials");
                    } catch (IOException failure) { return failure; }
                });
                assertTrue(accepted.await(3, TimeUnit.SECONDS));
                client.close(); client.close();
                assertNotNull(result.get(2, TimeUnit.SECONDS));
                server.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test public void tricklingResponseCannotExtendAbsoluteDeadline() throws Exception {
        try (ServerSocket listener = listener(); PairingHttp http = new PairingHttp(endpoint(listener))) {
            FutureTask<Void> server = start(() -> {
                try (Socket peer = listener.accept()) {
                    WsClient.readHttpHeader(peer.getInputStream());
                    byte[] reply = ascii(ok("Content-Length: 100\r\n", "a".repeat(100)));
                    peer.getOutputStream().write(reply, 0, reply.length - 100);
                    for (int i = 0; i < 100; ++i) {
                        peer.getOutputStream().write('a'); peer.getOutputStream().flush();
                        Thread.sleep(100);
                    }
                } catch (IOException expected) {}
                return null;
            });
            long start = System.nanoTime();
            assertThrows(SocketTimeoutException.class,
                    () -> http.request("GET", "/pair-v2", null, null, null, new byte[0]));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue("Deadline did not stop trickling reads", elapsed >= 4000 && elapsed < 8000);
            server.get(3, TimeUnit.SECONDS);
        }
    }
}
