package df.root;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Stages the heavy assets (ksud, kgta_helper) into credential- and
 * device-protected storage once per app version, so the exploit window never
 * pays the extraction I/O for a 6 MB payload.
 */
final class AssetPrep {

    private static final String TAG = "dfroot";
    private static final String[] ASSETS = {"ksud", "kgta_helper"};

    private AssetPrep() {
    }

    static void prepareIfNeeded(Context ctx) {
        if (isStaged(ctx)) {
            return;
        }
        prepare(ctx);
    }

    static boolean isStaged(Context ctx) {
        int version = versionCode(ctx);
        if (version <= 0) {
            return false;
        }
        for (String name : ASSETS) {
            if (!new File(ctx.getFilesDir(), name).isFile()) {
                return false;
            }
            try {
                if (!new File(ctx.createDeviceProtectedStorageContext()
                        .getFilesDir(), name).isFile()) {
                    return false;
                }
            } catch (Throwable ignored) {
                return false;
            }
        }
        File marker = new File(ctx.getFilesDir(), "assets-staged");
        try {
            return marker.isFile() && String.valueOf(version)
                    .equals(readTrim(marker));
        } catch (Throwable t) {
            return false;
        }
    }

    static synchronized void prepare(Context ctx) {
        int version = versionCode(ctx);
        boolean ok = true;
        for (String name : ASSETS) {
            ok &= stage(ctx, name);
            try {
                ok &= stage(ctx.createDeviceProtectedStorageContext(), name);
            } catch (Throwable ignored) {
            }
        }
        if (ok) {
            try {
                File marker = new File(ctx.getFilesDir(), "assets-staged");
                FileOutputStream out = new FileOutputStream(marker, false);
                try {
                    out.write(String.valueOf(version).getBytes(StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "assets staged for v" + version);
        }
    }

    private static boolean stage(Context ctx, String name) {
        try {
            Runner.stageAsset(ctx, name, true, ctx.getFilesDir());
            return true;
        } catch (IOException e) {
            Log.w(TAG, "stage " + name + ": " + e);
            return false;
        }
    }

    private static String readTrim(File file) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file),
                        StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static int versionCode(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }
}
