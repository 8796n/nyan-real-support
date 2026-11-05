// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
package jp.nyan.spatialwall.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// Explicit, non-exported PackageInstaller callback. The system may deliver it
// after the activity that initiated the update has moved between DeX displays.
public final class AndroidUpdateResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AndroidAppUpdater.handleInstallResult(context, intent);
    }
}
