// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLSocket;

// This same-signed starter only registers locally and installs the PC's verified full APK.
public final class InstallerActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SpatialConnections connections;
    private SpatialCredentials credentials;
    private AndroidAppUpdater updater;
    private PcRegistration registration;
    private AlertDialog pairingPrompt;
    private PairingClient.Result pendingResult;
    private PcConnection pendingConnection;
    private Socket activeSocket;
    private EditText host, port, code;
    private TextView status;
    private Button saved, register, reset, cancel;
    private volatile long generation;
    private volatile boolean destroyed;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this); scroll.addView(content); setContentView(scroll);
        TextView title = new TextView(this); title.setText(R.string.installer_title); title.setTextSize(24); content.addView(title);
        TextView explanation = new TextView(this); explanation.setText(R.string.installer_intro); content.addView(explanation);
        status = new TextView(this); status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE); content.addView(status);
        saved = button(content, R.string.installer_saved, () -> downloadSelected());
        host = field(content, R.string.installer_host, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        port = field(content, R.string.installer_port, InputType.TYPE_CLASS_NUMBER); port.setText("8797");
        code = field(content, R.string.installer_code, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        code.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        code.setSaveEnabled(false);
        register = button(content, R.string.installer_register, () -> registerCode());
        cancel = button(content, android.R.string.cancel, () -> {
            updater.cancelDownload(); stopWork(); show(R.string.installer_cancelled);
        });
        reset = button(content, R.string.installer_reset, () -> confirmReset());
        button(content, R.string.installer_licenses, () -> showLicenses());
        updater = new AndroidAppUpdater(this, text -> { status.setText(text); render(); });
        connections = new SpatialConnections(getSharedPreferences(SpatialConnections.PREFS, MODE_PRIVATE));
        try { credentials = SpatialCredentialStorage.open(this); render(); }
        catch (Exception failure) { storageFailed(); }
        consumeIntent(getIntent());
    }

    private EditText field(LinearLayout content, int label, int inputType) {
        TextView caption = new TextView(this); caption.setText(label); content.addView(caption);
        EditText field = new EditText(this); field.setInputType(inputType);
        field.setSingleLine(true); field.setContentDescription(getString(label)); content.addView(field); return field;
    }
    private Button button(LinearLayout content, int label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(view -> action.run());
        content.addView(button); return button;
    }
    private void show(int message) { status.setText(message); }
    private boolean current(long run) { return !destroyed && run == generation; }
    private boolean canStart() {
        if (updater != null && updater.isBusy()) { show(R.string.installer_updating); return false; }
        return !destroyed;
    }
    private void render() {
        boolean idle = !busy && (updater == null || !updater.isBusy());
        register.setEnabled(credentials != null && idle); reset.setEnabled(idle);
        cancel.setEnabled(busy || updater != null && updater.isDownloading());
        SpatialConnections.Connection selected = credentials == null ? null : connections.selected();
        saved.setEnabled(credentials != null && selected != null && idle);
        saved.setText(selected == null ? getString(R.string.installer_saved) : getString(R.string.installer_saved_address, selected.address()));
    }
    private synchronized void stopWork() {
        ++generation; busy = false;
        if (registration != null) { registration.close(); registration = null; }
        if (pairingPrompt != null) pairingPrompt.dismiss();
        if (activeSocket != null) { try { activeSocket.close(); } catch (IOException ignored) {} activeSocket = null; }
        if (pendingResult != null) { pendingResult.close(); pendingResult = null; }
        if (pendingConnection != null) { pendingConnection.close(); pendingConnection = null; }
        if (code != null) code.setText("");
        if (!destroyed) render();
    }
    private void storageFailed() {
        if (credentials != null) credentials.close(); credentials = null;
        stopWork(); updater.close(); show(R.string.installer_storage_failed);
    }
    private void registerCode() {
        if (!canStart() || credentials == null || busy) return;
        byte[] digits = code.getText().toString().getBytes(StandardCharsets.US_ASCII); code.setText("");
        try {
            String address = host.getText().toString().trim(); int number = Integer.parseInt(port.getText().toString());
            beginRegistration(new PcRegistration(address, number, digits), address, number);
        } catch (RuntimeException failure) { show(R.string.installer_invalid); }
        finally { PairingClient.wipe(digits); }
    }
    private void beginRegistration(PcRegistration attempt, String address, int number) {
        final SpatialConnections.Connection profile;
        try { profile = new SpatialConnections.Connection(UUID.randomUUID().toString(), address.substring(0, Math.min(80, address.length())), address, number); }
        catch (RuntimeException failure) { attempt.close(); throw failure; }
        stopWork(); registration = attempt; busy = true; render(); show(R.string.installer_registering);
        long run = generation;
        worker.execute(() -> {
            try {
                PairingClient.Result result = attempt.perform(getString(R.string.installer_device_name));
                synchronized (this) {
                    if (!current(run)) { result.close(); return; }
                    pendingResult = result;
                }
                main.post(() -> {
                    try {
                        if (!current(run)) return;
                        if ((result.grants & 1) == 0) { stopWork(); show(R.string.installer_remote_required); return; }
                        credentials.put(profile.id, result);
                        connections.saveAndSelect(profile);
                        stopWork(); downloadSelected();
                    } catch (Exception failure) { if (current(run)) storageFailed(); }
                    finally {
                        synchronized (InstallerActivity.this) { if (pendingResult == result) pendingResult = null; }
                        result.close();
                    }
                });
            } catch (Exception failure) { main.post(() -> { if (current(run)) { stopWork(); show(R.string.installer_failed); } }); }
        });
    }

    private void downloadSelected() {
        if (!canStart() || credentials == null || busy) return;
        final PcConnection connection;
        try {
            SpatialConnections.Connection selected = connections.selected();
            if (selected == null) return;
            try (SpatialCredentials.Entry entry = credentials.get(selected.id)) {
                if (entry == null) { show(R.string.installer_register_again); return; }
                connection = new PcConnection(selected.host, selected.port, entry);
            }
        } catch (Exception failure) { storageFailed(); return; }
        stopWork(); busy = true; render(); show(R.string.installer_connecting); long run = generation;
        pendingConnection = connection;
        worker.execute(() -> {
            try {
                AndroidAppUpdater.Offer offer = readOffer(connection, run);
                main.post(() -> {
                    try {
                        if (!current(run)) return;
                        updater.start(connection, offer); stopWork();
                    } finally { connection.close(); }
                });
            } catch (Exception failure) {
                connection.close();
                main.post(() -> { if (current(run)) { stopWork(); show(R.string.installer_failed); } });
            }
        });
    }
    private AndroidAppUpdater.Offer readOffer(PcConnection connection, long run) throws Exception {
        Socket raw = new Socket(Proxy.NO_PROXY);
        synchronized (this) {
            if (!current(run)) { raw.close(); throw new IOException("Installer cancelled"); }
            activeSocket = raw;
        }
        SSLSocket tls = null;
        try {
            tls = connection.connect(raw);
            DataInputStream input = new DataInputStream(tls.getInputStream());
            BufferedOutputStream output = new BufferedOutputStream(tls.getOutputStream());
            WsClient ws = new WsClient(65536, "");
            ws.authenticatedHandshake("/ws", connection.wireHost(), connection.port, input, output, connection.authorization(), raw);
            try (SocketDeadline deadline = new SocketDeadline(raw, 5000)) {
                for (int count = 0; count < 128; ++count) {
                    WsClient.Frame frame = ws.readFrame(input); deadline.check();
                    if (frame.opcode == 8) break;
                    if (frame.opcode == 9) ws.sendFrame(output, 10, frame.payload);
                    if (frame.opcode != 1) continue;
                    AndroidAppUpdater.Offer offer = AndroidAppUpdater.parseOffer(new String(frame.payload, StandardCharsets.UTF_8));
                    if (offer != null) return offer;
                }
            }
            throw new IOException("No verified APK offer");
        } finally {
            // Close raw before TLS close_notify can wait on an unresponsive peer.
            raw.close();
            if (tls != null) tls.close();
            synchronized (this) { if (activeSocket == raw) activeSocket = null; }
        }
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); consumeIntent(intent); }
    private void consumeIntent(Intent intent) {
        String value = intent == null ? null : intent.getDataString();
        if (intent != null) intent.setData(null);
        if (value == null || !canStart() || credentials == null) return;
        final PairingLink link;
        try { link = PairingLink.parse(value); }
        catch (IllegalArgumentException failure) { show(R.string.installer_invalid); return; }
        stopWork();
        AlertDialog prompt = new AlertDialog.Builder(this).setTitle(R.string.installer_confirm_title)
                .setMessage(getString(R.string.installer_confirm, link.host + ":" + link.port))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {})
                .setPositiveButton(R.string.installer_register, (dialog, which) -> {
                    if (canStart() && credentials != null) beginRegistration(new PcRegistration(link), link.host, link.port);
                }).create();
        prompt.setOnDismissListener(dialog -> { link.close(); if (pairingPrompt == prompt) pairingPrompt = null; });
        pairingPrompt = prompt; prompt.show();
    }
    private void confirmReset() {
        if (!canStart()) return;
        new AlertDialog.Builder(this).setTitle(R.string.installer_reset).setMessage(R.string.installer_reset_confirm)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.installer_reset, (dialog, which) -> {
                    if (!canStart()) return;
                    stopWork(); if (credentials != null) credentials.close(); credentials = null;
                    try {
                        credentials = SpatialCredentialStorage.reset(this); connections.clear();
                        updater.close(); updater = new AndroidAppUpdater(this, text -> { status.setText(text); render(); }); updater.onActivityResumed(this);
                        render(); show(R.string.installer_register_again);
                    } catch (Exception failure) { storageFailed(); }
                }).show();
    }
    private void showLicenses() {
        try {
            StringBuilder text = new StringBuilder();
            for (String name : new String[]{"Spatial-Wall-installer-MIT.txt", "boringssl.txt", "LLVM-runtime-NOTICE.txt"}) {
                try (java.io.InputStream input = getAssets().open("licenses/" + name)) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[8192]; int count;
                    while ((count = input.read(chunk)) != -1) bytes.write(chunk, 0, count);
                    text.append(name).append("\n\n").append(bytes.toString(StandardCharsets.UTF_8.name())).append("\n\n");
                }
            }
            new AlertDialog.Builder(this).setTitle(R.string.installer_licenses).setMessage(text).setPositiveButton(android.R.string.ok, null).show();
        } catch (IOException failure) { show(R.string.installer_failed); }
    }
    @Override protected void onResume() { super.onResume(); if (updater != null) updater.onActivityResumed(this); }
    @Override protected void onPause() { if (updater != null) updater.onActivityPaused(this); super.onPause(); }
    @Override protected void onDestroy() {
        destroyed = true; stopWork(); worker.shutdownNow();
        if (updater != null) updater.close(); if (credentials != null) credentials.close(); super.onDestroy();
    }
}
