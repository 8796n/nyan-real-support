// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

// A previously authenticated PC key is the identity, even when its LAN address changes.
// This is not TOFU or a general HTTPS trust policy. A changed pin requires a new instance.
final class PinnedTlsConnector {
    static final int TIMEOUT_MS = 5000;
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private final PinTrustManager trust;
    private final SSLContext context;

    PinnedTlsConnector(byte[] savedSpkiSha256) throws GeneralSecurityException {
        trust = new PinTrustManager(savedSpkiSha256);
        // Never share a resumption cache between different pinned identities.
        context = SSLContext.getInstance("TLSv1.3");
        context.init(new KeyManager[0], new TrustManager[]{trust}, null);
    }

    // The caller registers its direct, unconnected socket before entering here so stop can close it.
    // This method owns the transport: failure closes it; success transfers it to the TLS socket.
    SSLSocket connect(Socket transport, InetSocketAddress endpoint) throws IOException {
        SSLSocket secure = null;
        boolean connected = false;
        try {
            if (transport == null || transport.isConnected() || transport.isClosed())
                throw new IllegalArgumentException("An open, unconnected TCP socket is required");
            if (endpoint == null || endpoint.isUnresolved() || endpoint.getPort() < 1)
                throw new IllegalArgumentException("A resolved TCP endpoint is required");
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
            transport.connect(endpoint, TIMEOUT_MS);
            transport.setTcpNoDelay(true);
            secure = (SSLSocket) context.getSocketFactory().createSocket(
                    transport, endpoint.getHostString(), endpoint.getPort(), true);
            secure.setUseClientMode(true);
            secure.setEnabledProtocols(new String[]{"TLSv1.3"});
            SSLParameters parameters = secure.getSSLParameters();
            // Hostname/CA trust cannot replace the exact, already registered key.
            parameters.setEndpointIdentificationAlgorithm(null);
            secure.setSSLParameters(parameters);
            secure.setSoTimeout(TIMEOUT_MS);
            handshake(secure, transport, deadline);
            secure.setSoTimeout(0); // The channel owns its post-handshake read policy.
            connected = true;
            return secure;
        } finally {
            if (!connected) {
                close(transport); // Do not wait on TLS close_notify after a failed handshake.
                close(secure);
            }
        }
    }

    private void handshake(SSLSocket secure, Socket transport, long deadline) throws IOException {
        final int waiting = 0, complete = 1, expired = 2;
        AtomicInteger state = new AtomicInteger(waiting);
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException("TLS connection deadline exceeded");
        // A per-read SO_TIMEOUT alone lets a slow peer extend the handshake indefinitely.
        ScheduledFuture<?> timeout = DEADLINES.schedule(() -> {
            if (state.compareAndSet(waiting, expired)) close(transport);
        }, remaining, TimeUnit.NANOSECONDS);
        try {
            secure.startHandshake();
            verifySession(secure.getSession());
            if (System.nanoTime() - deadline >= 0 || !state.compareAndSet(waiting, complete))
                throw new SocketTimeoutException("TLS handshake deadline exceeded");
        } catch (IOException ex) {
            if (state.get() == expired) {
                SocketTimeoutException timedOut = new SocketTimeoutException("TLS handshake deadline exceeded");
                timedOut.initCause(ex);
                throw timedOut;
            }
            throw ex;
        } finally {
            state.compareAndSet(waiting, complete);
            timeout.cancel(false);
        }
    }

    // Resumption can reuse an identity without invoking the trust manager again.
    void verifySession(SSLSession session) throws SSLHandshakeException {
        try {
            if (!"TLSv1.3".equals(session.getProtocol()))
                throw new CertificateException("TLS 1.3 is required");
            Certificate[] chain = session.getPeerCertificates();
            if (chain == null || chain.length == 0 || !(chain[0] instanceof X509Certificate leaf))
                throw new CertificateException("Missing peer certificate");
            trust.checkServerTrusted(new X509Certificate[]{leaf}, "TLSv1.3");
        } catch (CertificateException | SSLPeerUnverifiedException ex) {
            session.invalidate();
            SSLHandshakeException failure = new SSLHandshakeException("Pinned PC identity verification failed");
            failure.initCause(ex);
            throw failure;
        }
    }

    static final class PinTrustManager implements X509TrustManager {
        private final byte[] pin;

        PinTrustManager(byte[] savedSpkiSha256) {
            if (savedSpkiSha256 == null || savedSpkiSha256.length != 32)
                throw new IllegalArgumentException("A saved 32-byte SPKI SHA-256 pin is required");
            pin = savedSpkiSha256.clone();
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain == null || chain.length == 0 || chain[0] == null
                    || authType == null || authType.isEmpty())
                throw new CertificateException("Missing peer certificate or authentication type");
            X509Certificate leaf = chain[0];
            leaf.checkValidity();
            PublicKey key = leaf.getPublicKey();
            byte[] spki = key == null ? null : key.getEncoded();
            if (spki == null || spki.length == 0 || !"X.509".equals(key.getFormat()))
                throw new CertificateException("Missing encoded SubjectPublicKeyInfo");
            try {
                if (!MessageDigest.isEqual(pin, MessageDigest.getInstance("SHA-256").digest(spki)))
                    throw new CertificateException("PC public key does not match the saved pin");
            } catch (java.security.NoSuchAlgorithmException ex) {
                throw new CertificateException("SHA-256 is unavailable", ex);
            }
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("Client certificate trust is not supported");
        }

        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread worker = new Thread(task, "pinned-tls-deadline");
            worker.setDaemon(true);
            return worker;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static void close(Closeable connection) {
        if (connection != null) try { connection.close(); } catch (IOException ignored) {}
    }
}
