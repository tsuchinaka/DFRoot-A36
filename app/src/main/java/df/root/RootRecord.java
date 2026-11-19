package df.root;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Remembers, in app-private storage, that the chain completed during the
 * current boot. Stores the boot id: an elapsedRealtime comparison can report
 * a previous boot as current after a reboot.
 */
final class RootRecord {

    private static final String FILE_NAME = "rooted-at";

    private static File storeDir(Context context) {
        try {
            return context.createDeviceProtectedStorageContext().getFilesDir();
        } catch (Throwable t) {
            return context.getFilesDir();
        }
    }

    private RootRecord() {
    }

    static void markRooted(Context context) {
        writeAtomic(new File(storeDir(context), FILE_NAME), BootId.current());
    }

    static boolean rootedThisBoot(Context context) {
        File file = new File(storeDir(context), FILE_NAME);
        if (!file.isFile()) {
            File legacy = new File(context.getFilesDir(), FILE_NAME);
            if (!legacy.isFile()) {
                return false;
            }
            file = legacy;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64];
            int read = in.read(buffer);
            if (read <= 0) {
                return false;
            }
            String stored = new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
            String boot = BootId.current();
            return !stored.isEmpty() && !boot.isEmpty() && stored.equals(boot);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void writeAtomic(File file, String value) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return;
            }
            File tmp = new File(file.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp, false);
            try {
                out.write(value.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable ignored) {
        }
    }
}
