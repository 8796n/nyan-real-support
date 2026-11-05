// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

// Downloads an update over pinned HTTPS without exposing device credentials to a browser,
// verifies that the archive is an actual update signed as this installed app,
// then delegates the final user confirmation and install to Android.
final class AndroidAppUpdater implements AutoCloseable {
    interface Listener {
        void onStatus(String text);
        default void onAuthRejected() {}
    }

    static final long MAX_APK_BYTES = 128L * 1024L * 1024L;
    private static final String APK_NAME = "spatialwall-update.apk";
    private static final String PART_NAME = APK_NAME + ".part";
    private static volatile WeakReference<AndroidAppUpdater> active =
            new WeakReference<>(null);

    static final class Offer {
        final long versionCode;
        final String versionName;
        final long size;
        final String sha256;

        Offer(long versionCode, String versionName, long size, String sha256) {
            if (versionCode < 1 || versionCode > Integer.MAX_VALUE || versionName == null || versionName.isEmpty()
                    || versionName.length() > 64 || versionName.chars().anyMatch(Character::isISOControl)
                    || !isSupportedSize(size) || !validSha256(sha256))
                throw new IllegalArgumentException("Invalid Android APK offer");
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.size = size;
            this.sha256 = sha256;
        }

        boolean sameAs(Offer other) {
            return other != null && versionCode == other.versionCode
                    && size == other.size && versionName.equals(other.versionName)
                    && sha256.equals(other.sha256);
        }

        boolean isUpdateFor(long installedVersion, String installedSha256) {
            return versionCode > installedVersion
                    || (versionCode == installedVersion && validSha256(sha256)
                    && !sha256.equals(installedSha256));
        }
    }

    private static final class PendingInstall {
        final File apk;
        final Offer offer;

        PendingInstall(File apk, Offer offer) {
            this.apk = apk;
            this.offer = offer;
        }
    }

    private final Context app;
    private final PackageManager packages;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private final ArrayList<WeakReference<Activity>> resumedLaunchers =
            new ArrayList<>(2);
    private PendingInstall pending;
    private Intent pendingSettings;
    private Intent pendingConfirmation;
    private volatile boolean busy;
    private PinnedHttpGet transfer;
    private boolean waitingForPermission;
    private volatile boolean closed;
    private String installedSha256;

    AndroidAppUpdater(Context context, Listener listener) {
        app = context.getApplicationContext();
        packages = app.getPackageManager();
        this.listener = listener;
        active = new WeakReference<>(this);
    }

    static boolean isSupportedSize(long size) {
        return size > 0 && size <= MAX_APK_BYTES;
    }

    static boolean validSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    // SDK JSON decoding stays shared by the full app and installer. Never expose its input in errors.
    static Offer parseOffer(String text) throws IOException {
        if (text == null || text.length() > 65536 || !WsClient.withinNestingLimit(text))
            throw new IOException("Invalid Android APK message");
        try { return parseOffer(new org.json.JSONObject(text)); }
        catch (org.json.JSONException ignored) { throw new IOException("Invalid Android APK message"); }
    }

    static Offer parseOffer(org.json.JSONObject message) throws IOException {
        if (!"android_apk".equals(message.opt("t"))) return null;
        return parseOfferFields(message.opt("schema"), message.opt("application_id"), message.opt("version_code"),
                message.opt("version_name"), message.opt("size"), message.opt("sha256"));
    }

    static Offer parseOfferFields(Object schema, Object applicationId, Object versionCode,
                                  Object versionName, Object size, Object sha256) throws IOException {
        if (!integer(schema, 1, 1) || !BuildConfig.APPLICATION_ID.equals(applicationId)
                || !integer(versionCode, 1, Integer.MAX_VALUE) || !(versionName instanceof String name)
                || !integer(size, 1, MAX_APK_BYTES) || !(sha256 instanceof String hash))
            throw new IOException("Invalid Android APK offer");
        try { return new Offer(((Number) versionCode).longValue(), name, ((Number) size).longValue(), hash); }
        catch (IllegalArgumentException ignored) { throw new IOException("Invalid Android APK offer"); }
    }

    private static boolean integer(Object value, long min, long max) {
        return value instanceof Number number && number.longValue() >= min && number.longValue() <= max
                && number.doubleValue() == number.longValue();
    }

    boolean isBusy() { return busy; }
    synchronized boolean isDownloading() { return transfer != null; }

