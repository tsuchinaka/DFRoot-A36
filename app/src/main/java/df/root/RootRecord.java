package df.root;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Remembers, in app-private storage, that the chain completed during the
 * current boot. Used to tell "not rooted yet" from "rooted but not granted",
 * which look identical from inside the app sandbox.
 */
final class RootRecord {

    private static final String FILE_NAME = "rooted-at";

    private RootRecord() {
    }

    static void markRooted(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        try {
            FileOutputStream out = new FileOutputStream(file, false);
            try {
                out.write(Long.toString(SystemClock.elapsedRealtime()).getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    static boolean rootedThisBoot(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.isFile()) {
            return false;
        }
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                byte[] buffer = new byte[32];
                int read = in.read(buffer);
                if (read <= 0) {
                    return false;
                }
                long stored = Long.parseLong(new String(buffer, 0, read, "UTF-8").trim());
                return SystemClock.elapsedRealtime() >= stored;
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return false;
        }
    }
}
