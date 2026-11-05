// SPDX-License-Identifier: MIT
package jp.nyan.spatialwall.probe;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

// One explicitly requested registration, shared by the app and its installer.
// The caller runs perform on its worker, saves only a still-current result, then closes both.
final class PcRegistration implements AutoCloseable {
    private final String host;
    private final int port, grants;
    private final byte[] code, invitation, pin, leaf;
    private final Socket cancellation = new Socket(Proxy.NO_PROXY);
    private PairingClient client;
    private boolean used, closed;

    PcRegistration(String host, int port, byte[] code) {
        if (!WsClient.validHost(host) || port < 1 || port > 65535 || code == null || code.length != 6)
            throw new IllegalArgumentException("Invalid pairing input");
        for (byte digit : code) if (digit < '0' || digit > '9') throw new IllegalArgumentException("Invalid pairing code");
        this.host = host; this.port = port; this.code = code.clone();
        grants = 0; invitation = pin = leaf = null;
    }

    PcRegistration(PairingLink link) {
        host = link.host; port = link.port; grants = link.grants;
        invitation = link.invitation(); pin = link.spkiSha256(); leaf = link.leafSha256(); code = null;
    }

    PairingClient.Result perform(String label) throws IOException {
        byte[] ownedCode, ownedInvite;
        synchronized (this) {
            if (used || closed) throw new IOException("Registration is closed");
            used = true;
            ownedCode = code == null ? null : code.clone();
            ownedInvite = invitation == null ? null : invitation.clone();
        }
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PinnedTlsConnector.TIMEOUT_MS);
            InetSocketAddress endpoint = new InetSocketAddress(PcAddress.resolve(host, cancellation, deadline), port);
            synchronized (this) {
                if (closed || System.nanoTime() - deadline >= 0) throw new IOException("Registration cancelled or timed out");
                client = new PairingClient(endpoint);
            }
            PairingClient.Result result = ownedCode != null ? client.pairCode(ownedCode, label)
                    : client.enrollInvite(pin, leaf, grants, ownedInvite, label);
            synchronized (this) {
                if (closed) { result.close(); throw new IOException("Registration cancelled"); }
            }
            return result;
        } finally { PairingClient.wipe(ownedCode); PairingClient.wipe(ownedInvite); close(); }
    }

    @Override public synchronized void close() {
        closed = true;
        try { cancellation.close(); } catch (IOException ignored) {}
        if (client != null) client.close();
        PairingClient.wipe(code); PairingClient.wipe(invitation);
    }
}
