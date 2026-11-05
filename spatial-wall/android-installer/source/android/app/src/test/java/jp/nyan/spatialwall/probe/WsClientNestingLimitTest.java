// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class WsClientNestingLimitTest {
    private static String repeat(String s, int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) out.append(s);
        return out.toString();
    }

    private static String nested(int depth) {
        return repeat("[", depth) + repeat("]", depth);
    }

    @Test
    public void acceptsServerShapedMessages() {
        assertTrue(WsClient.withinNestingLimit(
                "{\"t\":\"state\",\"dist\":1.5,\"lang\":\"ja\",\"edit\":false}"));
        // Wide but shallow, like "cfg": far more than the limit in total
        // brackets, only a few levels deep.
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            if (i > 0) rows.append(',');
            rows.append("{\"k\":\"r").append(i)
                    .append("\",\"options\":[{\"v\":-1.5e3,\"on\":true,\"x\":null}]}");
        }
        assertTrue(WsClient.withinNestingLimit(
                "{\"t\":\"cfg\",\"sections\":[{\"rows\":[" + rows + "]}]}"));
    }

    @Test
    public void limitIsInclusive() {
        int limit = WsClient.MAX_TEXT_NESTING;
        assertTrue(WsClient.withinNestingLimit(nested(limit)));
        assertFalse(WsClient.withinNestingLimit(nested(limit + 1)));
        assertFalse(WsClient.withinNestingLimit(
                repeat("[", 1 << 20)));
    }

    @Test
    public void bracketsInsideStringsDoNotCount() {
        String text = "{\"t\":\"osd\",\"text\":\"" + repeat("[{", 100)
                + "\\\"" + repeat("[", 100) + "\"}";
        assertTrue(WsClient.withinNestingLimit(text));
    }

    @Test
    public void nonJsonWithFewBracketsIsUntouched() {
        assertTrue(WsClient.withinNestingLimit("hello [world]"));
        assertTrue(WsClient.withinNestingLimit(""));
    }

    @Test
    public void lenientSyntaxCannotHideNesting() {
        String deep = repeat("[", 200);
        assertFalse(WsClient.withinNestingLimit("[1\"," + deep));
        assertFalse(WsClient.withinNestingLimit("['\"'," + deep));
        assertFalse(WsClient.withinNestingLimit("[/*\"*/" + deep));
    }

    private static WsClient.Frame readFrame(int opcode, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(0x80 | opcode);
        if (payload.length <= 125) output.writeByte(payload.length);
        else {
            output.writeByte(126);
            output.writeShort(payload.length);
        }
        output.write(payload);
        return new WsClient(4096, "").readFrame(new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray())));
    }

    @Test
    public void textIsBoundedBeforeAnyChannelConsumerButBinaryIsUnchanged() throws IOException {
        int limit = WsClient.MAX_TEXT_NESTING;
        assertEquals(1, readFrame(1, nested(limit)).opcode);
        assertThrows(IOException.class, () -> readFrame(1, nested(limit + 1)));
        String deep = nested(limit + 1);
        WsClient.Frame binary = readFrame(2, deep);
        assertEquals(2, binary.opcode);
        assertArrayEquals(deep.getBytes(StandardCharsets.UTF_8), binary.payload);
    }
}
