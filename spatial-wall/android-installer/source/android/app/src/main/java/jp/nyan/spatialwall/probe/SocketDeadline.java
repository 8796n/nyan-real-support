// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocket;

// Absolute network deadline: close the raw transport, never block a timer on TLS close_notify.
final class SocketDeadline implements AutoCloseable {
    private static final ScheduledThreadPoolExecutor TIMER = timer();
    private final long end;
    private final ScheduledFuture<?> timeout;
    private volatile boolean expired;
    SocketDeadline(Socket raw, int milliseconds) {
        if (raw == null || raw instanceof SSLSocket || milliseconds <= 0)
            throw new IllegalArgumentException("A raw transport and bounded deadline are required");
        end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
        timeout = TIMER.schedule(() -> {
            expired = true;
            try { raw.close(); } catch (IOException ignored) {}
        }, milliseconds, TimeUnit.MILLISECONDS);
    }
    void check() throws SocketTimeoutException {
        if (expired || System.nanoTime() - end >= 0) throw new SocketTimeoutException("PC request deadline exceeded");
    }
    IOException failure(IOException cause) {
        try { check(); return cause; }
        catch (SocketTimeoutException failure) { failure.initCause(cause); return failure; }
    }
    @Override public void close() { timeout.cancel(false); }
    private static ScheduledThreadPoolExecutor timer() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread worker = new Thread(task, "pc-request-deadline"); worker.setDaemon(true); return worker;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
