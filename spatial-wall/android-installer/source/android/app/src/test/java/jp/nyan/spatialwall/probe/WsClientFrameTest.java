// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class WsClientFrameTest {
    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }

    private static byte[] frame(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(0x80 | opcode);
        if (payload.length <= 125) output.writeByte(payload.length);
        else if (payload.length <= 65535) {
            output.writeByte(126);
            output.writeShort(payload.length);
        } else {
            output.writeByte(127);
            output.writeLong(payload.length);
        }
        output.write(payload);
        return bytes.toByteArray();
    }

    private static WsClient.Frame read(int opcode, byte[] payload) throws IOException {
        return new WsClient(65536, "").readFrame(new DataInputStream(
                new ByteArrayInputStream(frame(opcode, payload))));
    }

    // One byte per read models split TCP delivery. Reading past a rejected header fails the test.
    private static final class BoundedStream extends InputStream {
        private final byte[] data;
        private int position;

        BoundedStream(byte[] data) { this.data = data; }

        @Override public int read() {
            if (position == data.length) throw new AssertionError("Read beyond supplied frame/header");
            return data[position++] & 0xFF;
        }

        @Override public int read(byte[] target, int offset, int length) {
            if (length == 0) return 0;
            target[offset] = (byte) read();
            return 1;
        }
    }

    private static void rejectHeader(int... header) {
        BoundedStream input = new BoundedStream(bytes(header));
        assertThrows(IOException.class, () -> new WsClient(4096, "")
                .readFrame(new DataInputStream(input)));
        assertEquals(header.length, input.position);
    }

    @Test public void rejectsReservedBitsOpcodesAndServerMaskBeforePayload() {
        for (int bit : new int[]{0x10, 0x20, 0x40}) rejectHeader(0x81 | bit, 127);
        for (int opcode : new int[]{3, 4, 5, 6, 7, 11, 12, 13, 14, 15})
            rejectHeader(0x80 | opcode, 127);
        rejectHeader(0x81, 0x80);
        rejectHeader(0x82, 0xFF);
    }

    @Test public void continuesToRejectAllFragmentedMessages() {
        for (int opcode : new int[]{0, 1, 2, 8, 9, 10}) rejectHeader(opcode, 0);
        rejectHeader(0x80, 0);
    }

    @Test public void rejectsInvalidControlLengthsWithoutReadingExtendedLength() {
        for (int opcode : new int[]{8, 9, 10}) {
            rejectHeader(0x80 | opcode, 126);
            rejectHeader(0x80 | opcode, 127);
        }
        rejectHeader(0x88, 1);
    }

    @Test public void rejectsNonMinimalNegativeAndOversizedLengthsBeforePayload() {
        rejectHeader(0x82, 126, 0, 125);
        rejectHeader(0x82, 127, 0, 0, 0, 0, 0, 0, 255, 255);
        rejectHeader(0x82, 127, 128, 0, 0, 0, 0, 0, 0, 0);
        rejectHeader(0x82, 126, 16, 1); // 4097 exceeds the configured cap.
        rejectHeader(0x82, 127, 0, 0, 0, 0, 127, 255, 255, 255);
    }

    @Test public void acceptsEachLengthBoundaryAndExactConfiguredLimit() throws IOException {
        for (int size : new int[]{0, 125, 126, 4096, 65535, 65536}) {
            byte[] payload = new byte[size];
            if (size > 0) payload[size - 1] = (byte) 0xFF;
            WsClient.Frame result = new WsClient(size, "").readFrame(new DataInputStream(
                    new ByteArrayInputStream(frame(2, payload))));
            assertEquals(2, result.opcode);
            assertArrayEquals(payload, result.payload);
        }
    }

    @Test public void handlesShortReadsAndLeavesFollowingFrameUntouched() throws IOException {
        byte[] payload = new byte[126];
        payload[125] = (byte) 0xFF;
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write(frame(9, bytes(0xFF)));
        wire.write(frame(2, payload));
        wire.write(frame(8, new byte[0]));
        BoundedStream source = new BoundedStream(wire.toByteArray());
        DataInputStream input = new DataInputStream(source);
        WsClient client = new WsClient(126, "");
        assertEquals(9, client.readFrame(input).opcode);
        assertArrayEquals(payload, client.readFrame(input).payload);
        assertEquals(8, client.readFrame(input).opcode);
        assertEquals(wire.size(), source.position);
    }

    @Test public void truncatedHeadersAndPayloadsFailAtEof() {
        for (byte[] wire : new byte[][]{
                bytes(0x82), bytes(0x82, 126, 1),
                bytes(0x82, 127, 0, 0, 0, 0, 0, 1, 0), bytes(0x82, 2, 1)}) {
            assertThrows(EOFException.class, () -> new WsClient(65536, "")
                    .readFrame(new DataInputStream(new ByteArrayInputStream(wire))));
        }
    }

    @Test public void acceptsUtf8BoundariesAndEmptyText() throws IOException {
        byte[] text = ("ASCII\0\u007f\u0080\u07ff\u0800\ud7ff\ue000\uffff"
                + "\ud800\udc00\udbff\udfff日本語").getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(text, read(1, text).payload);
        assertEquals(0, read(1, new byte[0]).payload.length);
    }

    @Test public void rejectsMalformedUtf8InTextAndCloseReason() throws IOException {
        for (byte[] invalid : new byte[][]{
                bytes(0x80), bytes(0xC0, 0x80), bytes(0xC1, 0xBF),
                bytes(0xE0, 0x9F, 0xBF), bytes(0xED, 0xA0, 0x80),
                bytes(0xF0, 0x8F, 0xBF, 0xBF), bytes(0xF4, 0x90, 0x80, 0x80),
                bytes(0xF5, 0x80, 0x80, 0x80), bytes(0xFE), bytes(0xFF),
                bytes(0xC2), bytes(0xE1, 0x80), bytes(0xF1, 0x80, 0x80), bytes(0xC2, 0x41)}) {
            assertThrows(IOException.class, () -> read(1, invalid));
            byte[] close = new byte[invalid.length + 2];
            close[0] = 3;
            close[1] = (byte) 0xE8; // 1000
            System.arraycopy(invalid, 0, close, 2, invalid.length);
            assertThrows(IOException.class, () -> read(8, close));
        }
    }

    @Test public void binaryPingAndPongDoNotRequireUtf8() throws IOException {
        byte[] payload = new byte[125];
        payload[0] = (byte) 0xFF;
        for (int opcode : new int[]{2, 9, 10}) {
            WsClient.Frame result = read(opcode, payload);
            assertEquals(opcode, result.opcode);
            assertArrayEquals(payload, result.payload);
        }
    }

    @Test public void closeCodesMatchTheServerAndReasonsRemainIntact() throws IOException {
        assertEquals(0, read(8, new byte[0]).payload.length);
        for (int code : new int[]{1000, 1001, 1002, 1003, 1007, 1008, 1009, 1010,
                1011, 1012, 1013, 1014, 3000, 3999, 4999}) {
            byte[] payload = bytes(code >> 8, code);
            assertArrayEquals(payload, read(8, payload).payload);
        }
        byte[] reason = "正常終了😀".getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[reason.length + 2];
        payload[0] = 3;
        payload[1] = (byte) 0xE8;
        System.arraycopy(reason, 0, payload, 2, reason.length);
        assertArrayEquals(payload, read(8, payload).payload);
        for (int code : new int[]{0, 999, 1004, 1005, 1006, 1015, 1016, 2000, 2999, 5000, 65535})
            assertThrows(IOException.class, () -> read(8, bytes(code >> 8, code)));
    }
}
