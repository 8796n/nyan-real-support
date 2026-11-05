// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// Syntax and identity validation only. An external intent still requires the user's confirmation.
// The website passes only the short-lived invitation, never a saved device credential.
final class PairingLink implements AutoCloseable {
    final String host;
    final int port, grants;
    private final byte[] spki, pin, leaf, invite;
    private boolean closed;

    private PairingLink(String host, int port, int grants, byte[] spki, byte[] pin, byte[] leaf, byte[] invite) {
        this.host = host; this.port = port; this.grants = grants;
        this.spki = spki; this.pin = pin; this.leaf = leaf; this.invite = invite;
    }

    static PairingLink parse(String value) {
        if (value == null || value.length() > 2048) throw invalid();
        byte[] invite = null;
        boolean complete = false;
        try {
            URI uri = new URI(value);
            String fields;
            if ("https".equals(uri.getScheme()) && "support.nyanreal.jp".equals(uri.getRawAuthority())
                    && "/spatial-wall/remote/".equals(uri.getRawPath()) && uri.getRawQuery() == null) {
                fields = uri.getRawFragment();
            } else if ("nyanreal".equals(uri.getScheme()) && "pair".equals(uri.getRawAuthority())
                    && "".equals(uri.getRawPath()) && uri.getRawFragment() == null) {
                fields = uri.getRawQuery();
            } else throw invalid();
            if (fields == null) throw invalid();
            String[] parts = fields.split("&", -1);
            if (parts.length != 7) throw invalid();
            Map<String, String> values = new HashMap<>();
            for (String part : parts) {
                int separator = part.indexOf('=');
                if (separator <= 0 || separator != part.lastIndexOf('=') || separator == part.length() - 1
                        || values.put(part.substring(0, separator), part.substring(separator + 1)) != null)
                    throw invalid();
            }
            if (!values.keySet().equals(Set.of("v", "host", "port", "spki", "cert", "invite", "grants"))
                    || !"2".equals(values.get("v"))) throw invalid();
            String host = PhoneControllerAddress.parse(values.get("host"));
            if (!host.equals(values.get("host"))) throw invalid();
            String portText = values.get("port");
            if (!portText.matches("[1-9][0-9]{0,4}")) throw invalid();
            int port = Integer.parseInt(portText);
            if (port > 65535 || !values.get("grants").matches("[1-7]")) throw invalid();
            int grants = Integer.parseInt(values.get("grants"));
            byte[] spki = decode(values.get("spki"), 1, 256);
            validateSpki(spki);
            byte[] pin = MessageDigest.getInstance("SHA-256").digest(spki);
            byte[] leaf = decode(values.get("cert"), 32, 32);
            invite = decode(values.get("invite"), 32, 32);
            PairingLink result = new PairingLink(host, port, grants, spki, pin, leaf, invite);
            complete = true;
            return result;
        } catch (URISyntaxException | GeneralSecurityException failure) {
            throw invalid(); // Never include the invitation URL in an exception/cause.
        } finally { if (!complete) PairingClient.wipe(invite); }
    }

    byte[] spkiDer() { return spki.clone(); }
    byte[] spkiSha256() { return pin.clone(); }
    byte[] leafSha256() { return leaf.clone(); }
    synchronized byte[] invitation() {
        if (closed) throw new IllegalStateException("Pairing invitation is closed");
        return invite.clone();
    }
    @Override public synchronized void close() { closed = true; PairingClient.wipe(invite); }

    private static byte[] decode(String value, int minimum, int maximum) {
        if (value == null || value.length() > (maximum * 8 + 5) / 6 || !value.matches("[A-Za-z0-9_-]+"))
            throw invalid();
        byte[] decoded;
        try { decoded = Base64.getUrlDecoder().decode(value); }
        catch (IllegalArgumentException rejected) { throw invalid(); }
        byte[] canonical = Base64.getUrlEncoder().withoutPadding().encode(decoded);
        boolean matches = canonical.length == value.length();
        for (int i = 0; matches && i < canonical.length; ++i) matches = canonical[i] == value.charAt(i);
        PairingClient.wipe(canonical);
        if (decoded.length < minimum || decoded.length > maximum || !matches) {
            PairingClient.wipe(decoded);
            throw invalid();
        }
        return decoded;
    }
    private static void validateSpki(byte[] spki) throws GeneralSecurityException {
        var key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
        if (!(key instanceof ECPublicKey ec) || !Arrays.equals(spki, key.getEncoded())) throw invalid();
        AlgorithmParameters named = AlgorithmParameters.getInstance("EC");
        named.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected = named.getParameterSpec(ECParameterSpec.class), actual = ec.getParams();
        if (!expected.getCurve().equals(actual.getCurve()) || !expected.getGenerator().equals(actual.getGenerator())
                || !expected.getOrder().equals(actual.getOrder()) || expected.getCofactor() != actual.getCofactor())
            throw invalid();
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid v2 Spatial Wall pairing link");
    }
}
