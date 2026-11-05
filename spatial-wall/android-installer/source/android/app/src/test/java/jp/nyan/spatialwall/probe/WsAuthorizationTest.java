// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import static org.junit.Assert.*;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.Test;

public final class WsAuthorizationTest {
    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
    private static byte[] device() { return ascii("Device " + "a".repeat(32) + "." + "b".repeat(64)); }
    private static byte[] ticket() { return ascii("Ticket " + "c".repeat(64)); }

    @Test public void authenticatedUpgradeErasesAuthorizationBeforeReadingTheResponse() throws Exception {
        for (String path : new String[]{"/ws", "/controller-left-v1", "/controller-right-v1",
                "/video", "/video?hevc=1", "/audio", "/media"}) {
            byte[] auth = path.startsWith("/controller") || path.equals("/ws") ? device() : ticket();
            ByteArrayOutputStream written = new ByteArrayOutputStream();
            InputStream reply = new InputStream() {
                private InputStream response;
                @Override public int read() throws IOException {
                    if (response == null) {
                        assertArrayEquals(new byte[auth.length], auth);
                        String request = written.toString(StandardCharsets.US_ASCII.name());
                        assertTrue(request.startsWith("GET " + path + " HTTP/1.1\r\n"));
                        assertFalse(request.contains("?t="));
                        String key = request.split("Sec-WebSocket-Key: ")[1].split("\r\n")[0];
                        try {
                            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                                    .digest(ascii(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
                            response = new ByteArrayInputStream(ascii("HTTP/1.1 101 Switching Protocols\r\n"
                                    + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n"));
                        } catch (Exception failure) { throw new IOException(failure); }
                    }
                    return response.read();
                }
            };
            try (Socket raw = new Socket()) {
                new WsClient(1024, "").authenticatedHandshake(path, "192.168.1.10", 8797,
                        new DataInputStream(reply), new BufferedOutputStream(written), auth, raw);
                assertFalse(raw.isClosed());
            }
            String request = written.toString(StandardCharsets.US_ASCII.name());
            assertTrue(request.contains("\r\nAuthorization: " + new String(
                    path.startsWith("/controller") || path.equals("/ws") ? device() : ticket(), StandardCharsets.US_ASCII) + "\r\n"));
            assertTrue(request.endsWith("\r\n\r\n"));
        }
    }

    @Test public void wrongRouteCredentialKindAndHeaderInjectionAreRejectedAndErased() throws Exception {
        for (String path : new String[]{"/ws?t=AAAAAAAAAAAA", "/other", "/video?hevc=1&t=old", "/audio"}) {
            byte[] auth = device(); ByteArrayOutputStream written = new ByteArrayOutputStream();
            try (Socket raw = new Socket()) {
                assertThrows(IOException.class, () -> new WsClient(1024, "").authenticatedHandshake(
                        path, "192.168.1.10", 8797, new DataInputStream(InputStream.nullInputStream()),
                        new BufferedOutputStream(written), auth, raw));
            }
            assertEquals(0, written.size()); assertArrayEquals(new byte[auth.length], auth);
        }
        for (byte[] auth : new byte[][]{ticket(), ascii("Device " + "A".repeat(32) + "." + "b".repeat(64)),
                ascii("Device a\r\nInjected: value"), new byte[0]}) {
            ByteArrayOutputStream written = new ByteArrayOutputStream();
            try (Socket raw = new Socket()) {
                assertThrows(IOException.class, () -> new WsClient(1024, "").authenticatedHandshake(
                        "/ws", "192.168.1.10", 8797, new DataInputStream(InputStream.nullInputStream()),
                        new BufferedOutputStream(written), auth, raw));
            }
            assertEquals(0, written.size()); assertArrayEquals(new byte[auth.length], auth);
        }
    }

    @Test public void writeFailureAndAuthRejectionAlwaysEraseHeader() throws Exception {
        byte[] auth = device();
        try (Socket raw = new Socket()) {
            OutputStream broken = new OutputStream() {
                public void write(int value) throws IOException { throw new IOException("Fixture write failure"); }
            };
            assertThrows(IOException.class, () -> new WsClient(1024, "").authenticatedHandshake("/ws", "192.168.1.10", 8797,
                    new DataInputStream(InputStream.nullInputStream()), new BufferedOutputStream(broken), auth, raw));
            assertArrayEquals(new byte[auth.length], auth);
        }
        byte[] rejected = device();
        try (Socket raw = new Socket()) {
            assertThrows(WsClient.AuthRejected.class, () -> new WsClient(1024, "").authenticatedHandshake(
                    "/ws", "192.168.1.10", 8797,
                    new DataInputStream(new ByteArrayInputStream(ascii("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"))),
                    new BufferedOutputStream(new ByteArrayOutputStream()), rejected, raw));
            assertArrayEquals(new byte[rejected.length], rejected);
        }
    }
}
