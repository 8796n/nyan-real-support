// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// The fixed PC HTTP routes share framing, not redirect/cookie/compression support.
final class RemoteHttpHeaders {
    static final int LIMIT = 8192;
    final int status, length;
    final String contentType;
    private RemoteHttpHeaders(int status, int length, String contentType) {
        this.status = status; this.length = length; this.contentType = contentType;
    }

    static RemoteHttpHeaders read(InputStream input, int maxBody) throws IOException {
        if (maxBody < 0) throw new IllegalArgumentException("Invalid HTTP body limit");
        byte[] header = new byte[LIMIT];
        int used = 0, matched = 0;
        while (matched < 4) {
            if (used == header.length) throw new IOException("PC HTTP header is too large");
            int value = input.read();
            if (value < 0) throw new EOFException("Incomplete PC HTTP header");
            if ((value < 32 && value != '\r' && value != '\n' && value != '\t') || value > 126)
                throw new IOException("Invalid PC HTTP header");
            header[used++] = (byte) value;
            int expected = matched == 0 || matched == 2 ? '\r' : '\n';
            matched = value == expected ? matched + 1 : value == '\r' ? 1 : 0;
        }
        String[] lines = new String(header, 0, used - 4, StandardCharsets.US_ASCII).split("\r\n", -1);
        if (!lines[0].matches("HTTP/1\\.1 [1-5][0-9]{2} [ -~]*"))
            throw new IOException("Invalid PC HTTP status");
        int status = Integer.parseInt(lines[0].substring(9, 12));
        Map<String, String> fields = new HashMap<>();
        for (int i = 1; i < lines.length; ++i) {
            int colon = lines[i].indexOf(':');
            if (colon < 1 || !lines[i].substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))
                throw new IOException("Invalid PC HTTP field");
            String name = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1);
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || fields.put(name, value.trim()) != null)
                throw new IOException("Invalid or duplicate PC HTTP field");
        }
        if (fields.containsKey("transfer-encoding") || fields.containsKey("content-encoding")
                || fields.containsKey("set-cookie")) throw new IOException("Unsupported PC HTTP framing");
        String length = fields.get("content-length");
        if (length == null && status == 204) length = "0";
        if (length == null || !length.matches("0|[1-9][0-9]{0,8}"))
            throw new IOException("Invalid PC HTTP length");
        int size = Integer.parseInt(length);
        if (size > maxBody || (status == 204 && size != 0))
            throw new IOException("PC HTTP body is too large or forbidden");
        return new RemoteHttpHeaders(status, size, fields.get("content-type"));
    }
}
