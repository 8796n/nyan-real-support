// SPDX-License-Identifier: MIT
package jp.nyan.spatialwall.probe;

import java.net.InetAddress;

final class PhoneControllerAddress {
    enum Hand {
        LEFT, RIGHT;
        String path() { return this == LEFT ? "/controller-left-v1" : "/controller-right-v1"; }
    }
    // Numeric LAN IPv4 only: neither pairing nor control performs external DNS lookups.
    static String parse(String value) {
        if (value == null) throw new IllegalArgumentException();
        String host = value.trim();
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException();
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; ++i) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException();
            int n = Integer.parseInt(parts[i]);
            if (n > 255) throw new IllegalArgumentException();
            bytes[i] = (byte)n;
        }
        try {
            InetAddress address = InetAddress.getByAddress(bytes);
            if (!RdpNetwork.localAddress(address) || address.isLoopbackAddress()) throw new IllegalArgumentException();
        } catch (java.net.UnknownHostException e) { throw new IllegalArgumentException(e); }
        return host;
    }
    private PhoneControllerAddress() {}
}
