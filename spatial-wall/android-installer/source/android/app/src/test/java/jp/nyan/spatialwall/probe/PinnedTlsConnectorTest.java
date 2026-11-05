// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import static org.junit.Assert.*;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.security.auth.x500.X500Principal;
import org.junit.Test;

public final class PinnedTlsConnectorTest {
    private static PublicKey key() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair().getPublic();
    }

    private static byte[] pin(PublicKey key) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
    }

    // Only the platform certificate accessors used by the pin policy are modeled here.
    private static final class Leaf extends X509Certificate {
        private final PublicKey key;
        private final int serial;
        private final long from, until;

        Leaf(PublicKey key, int serial) { this(key, serial, 0, Long.MAX_VALUE); }
        Leaf(PublicKey key, int serial, long from, long until) {
            this.key = key; this.serial = serial; this.from = from; this.until = until;
        }
        @Override public void checkValidity() throws CertificateExpiredException, CertificateNotYetValidException {
            checkValidity(new Date());
        }
        @Override public void checkValidity(Date date) throws CertificateExpiredException, CertificateNotYetValidException {
            if (date.getTime() < from) throw new CertificateNotYetValidException();
            if (date.getTime() > until) throw new CertificateExpiredException();
        }
        @Override public PublicKey getPublicKey() { return key; }
        @Override public byte[] getEncoded() { return new byte[]{(byte) serial}; }
        @Override public int getVersion() { return 3; }
        @Override public BigInteger getSerialNumber() { return BigInteger.valueOf(serial); }
        @Override public Principal getIssuerDN() { return new X500Principal("CN=Test PC"); }
        @Override public Principal getSubjectDN() { return getIssuerDN(); }
        @Override public Date getNotBefore() { return new Date(from); }
        @Override public Date getNotAfter() { return new Date(until); }
        @Override public byte[] getTBSCertificate() { throw new UnsupportedOperationException(); }
        @Override public byte[] getSignature() { throw new UnsupportedOperationException(); }
        @Override public String getSigAlgName() { return "SHA256withECDSA"; }
        @Override public String getSigAlgOID() { return "1.2.840.10045.4.3.2"; }
        @Override public byte[] getSigAlgParams() { return null; }
        @Override public boolean[] getIssuerUniqueID() { return null; }
        @Override public boolean[] getSubjectUniqueID() { return null; }
        @Override public boolean[] getKeyUsage() { return null; }
        @Override public int getBasicConstraints() { return -1; }
        @Override public Set<String> getCriticalExtensionOIDs() { return null; }
        @Override public Set<String> getNonCriticalExtensionOIDs() { return null; }
        @Override public byte[] getExtensionValue(String oid) { return null; }
        @Override public boolean hasUnsupportedCriticalExtension() { return false; }
        @Override public void verify(PublicKey key) { throw new UnsupportedOperationException(); }
        @Override public void verify(PublicKey key, String provider) { throw new UnsupportedOperationException(); }
        @Override public String toString() { return "Test leaf " + serial; }
    }

    @Test public void pinIsMandatoryExactLengthAndDefensivelyCopied() throws Exception {
        for (byte[] invalid : new byte[][]{null, new byte[0], new byte[31], new byte[33]})
            assertThrows(IllegalArgumentException.class, () -> new PinnedTlsConnector(invalid));
        PublicKey key = key();
        byte[] saved = pin(key);
        PinnedTlsConnector.PinTrustManager trust = new PinnedTlsConnector.PinTrustManager(saved);
        Arrays.fill(saved, (byte) 0);
        trust.checkServerTrusted(new X509Certificate[]{new Leaf(key, 1)}, "UNKNOWN");
        assertEquals(0, trust.getAcceptedIssuers().length);
        assertThrows(CertificateException.class,
                () -> trust.checkClientTrusted(new X509Certificate[]{new Leaf(key, 1)}, "EC"));
    }

    @Test public void renewedCertificateKeepsIdentityButOtherLeafKeyIsRejected() throws Exception {
        PublicKey key = key();
        PinnedTlsConnector.PinTrustManager trust = new PinnedTlsConnector.PinTrustManager(pin(key));
        Leaf original = new Leaf(key, 1), renewed = new Leaf(key, 2), other = new Leaf(key(), 3);
        assertFalse(Arrays.equals(original.getEncoded(), renewed.getEncoded()));
        trust.checkServerTrusted(new X509Certificate[]{original}, "EC");
        trust.checkServerTrusted(new X509Certificate[]{renewed}, "EC");
        assertThrows(CertificateException.class,
                () -> trust.checkServerTrusted(new X509Certificate[]{other}, "EC"));
        // A matching key later in the chain must not authenticate an unrelated leaf.
        assertThrows(CertificateException.class,
                () -> trust.checkServerTrusted(new X509Certificate[]{other, original}, "EC"));
    }

    @Test public void validityAndMissingCertificateCannotBeOverriddenByPin() throws Exception {
        PublicKey key = key();
        PinnedTlsConnector.PinTrustManager trust = new PinnedTlsConnector.PinTrustManager(pin(key));
        assertThrows(CertificateExpiredException.class,
                () -> trust.checkServerTrusted(new X509Certificate[]{new Leaf(key, 1, 0, 1)}, "EC"));
        assertThrows(CertificateNotYetValidException.class, () -> trust.checkServerTrusted(
                new X509Certificate[]{new Leaf(key, 1, Long.MAX_VALUE - 1, Long.MAX_VALUE)}, "EC"));
        for (X509Certificate[] chain : new X509Certificate[][]{null, new X509Certificate[0], {null}})
            assertThrows(CertificateException.class, () -> trust.checkServerTrusted(chain, "EC"));
        assertThrows(CertificateException.class,
                () -> trust.checkServerTrusted(new X509Certificate[]{new Leaf(key, 1)}, ""));
    }

    private static SSLSession session(String protocol, Certificate[] peer, AtomicBoolean invalidated) {
        return (SSLSession) java.lang.reflect.Proxy.newProxyInstance(SSLSession.class.getClassLoader(),
                new Class<?>[]{SSLSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getProtocol" -> protocol;
                    case "getPeerCertificates" -> peer;
                    case "invalidate" -> { invalidated.set(true); yield null; }
                    default -> throw new AssertionError("Unexpected session call: " + method.getName());
                });
    }

    @Test public void resumedSessionRechecksPinValidityAndTlsVersion() throws Exception {
        PublicKey key = key();
        PinnedTlsConnector connector = new PinnedTlsConnector(pin(key));
        AtomicBoolean invalidated = new AtomicBoolean();
        SSLSession original = session("TLSv1.3", new Certificate[]{new Leaf(key, 1)}, invalidated);
        connector.verifySession(original);
        assertFalse(invalidated.get());
        PinnedTlsConnector changedIdentity = new PinnedTlsConnector(pin(key()));
        assertThrows(SSLHandshakeException.class, () -> changedIdentity.verifySession(original));
        assertTrue(invalidated.get());
        for (SSLSession rejected : new SSLSession[]{
                session("TLSv1.2", new Certificate[]{new Leaf(key, 1)}, invalidated),
                session("TLSv1.3", new Certificate[]{new Leaf(key, 1, 0, 1)}, invalidated),
                session("TLSv1.3", new Certificate[0], invalidated)}) {
            invalidated.set(false);
            assertThrows(SSLHandshakeException.class, () -> connector.verifySession(rejected));
            assertTrue(invalidated.get());
        }
    }

    @Test public void unresolvedEndpointsAreRejectedBeforeUnboundedDnsOrConnect() throws Exception {
        PinnedTlsConnector connector = new PinnedTlsConnector(pin(key()));
        for (InetSocketAddress endpoint : new InetSocketAddress[]{null,
                InetSocketAddress.createUnresolved("unresolved.invalid", 8797),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)}) {
            Socket transport = new Socket(Proxy.NO_PROXY);
            assertThrows(IllegalArgumentException.class, () -> connector.connect(transport, endpoint));
            assertTrue(transport.isClosed());
        }
    }

    @Test public void requiresAnOpenUnconnectedTransport() throws Exception {
        PinnedTlsConnector connector = new PinnedTlsConnector(pin(key()));
        InetSocketAddress endpoint = new InetSocketAddress(InetAddress.getLoopbackAddress(), 8797);
        assertThrows(IllegalArgumentException.class, () -> connector.connect(null, endpoint));
        Socket closed = new Socket(Proxy.NO_PROXY);
        closed.close();
        assertThrows(IllegalArgumentException.class, () -> connector.connect(closed, endpoint));
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket connected = new Socket(Proxy.NO_PROXY)) {
            connected.connect(new InetSocketAddress(server.getInetAddress(), server.getLocalPort()), 1000);
            assertThrows(IllegalArgumentException.class, () -> connector.connect(connected, endpoint));
            assertTrue(connected.isClosed());
        }
    }

    @Test(timeout = 12000)
    public void slowTlsRecordCannotExtendTheHandshakeDeadline() throws Exception {
        AtomicBoolean sending = new AtomicBoolean(true);
        AtomicInteger drips = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread peer = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    // A TLS handshake record whose body never finishes, despite frequent bytes.
                    accepted.getOutputStream().write(new byte[]{22, 3, 3, 16, 0});
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                    while (sending.get() && System.nanoTime() < end) {
                        accepted.getOutputStream().write(0);
                        accepted.getOutputStream().flush();
                        drips.incrementAndGet();
                        Thread.sleep(20);
                    }
                } catch (IOException ignored) {
                    // The client's absolute deadline closes its transport.
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }, "tls-slow-record-peer");
            peer.setDaemon(true);
            peer.start();
            try {
                PinnedTlsConnector connector = new PinnedTlsConnector(pin(key()));
                assertThrows(SocketTimeoutException.class, () -> connector.connect(new Socket(Proxy.NO_PROXY),
                        new InetSocketAddress(server.getInetAddress(), server.getLocalPort())));
                assertTrue(drips.get() > 10);
            } finally {
                sending.set(false);
                server.close();
                peer.interrupt();
                peer.join(1000);
            }
        }
    }

    @Test(timeout = 4000)
    public void callerCloseCancelsAnInProgressTlsHandshake() throws Exception {
        CountDownLatch receivedClientHello = new CountDownLatch(1);
        CountDownLatch stopPeer = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket transport = new Socket(Proxy.NO_PROXY)) {
            Thread peer = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.setSoTimeout(1000);
                    if (accepted.getInputStream().read() >= 0) receivedClientHello.countDown();
                    // The server leaves its TLS record unfinished until the caller cancels.
                    accepted.getOutputStream().write(new byte[]{22, 3, 3, 16, 0, 0});
                    accepted.getOutputStream().flush();
                    stopPeer.await();
                } catch (IOException ignored) {
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }, "tls-cancel-peer");
            PinnedTlsConnector connector = new PinnedTlsConnector(pin(key()));
            Thread client = new Thread(() -> {
                try {
                    connector.connect(transport,
                            new InetSocketAddress(server.getInetAddress(), server.getLocalPort()));
                    failure.set(new AssertionError("Incomplete TLS handshake succeeded"));
                } catch (Throwable ex) { failure.set(ex); }
            }, "tls-cancel-client");
            peer.setDaemon(true);
            client.setDaemon(true);
            peer.start();
            client.start();
            try {
                assertTrue(receivedClientHello.await(1, TimeUnit.SECONDS));
                transport.close();
                client.join(1000);
                assertFalse(client.isAlive());
                assertTrue(failure.get() instanceof IOException);
                assertFalse(failure.get() instanceof SocketTimeoutException);
                assertTrue(transport.isClosed());
            } finally {
                transport.close();
                stopPeer.countDown();
                server.close();
                peer.interrupt();
                peer.join(1000);
                client.join(1000);
            }
        }
    }
}
