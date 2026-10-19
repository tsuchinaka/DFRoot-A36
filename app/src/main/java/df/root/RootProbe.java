package df.root;

import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Answers one question — "is su answering uid=0 right now?" — without ever
 * assuming anything about module state, and dumps enough of the environment to
 * make a failed boot diagnosable from logcat alone.
 */
final class RootProbe {

    private static final String TAG = "dfroot";
    private static final long TIMEOUT_SECONDS = 3;

    private static final String[] DIAGNOSE_PATHS = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/proc/modules",
            "/sys/module/kernelsu",
            "/dev/df",
            "/data/adb/ksud",
    };

    static final class Result {
        final boolean live;
        final String detail;

        Result(boolean live, String detail) {
            this.live = live;
            this.detail = detail;
        }
    }

    private RootProbe() {
    }

    static Result probe() {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.d(TAG, "su probe: timeout");
                process.destroyForcibly();
                return new Result(false, "timeout");
            }
            String output = read(process.getInputStream()).trim();
            boolean live = process.exitValue() == 0 && output.contains("uid=0");
            Log.d(TAG, "su probe: exit=" + process.exitValue() + " live=" + live);
            return new Result(live, live ? output : "exit=" + process.exitValue());
        } catch (Throwable error) {
            Log.d(TAG, "su probe refused: " + error.getClass().getSimpleName());
            return new Result(false, "not granted");
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String read(InputStream stream) {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null && out.length() < 4096) {
                out.append(line).append('\n');
            }
        } catch (Throwable ignored) {
        }
        return out.toString();
    }

    static String diagnose() {
        StringBuilder out = new StringBuilder();
        out.append("signal probe, uid=").append(Process.myUid()).append('\n');
        out.append("  selinux: ").append(selfContext()).append('\n');
        for (String path : DIAGNOSE_PATHS) {
            File file = new File(path);
            boolean exists = false;
            long length = -1;
            boolean readable = false;
            try {
                exists = file.exists();
                length = file.length();
                readable = file.canRead();
            } catch (Throwable ignored) {
            }
            out.append("  ").append(path)
                    .append(": exists=").append(exists)
                    .append(" len=").append(length)
                    .append(" readable=").append(readable)
                    .append('\n');
        }
        return out.toString();
    }

    private static String selfContext() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream("/proc/self/attr/current")))) {
            String line = reader.readLine();
            return line == null ? "?" : line.trim();
        } catch (Throwable error) {
            return "unreadable (" + error.getClass().getSimpleName() + ")";
        }
    }
}
