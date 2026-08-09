package com.n0hindi.servicehider.zygisk;

import android.util.Log;

import com.v7878.zygisk.ZygoteLoader;

public final class ZygoteEntry {
    private static final String TAG = "ServiceHiderZygisk";

    public static void premain() {
        // Do not touch the pre-specialization framework/runtime state.
    }

    public static void main() {
        try {
            int uid = android.os.Process.myUid();
            // Process.FIRST_APPLICATION_UID = 10000
            // Apply ServiceManager proxy to all third-party/user apps
            if (uid >= 10000) {
                ServiceManagerProxy.install();
            }
        } catch (Throwable t) {
            Log.e(TAG, "main", t);
        }
    }
}