    void checkOffer(Offer offer, Consumer<Boolean> result) {
        if (closed) return;
        worker.execute(() -> {
            try {
                boolean available = offer.isUpdateFor(BuildConfig.VERSION_CODE,
                        offer.versionCode == BuildConfig.VERSION_CODE
                                && validSha256(offer.sha256) ? installedSha256() : "");
                main.post(() -> { if (!closed) result.accept(available); });
            } catch (Exception ex) {
                report(app.getString(R.string.android_update_check_failed, safeMessage(ex)));
            }
        });
    }

    private String installedSha256() throws Exception {
        if (installedSha256 == null)
            installedSha256 = sha256(new File(app.getApplicationInfo().sourceDir));
        return installedSha256;
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1)
                digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value & 0xff) >> 4, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }

    // Acquire the credential copy synchronously; the caller may close its PcConnection after return.
    void start(PcConnection connection, Offer offer) {
        if (closed) return;
        if (busy) { show(app.getString(R.string.android_update_busy)); return; }
        if (connection == null || (connection.grants & 1) == 0 || offer == null) { show(app.getString(R.string.android_update_invalid)); return; }
        busy = true;
        if (pending != null && pending.offer.sameAs(offer)
                && pending.apk.isFile() && pending.apk.length() == offer.size) {
            requestPermissionOrInstall(pending);
            return;
        }
        final PinnedHttpGet download;
        try {
            download = new PinnedHttpGet(connection);
            synchronized (this) {
                if (closed) { download.close(); busy = false; return; }
                transfer = download;
            }
        } catch (java.security.GeneralSecurityException | RuntimeException failure) {
            busy = false; show(app.getString(R.string.android_update_invalid)); return;
        }
        report(app.getString(R.string.android_update_checking));
        try {
            worker.execute(() -> {
                try {
                    PendingInstall downloaded = downloadAndVerify(download, offer);
                    main.post(() -> {
                        if (!finishDownload(download)) return;
                        pending = downloaded;
                        requestPermissionOrInstall(downloaded);
                    });
                } catch (Exception failure) {
                    main.post(() -> {
                        if (!finishDownload(download)) return;
                        busy = false;
                        if (failure instanceof WsClient.AuthRejected) listener.onAuthRejected();
                        if (!closed) show(app.getString(R.string.android_update_verify_failed, safeMessage(failure)));
                    });
                } finally { download.close(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) { cancelDownload(); }
    }

    private synchronized boolean finishDownload(PinnedHttpGet download) {
        if (closed || transfer != download) return false;
        transfer = null;
        return true;
    }

    private synchronized void checkDownload(PinnedHttpGet download) throws IOException {
        if (closed || transfer != download) throw new IOException("APK download cancelled");
    }

    // Cancels only the network job; an install already handed to Android remains Android-owned.
    void cancelDownload() {
        PinnedHttpGet previous;
        synchronized (this) { previous = transfer; transfer = null; }
        if (previous != null) { previous.close(); busy = false; }
    }

    // Activity starts are permitted only while one of our launchers is
    // RESUMED. This matters when PackageInstaller replies after Home or a DeX
    // display transition: a merely non-destroyed Activity is still background.
    void onActivityResumed(Activity activity) {
        if (closed || activity == null) return;
        removeLauncher(activity);
        resumedLaunchers.add(new WeakReference<>(activity));
        // Opening Settings and returning from it are distinct resumes. On the
        // first one, launch the deferred Intent and do not mistake it for a
        // denial; only the later resume checks canRequestPackageInstalls().
        if (drainPendingSettings()) return;
        if (waitingForPermission) {
            resumeAfterSettings();
            return;
        }
        drainPendingConfirmation();
    }

    void onActivityPaused(Activity activity) {
        removeLauncher(activity);
    }

    private void resumeAfterSettings() {
        if (!waitingForPermission || pending == null) return;
        waitingForPermission = false;
        if (!packages.canRequestPackageInstalls()) {
            finish(false, app.getString(R.string.android_update_permission_required));
            return;
        }
        stageAndCommit(pending);
    }

    private PendingInstall downloadAndVerify(PinnedHttpGet download, Offer offer) throws Exception {
        File part = new File(app.getCacheDir(), PART_NAME), apk = new File(app.getCacheDir(), APK_NAME);
        boolean complete = false, replaced = false;
        byte[] hash = new byte[32];
        for (int i = 0; i < hash.length; ++i) hash[i] = (byte) Integer.parseInt(offer.sha256.substring(2 * i, 2 * i + 2), 16);
        checkDownload(download);
        part.delete();
        try {
            try (FileOutputStream output = new FileOutputStream(part)) {
                download.apk(offer.size, hash, output);
                checkDownload(download);
                output.getFD().sync();
            }
            checkDownload(download);
            apk.delete();
            if (!part.renameTo(apk)) throw new IOException(app.getString(R.string.android_update_save_failed));
            replaced = true;
            verifyArchive(apk, offer);
            checkDownload(download);
            complete = true;
            return new PendingInstall(apk, offer);
        } finally {
            PairingClient.wipe(hash);
            part.delete();
            if (!complete && replaced) apk.delete();
        }
    }

    private void verifyArchive(File apk, Offer offer) throws Exception {
        if (!offer.sha256.equals(sha256(apk)))
            throw new IOException(app.getString(R.string.android_update_hash_mismatch));
        PackageInfo archive = packages.getPackageArchiveInfo(
                apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo installed = packages.getPackageInfo(
                app.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null || installed == null
                || !identityMatches(offer, app.getPackageName(),
                        installed.getLongVersionCode(), archive.packageName,
                        archive.getLongVersionCode(), archive.versionName,
                        signerBytes(installed), signerBytes(archive))) {
            throw new IOException(app.getString(R.string.android_update_identity_mismatch));
        }
        if (!offer.isUpdateFor(installed.getLongVersionCode(),
                offer.versionCode == installed.getLongVersionCode() ? installedSha256() : ""))
            throw new IOException(app.getString(R.string.android_update_already_installed));
    }

    static boolean identityMatches(Offer offer, String installedPackage,
                                   long installedVersion,
                                   String archivePackage, long archiveVersion,
                                   String archiveVersionName,
                                   byte[][] installedSigners,
                                   byte[][] archiveSigners) {
        return offer != null && installedPackage != null
                && installedPackage.equals(archivePackage)
                && (offer.versionCode > installedVersion
                    || (offer.versionCode == installedVersion && validSha256(offer.sha256)))
                && archiveVersion == offer.versionCode
                && offer.versionName.equals(archiveVersionName)
                && sameSignerSet(installedSigners, archiveSigners);
    }

    private static byte[][] signerBytes(PackageInfo info) {
        if (info.signingInfo == null) return null;
        Signature[] signatures = info.signingInfo.getApkContentsSigners();
        if (signatures == null) return null;
        byte[][] result = new byte[signatures.length][];
        for (int i = 0; i < signatures.length; ++i)
            result[i] = signatures[i].toByteArray();
        return result;
    }

    private static boolean sameSignerSet(byte[][] first, byte[][] second) {
        if (first == null || second == null || first.length == 0
                || first.length != second.length) return false;
        boolean[] used = new boolean[second.length];
        for (byte[] wanted : first) {
            boolean found = false;
            for (int i = 0; i < second.length; ++i) {
                if (!used[i] && Arrays.equals(wanted, second[i])) {
                    used[i] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private void requestPermissionOrInstall(PendingInstall install) {
        if (!packages.canRequestPackageInstalls()) {
            waitingForPermission = true;
            report(app.getString(R.string.android_update_permission));
            pendingSettings = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + app.getPackageName()));
            drainPendingSettings();
            return;
        }
        stageAndCommit(install);
    }

    // Returns true whenever a Settings launch is pending so onActivityResumed
    // never falls through to the "returned without permission" branch.
    private boolean drainPendingSettings() {
        if (pendingSettings == null) return false;
        Activity activity = resumedLauncher();
        if (activity == null) return true;
        Intent settings = pendingSettings;
        pendingSettings = null;
        try {
            activity.startActivity(settings);
        } catch (Exception ex) {
            finish(false, app.getString(R.string.android_update_permission_failed, safeMessage(ex)));
        }
        return true;
    }

    private void stageAndCommit(PendingInstall install) {
        report(app.getString(R.string.android_update_preparing));
        worker.execute(() -> {
            int sessionId = 0;
            boolean committed = false;
            try {
                // Re-check after returning from Settings; the cache is private,
                // but this also refuses a stale pending offer after recreation.
                verifyArchive(install.apk, install.offer);
                PackageInstaller installer = packages.getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(
                                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                params.setSize(install.apk.length());
                if (Build.VERSION.SDK_INT >= 31)
                    params.setRequireUserAction(
                            PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                sessionId = installer.createSession(params);
                try (PackageInstaller.Session session =
                             installer.openSession(sessionId);
                     InputStream input = new FileInputStream(install.apk)) {
                    try (OutputStream output = session.openWrite(
                            "base.apk", 0, install.apk.length())) {
                        byte[] buffer = new byte[32 * 1024];
                        int count;
                        while ((count = input.read(buffer)) >= 0) {
                            if (count > 0) output.write(buffer, 0, count);
                        }
                        session.fsync(output);
                    }
                    Intent result = new Intent(app,
                            AndroidUpdateResultReceiver.class)
                            .setAction(app.getPackageName()
                                    + ".ANDROID_UPDATE_RESULT");
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31)
                        flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent callback = PendingIntent.getBroadcast(
                            app, sessionId, result, flags);
                    session.commit(callback.getIntentSender());
                    committed = true;
                }
                report(app.getString(R.string.android_update_opening));
            } catch (Exception ex) {
                fail(app.getString(R.string.android_update_start_failed, safeMessage(ex)));
            } finally {
                if (!committed && sessionId != 0)
                    packages.getPackageInstaller().abandonSession(sessionId);
            }
        });
    }

    static void handleInstallResult(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        AndroidAppUpdater updater = active.get();
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation;
            if (Build.VERSION.SDK_INT >= 33)
                confirmation = intent.getParcelableExtra(
                        Intent.EXTRA_INTENT, Intent.class);
            else
                confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation == null) {
                if (updater != null)
                    updater.finish(false, context.getString(R.string.android_update_confirmation_failed));
                return;
            }
            if (updater != null) updater.deferConfirmation(confirmation);
            return;
        }
        String detail = intent.getStringExtra(
                PackageInstaller.EXTRA_STATUS_MESSAGE);
        String text = status == PackageInstaller.STATUS_SUCCESS
                ? context.getString(R.string.android_update_success)
                : context.getString(R.string.android_update_failed)
                    + (detail == null || detail.isEmpty() ? ""
                            : ": " + detail);
        if (updater != null) updater.finish(
                status == PackageInstaller.STATUS_SUCCESS, text);
        else Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }

    private void deferConfirmation(Intent confirmation) {
        if (closed) return;
        pendingConfirmation = confirmation;
        drainPendingConfirmation();
    }

    private void drainPendingConfirmation() {
        if (pendingConfirmation == null) return;
        Activity activity = resumedLauncher();
        if (activity == null) return;
        Intent confirmation = pendingConfirmation;
        pendingConfirmation = null;
        try {
            activity.startActivity(confirmation);
        } catch (Exception ex) {
            finish(false, app.getString(R.string.android_update_confirmation_failed) + ": " + safeMessage(ex));
        }
    }

    private Activity resumedLauncher() {
        for (int i = resumedLaunchers.size() - 1; i >= 0; --i) {
            Activity activity = resumedLaunchers.get(i).get();
            if (activity != null && !activity.isFinishing()
                    && !activity.isDestroyed()) return activity;
            resumedLaunchers.remove(i);
        }
        return null;
    }

    private void removeLauncher(Activity target) {
        for (int i = resumedLaunchers.size() - 1; i >= 0; --i) {
            Activity activity = resumedLaunchers.get(i).get();
            if (activity == null || activity == target)
                resumedLaunchers.remove(i);
        }
    }

    private void finish(boolean success, String text) {
        main.post(() -> {
            if (closed) return;
            busy = false;
            waitingForPermission = false;
            pendingSettings = null;
            pendingConfirmation = null;
            if (success && pending != null) {
                pending.apk.delete();
                pending = null;
            }
            show(text);
        });
    }

    private void fail(String text) {
        main.post(() -> {
            if (closed) return;
            busy = false;
            waitingForPermission = false;
            pendingSettings = null;
            pendingConfirmation = null;
            show(text);
        });
    }

    private void report(String text) {
        main.post(() -> {
            if (!closed) listener.onStatus(text);
        });
    }

    private void show(String text) {
        listener.onStatus(text);
        Activity activity = resumedLauncher();
        Toast.makeText(activity != null ? activity : app, text,
                Toast.LENGTH_LONG).show();
    }

    private static String safeMessage(Exception ex) {
        String text = ex.getMessage();
        if (text == null || text.isEmpty()) text = ex.getClass().getSimpleName();
        return text.length() <= 160 ? text : text.substring(0, 160);
    }

    @Override
    public void close() {
        closed = true;
        cancelDownload();
        resumedLaunchers.clear();
        pendingSettings = null;
        pendingConfirmation = null;
        worker.shutdownNow();
        AndroidAppUpdater current = active.get();
        if (current == this) active = new WeakReference<>(null);
    }
}
