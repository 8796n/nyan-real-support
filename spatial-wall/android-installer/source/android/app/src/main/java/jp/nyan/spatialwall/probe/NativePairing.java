// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import java.util.Arrays;

// Lifetime and array ownership only; the PAKE protocol and its state live in core.
// Callers must close the session and erase their code/proof arrays after use.
final class NativePairing implements AutoCloseable {
    static final int CLIENT = 0, SERVER = 1;
    static final int CONTEXT_BYTES = 98, CODE_BYTES = 6, MESSAGE_BYTES = 32;

    static { System.loadLibrary("nyan_android_core"); }

    private long handle;

    NativePairing(int role, byte[] context, byte[] code) {
        byte[] contextCopy = copy(context, CONTEXT_BYTES);
        byte[] codeCopy = null;
        try {
            codeCopy = copy(code, CODE_BYTES);
            handle = createNative(role, contextCopy, codeCopy);
            if (handle == 0) throw new IllegalStateException("Pairing initialization failed");
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        } finally {
            Arrays.fill(contextCopy, (byte) 0);
            if (codeCopy != null) Arrays.fill(codeCopy, (byte) 0);
        }
    }

    synchronized byte[] message() {
        try {
            requireOpen();
            return output(messageNative(handle));
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    synchronized void processPeer(byte[] message) {
        try {
            requireOpen();
            if (!processPeerNative(handle, copy(message, MESSAGE_BYTES)))
                throw new IllegalStateException("Pairing peer rejected");
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    synchronized byte[] confirmation() {
        try {
            requireOpen();
            return output(confirmationNative(handle));
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    synchronized void verifyConfirmation(byte[] confirmation) {
        try {
            requireOpen();
            if (!verifyConfirmationNative(handle, copy(confirmation, MESSAGE_BYTES)))
                throw new IllegalStateException("Pairing confirmation rejected");
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    // This secret may be sent only through the authenticated pinned TLS/WT path.
    synchronized byte[] enrollmentProof() {
        try {
            requireOpen();
            return output(enrollmentProofNative(handle));
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    @Override public synchronized void close() {
        long owned = handle;
        handle = 0;
        if (owned != 0) destroyNative(owned);
    }

    private void requireOpen() {
        if (handle == 0) throw new IllegalStateException("Pairing session is closed");
    }

    private static byte[] copy(byte[] value, int length) {
        if (value == null || value.length != length)
            throw new IllegalArgumentException("Invalid pairing array length");
        return value.clone();
    }

    private static byte[] output(byte[] value) {
        if (value == null || value.length != MESSAGE_BYTES) {
            if (value != null) Arrays.fill(value, (byte) 0);
            throw new IllegalStateException("Pairing operation rejected");
        }
        return value;
    }

    private static native long createNative(int role, byte[] context, byte[] code);
    private static native byte[] messageNative(long handle);
    private static native boolean processPeerNative(long handle, byte[] message);
    private static native byte[] confirmationNative(long handle);
    private static native boolean verifyConfirmationNative(long handle, byte[] confirmation);
    private static native byte[] enrollmentProofNative(long handle);
    private static native void destroyNative(long handle);
}
