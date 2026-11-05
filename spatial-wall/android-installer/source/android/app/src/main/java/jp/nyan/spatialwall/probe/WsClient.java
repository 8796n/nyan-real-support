// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

// Dependency-free RFC 6455 client mechanics shared by the bridge (/ws) and
// video (/video) channels: upgrade handshake with accept verification,
// masked frame encode, frame decode with a size cap, HTTP header read and
// the common validation helpers. Session policy (retry loops, queues, what
// each opcode means) stays in each client.
final class WsClient {
    // All PC text channels carry JSON; bound recursion before any consumer parses it.
    static final int MAX_TEXT_NESTING = 64;
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    static final class Frame {
        final int opcode;
        final byte[] payload;

        Frame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final int maxInboundFrame;
    private final String label; // exception-message prefix ("" or "映像" etc.)

    WsClient(int maxInboundFrame, String label) {
        this.maxInboundFrame = maxInboundFrame;
        this.label = label;
    }

    // Only on a socket already authenticated by PinnedTlsConnector. Takes and erases authorization.
    void authenticatedHandshake(String path, String host, int port, DataInputStream input,
                                BufferedOutputStream output, byte[] authorization, Socket raw) throws Exception {
        try {
            boolean device = "/ws".equals(path) || "/controller-left-v1".equals(path)
                    || "/controller-right-v1".equals(path);
            boolean ticket = "/video".equals(path) || "/video?hevc=1".equals(path)
                    || "/audio".equals(path) || "/media".equals(path);
            if ((!device && !ticket) || !validAuthorization(authorization, ticket)
                    || !PhoneControllerAddress.parse(host).equals(host) || port < 1 || port > 65535)
                throw new IOException("Invalid authenticated WebSocket request");
            try (SocketDeadline deadline = new SocketDeadline(raw, PinnedTlsConnector.TIMEOUT_MS)) {
                try {
                    handshakeRequest(path, host, port, input, output, authorization);
                    deadline.check();
                } catch (IOException failure) { throw deadline.failure(failure); }
            }
        } finally { PairingClient.wipe(authorization); }
    }

    static boolean validAuthorization(byte[] value, boolean ticket) {
        byte[] prefix = (ticket ? "Ticket " : "Device ").getBytes(StandardCharsets.US_ASCII);
        if (value == null || value.length != (ticket ? 71 : 104)) return false;
        for (int i = 0; i < prefix.length; ++i) if (value[i] != prefix[i]) return false;
        for (int i = prefix.length; i < value.length; ++i) {
            if (!ticket && i == 39) { if (value[i] != '.') return false; }
            else if (!((value[i] >= '0' && value[i] <= '9') || (value[i] >= 'a' && value[i] <= 'f'))) return false;
        }
        return true;
    }

    private void handshakeRequest(String path, String host, int port, DataInputStream input,
                                  BufferedOutputStream output, byte[] authorization) throws Exception {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ':' + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n";
        try {
            output.write(request.getBytes(StandardCharsets.US_ASCII));
            output.write("Authorization: ".getBytes(StandardCharsets.US_ASCII));
            output.write(authorization);
            output.write(new byte[]{'\r', '\n'});
            output.write(new byte[]{'\r', '\n'});
            output.flush();
        } finally { PairingClient.wipe(authorization); }

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        String expected = Base64.getEncoder().encodeToString(
                sha1.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
        validateUpgrade(readHttpHeader(input), expected, label);
    }

    // No extensions or subprotocols were offered, so the peer cannot enable either.
    static void validateUpgrade(String header, String expected, String label) throws IOException {
        String[] lines = header.split("\\r\\n", -1);
        if (lines.length < 3 || !lines[lines.length - 1].isEmpty()
                || !lines[lines.length - 2].isEmpty()
                || !lines[0].matches("HTTP/1\\.1 [0-9]{3} [\\t\\x20-\\x7e]*"))
            throw new IOException(label + "不正なHTTP応答です");
        String accept = null, upgrade = null;
        String status = lines[0].substring(9, 12);
        boolean connectionUpgrade = false, contentLength = false;
        for (int i = 1; i < lines.length - 2; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon < 1 || !httpToken(line.substring(0, colon)))
                throw new IOException(label + "不正なHTTPヘッダーです");
            String value = line.substring(colon + 1);
            for (int j = 0; j < value.length(); j++) {
                char c = value.charAt(j);
                if ((c < 32 && c != '\t') || c > 126)
                    throw new IOException(label + "不正なHTTPヘッダー値です");
            }
            value = value.trim();
            if (!status.equals("101")) continue;
            switch (line.substring(0, colon).toLowerCase(Locale.ROOT)) {
                case "sec-websocket-accept":
                    if (accept != null) throw new IOException(label + "WebSocket応答が重複しています");
                    accept = value;
                    break;
                case "upgrade":
                    if (upgrade != null) throw new IOException(label + "Upgradeヘッダーが重複しています");
                    upgrade = value;
                    break;
                case "connection":
                    for (String part : value.split(",", -1)) {
                        String item = part.trim();
                        if (!httpToken(item)) throw new IOException(label + "不正なConnectionヘッダーです");
                        connectionUpgrade |= item.equalsIgnoreCase("upgrade");
                    }
                    break;
                case "content-length":
                    if (contentLength || !value.equals("0"))
                        throw new IOException(label + "WebSocket応答に本文は使えません");
                    contentLength = true;
                    break;
                case "transfer-encoding":
                case "sec-websocket-extensions":
                case "sec-websocket-protocol":
                    throw new IOException(label + "未要求のWebSocket応答形式です");
                default:
                    break;
            }
        }
        if (status.equals("403"))
            throw new AuthRejected(label + "PCにペアリングを拒否されました");
        if (!status.equals("101") || !"websocket".equalsIgnoreCase(upgrade)
                || !connectionUpgrade || accept == null)
            throw new IOException(label + "WebSocket upgradeが拒否されました");
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                accept.getBytes(StandardCharsets.US_ASCII)))
            throw new IOException(label + "WebSocket応答の検証に失敗しました");
    }

    private static boolean httpToken(String value) {
        return value.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    }

    // Client frames are always masked. The 16-bit length encoding bounds the
    // outbound size; nothing this app sends comes near it.
    void sendFrame(BufferedOutputStream output, int opcode, byte[] payload)
            throws IOException {
        if (payload.length > 65535)
            throw new IOException(label + "送信フレームが大きすぎます");
        output.write(0x80 | (opcode & 0x0F));
        if (payload.length <= 125) {
            output.write(0x80 | payload.length);
        } else {
            output.write(0x80 | 126);
            output.write((payload.length >>> 8) & 0xFF);
            output.write(payload.length & 0xFF);
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        output.write(mask);
        for (int i = 0; i < payload.length; ++i)
            output.write(payload[i] ^ mask[i & 3]);
        output.flush();
    }

    Frame readFrame(DataInputStream input) throws IOException {
        int first = input.readUnsignedByte();
        int second = input.readUnsignedByte();
        int opcode = first & 0x0F;
        if ((first & 0x70) != 0)
            throw new IOException(label + "不正な予約ビットです");
        if ((first & 0x80) == 0 || opcode == 0)
            throw new IOException(label + "分割フレームは未対応です");
        if (opcode != 1 && opcode != 2 && opcode != 8 && opcode != 9 && opcode != 10)
            throw new IOException(label + "不正なフレーム種別です");
        if ((second & 0x80) != 0)
            throw new IOException(label + "サーバーフレームのマスクは禁止されています");
        int encodedLength = second & 0x7F;
        if (opcode >= 8 && encodedLength > 125)
            throw new IOException(label + "制御フレームが大きすぎます");
        if (opcode == 8 && encodedLength == 1)
            throw new IOException(label + "不正なcloseフレーム長です");
        long length = encodedLength;
        if (encodedLength == 126) length = input.readUnsignedShort();
        else if (encodedLength == 127) {
            length = input.readLong();
            if (length < 0)
                throw new IOException(label + "不正なフレーム長です");
        }
        if ((encodedLength == 126 && length < 126)
                || (encodedLength == 127 && length < 65536))
            throw new IOException(label + "フレーム長が最短形式ではありません");
        if (length > maxInboundFrame)
            throw new IOException(label + "受信フレームが大きすぎます");
        byte[] payload = new byte[(int) length];
        input.readFully(payload);
        if (opcode == 1) {
            if (!withinNestingLimit(decodeUtf8(payload, 0)))
                throw new IOException(label + "JSON nesting exceeds " + MAX_TEXT_NESTING);
        } else if (opcode == 8 && payload.length >= 2) {
            int code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
            // Keep the accepted wire codes aligned with ws_server.cpp.
            if (!((code >= 1000 && code <= 1014 && code != 1004 && code != 1005 && code != 1006)
                    || (code >= 3000 && code <= 4999)))
                throw new IOException(label + "不正なcloseコードです");
            decodeUtf8(payload, 2);
        }
        return new Frame(opcode, payload);
    }

    private String decodeUtf8(byte[] payload, int offset) throws IOException {
        try {
            // A decoder reports malformed input instead of replacing it like new String().
            return StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(payload, offset, payload.length - offset)).toString();
        } catch (CharacterCodingException ex) {
            throw new IOException(label + "不正なUTF-8です", ex);
        }
    }

    // The reader never writes: even Ping is delivered to the session's sole writer.
    static final class FrameReader implements AutoCloseable {
        private final WsClient ws;
        private final DataInputStream input;
        private final Closeable connection;
        private final int maxFrames;
        private final long maxBytes;
        private final Predicate<Frame> terminal;
        private final ArrayDeque<Frame> frames = new ArrayDeque<>();
        private final CountDownLatch finished = new CountDownLatch(1);
        private final Thread worker;
        private long queuedBytes;
        private boolean closed;
        private boolean draining;
        private volatile Frame terminalFrame;
        private volatile IOException failure;

        FrameReader(WsClient ws, DataInputStream input, Closeable connection,
                    int maxFrames, long maxBytes, Predicate<Frame> terminal) {
            if (maxFrames < 1 || maxBytes < ws.maxInboundFrame)
                throw new IllegalArgumentException("Receive queue cannot hold one frame");
            this.ws = ws;
            this.input = input;
            this.connection = connection;
            this.maxFrames = maxFrames;
            this.maxBytes = maxBytes;
            this.terminal = terminal;
            worker = new Thread(this::read, "ws-frame-reader");
            worker.setDaemon(true);
            worker.start();
        }

        synchronized Frame poll() {
            Frame frame = frames.pollFirst();
            if (frame != null) {
                queuedBytes -= frame.payload.length;
                notifyAll();
            }
            return frame;
        }

        Frame terminalFrame() { return terminalFrame; }
        IOException failure() { return failure; }
        boolean isDone() { return finished.getCount() == 0; }

        // A write failure must not hide a handover notice already arriving from the PC.
        boolean drainAndAwait(long timeoutMs) {
            synchronized (this) {
                draining = true;
                frames.clear();
                queuedBytes = 0;
                notifyAll();
            }
            try {
                return finished.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void read() {
            try {
                for (;;) {
                    Frame frame = ws.readFrame(input);
                    synchronized (this) {
                        if (closed) break;
                        if (terminal.test(frame)) {
                            terminalFrame = frame;
                            break;
                        }
                        while (!closed && !draining && (frames.size() >= maxFrames
                                || queuedBytes + frame.payload.length > maxBytes))
                            wait();
                        if (closed) break;
                        if (!draining) {
                            frames.addLast(frame);
                            queuedBytes += frame.payload.length;
                        }
                    }
                    if (frame.opcode == 8) break;
                }
            } catch (IOException ex) {
                failure = ex;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                closeConnection(); // Also releases a writer blocked in socket output.
                finished.countDown();
            }
        }

        private void closeConnection() {
            try { connection.close(); } catch (IOException ignored) {}
        }

        @Override public void close() {
            synchronized (this) {
                closed = true;
                frames.clear();
                queuedBytes = 0;
                notifyAll();
            }
            closeConnection();
            worker.interrupt();
        }
    }

    // Few opening brackets cannot exhaust the parser stack. For wider text,
    // count depth using JSON string boundaries and reject lenient tokens that
    // org.json may interpret differently. The PC sends strict JSON.
    static boolean withinNestingLimit(String text) {
        int opens = 0;
        for (int i = 0; i < text.length() && opens <= MAX_TEXT_NESTING; i++) {
            char c = text.charAt(i);
            if (c == '[' || c == '{') ++opens;
        }
        if (opens <= MAX_TEXT_NESTING) return true;
        int depth = 0;
        boolean inString = false;
        boolean valueMayStart = true; // start of text or after [ { , :
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') ++i;
                else if (c == '"') inString = false;
                continue;
            }
            switch (c) {
                case ' ': case '\t': case '\n': case '\r':
                    continue;
                case '"':
                    if (!valueMayStart) return false;
                    inString = true;
                    valueMayStart = false;
                    continue;
                case '[': case '{':
                    if (++depth > MAX_TEXT_NESTING) return false;
                    valueMayStart = true;
                    continue;
                case ',': case ':':
                    valueMayStart = true;
                    continue;
                case ']': case '}':
                    if (depth > 0) --depth;
                    valueMayStart = false;
                    continue;
                default:
                    if ((c < '0' || c > '9') && "-+.eEtrufalsn".indexOf(c) < 0)
                        return false;
                    valueMayStart = false;
            }
        }
        return true;
    }

    static String readHttpHeader(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() < 16384) {
            int value = input.read();
            if (value < 0) throw new EOFException("HTTP応答が途中で終了しました");
            bytes.write(value);
            int expected = matched == 0 || matched == 2 ? '\r' : '\n';
            if (value == expected) {
                if (++matched == 4)
                    return bytes.toString(StandardCharsets.US_ASCII.name());
            } else {
                matched = value == '\r' ? 1 : 0;
            }
        }
        throw new IOException("HTTPヘッダーが大きすぎます");
    }

    static boolean validHost(String host) {
        return host != null && host.length() >= 1 && host.length() <= 253
                && host.matches("[A-Za-z0-9._-]+");
    }

    // The server refused the credentials. Callers must stop retrying and send
    // the user back to pairing instead.
    static final class AuthRejected extends IOException {
        AuthRejected(String message) {
            super(message);
        }
    }

    static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isEmpty()
                ? ex.getClass().getSimpleName().toLowerCase(Locale.ROOT)
                : message;
    }
}
