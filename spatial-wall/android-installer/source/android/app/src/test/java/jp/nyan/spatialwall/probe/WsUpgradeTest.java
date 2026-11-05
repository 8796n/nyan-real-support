// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class WsUpgradeTest {
    private static final String ACCEPT = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
    private static final String RESPONSE = "HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: " + ACCEPT + "\r\n\r\n";

    private static void check(String response) throws IOException {
        WsClient.validateUpgrade(response, ACCEPT, "");
    }

    @Test public void acceptsRfcHandshakeAndCaseInsensitiveConnectionTokens() throws Exception {
        check(RESPONSE);
        check(RESPONSE.replace("Upgrade: websocket", "uPgRaDe:\tWebSocket \t")
                .replace("Connection: Upgrade", "Connection: keep-alive\r\nconnection: UpGrAdE")
                .replace("\r\n\r\n", "\r\nX-Optional: ignored\r\nContent-Length: 0\r\n\r\n"));
        check(RESPONSE.replace("Switching Protocols", ""));
    }

    @Test public void rejectsInvalidNegotiationEvenWithCorrectAccept() {
        String[] invalid = {
            RESPONSE.replace("HTTP/1.1", "HTTP/1.0"),
            RESPONSE.replace("101 Switching Protocols", "200 text 101 ok"),
            RESPONSE.replace("Upgrade: websocket\r\n", ""),
            RESPONSE.replace("websocket", "h2c"),
            RESPONSE.replace("Connection: Upgrade\r\n", ""),
            RESPONSE.replace("Connection: Upgrade", "Connection: keep-alive"),
            RESPONSE.replace("Connection: Upgrade", "Connection: Upgrade,"),
            RESPONSE.replace("Sec-WebSocket-Accept: " + ACCEPT + "\r\n", ""),
            RESPONSE.replace(ACCEPT, "wrong"),
            RESPONSE.replace("\r\n\r\n", "\r\nSec-WebSocket-Accept: " + ACCEPT + "\r\n\r\n"),
            RESPONSE.replace("\r\n\r\n", "\r\nUpgrade: websocket\r\n\r\n"),
            RESPONSE.replace("\r\n\r\n", "\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\n"),
            RESPONSE.replace("\r\n\r\n", "\r\nSec-WebSocket-Protocol: remote\r\n\r\n"),
            RESPONSE.replace("\r\n\r\n", "\r\nContent-Length: 1\r\n\r\n"),
            RESPONSE.replace("\r\n\r\n", "\r\nContent-Length: 0\r\nContent-Length: 0\r\n\r\n"),
            RESPONSE.replace("\r\n\r\n", "\r\nTransfer-Encoding: chunked\r\n\r\n"),
        };
        for (String response : invalid) assertThrows(IOException.class, () -> check(response));
    }

    @Test public void rejectsAmbiguousHeaderSyntaxAndTruncation() {
        for (String response : new String[]{
                RESPONSE.replace("Upgrade:", " Upgrade:"),
                RESPONSE.replace("Upgrade:", "Upgrade :"),
                RESPONSE.replace("\r\n\r\n", "\r\n folded\r\n\r\n"),
                RESPONSE.replace("\r\n\r\n", "\r\nX: a\u0000b\r\n\r\n"),
                RESPONSE.replace("\r\n\r\n", "\r\nX: \u00ff\r\n\r\n"),
                RESPONSE.replace("\r\n", "\n"), RESPONSE.stripTrailing(),
                RESPONSE.replace("\r\n\r\n", "\r\n\r\nExtra: header\r\n\r\n"),
        }) assertThrows(IOException.class, () -> check(response));
    }

    @Test public void onlyExactForbiddenStatusMeansCredentialsWereRejected() throws Exception {
        assertThrows(WsClient.AuthRejected.class, () -> check("HTTP/1.1 403 Forbidden\r\n\r\n"));
        assertThrows(WsClient.AuthRejected.class, () -> check("HTTP/1.1 403 Forbidden\r\nContent-Length: 9\r\n\r\n"));
        IOException ordinary = assertThrows(IOException.class,
                () -> check(RESPONSE.replace("101 Switching Protocols", "503 text 403 unavailable")));
        assertFalse(ordinary instanceof WsClient.AuthRejected);
        check(RESPONSE.replace("Switching Protocols", "text 403 accepted"));
    }

    @Test public void headerReadLeavesFirstFrameUnreadAndBoundsUnterminatedInput() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream((RESPONSE + "frame").getBytes(StandardCharsets.US_ASCII));
        check(WsClient.readHttpHeader(input));
        assertEquals('f', input.read());
        assertThrows(IOException.class, () -> WsClient.readHttpHeader(new ByteArrayInputStream(new byte[16385])));
        assertThrows(IOException.class, () -> WsClient.readHttpHeader(new ByteArrayInputStream(new byte[1])));
    }
}
