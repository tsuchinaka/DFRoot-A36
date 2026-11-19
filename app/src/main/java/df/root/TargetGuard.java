package df.root;

import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * Refuses to run the chain on anything that is not the device this build was
 * made for. The exploit patches libc/libc++ page cache and loads a kernel
 * module: on the wrong kernel it does not fail, it bricks. Every entry point
 * (UI, boot chain, adb) must ask this first.
 *
 * Matched granularity is the KMI, not the exact firmware build: the dirtyfrag
 * module is built per android-release + kernel major.minor, and the libc hook
 * offsets are resolved from the live symbols at run time, so same-KMI rebuilds
 * are in scope and anything else is refused.
 */
final class TargetGuard {

    private static final String TAG = "dfroot";
    private static final String DEVICE = "a36xq";
    private static final String FINGERPRINT_PART = "a36xq";
    private static final String KERNEL_PREFIX = "6.6.";
    private static final String KERNEL_KMI = "android15";

    private static volatile Result cached;

    static final class Result {
        final boolean ok;
        final String detail;

        Result(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }
    }

    private TargetGuard() {
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    static Result check(Context ctx) {
        Result result = cached;
        if (result != null) {
            return result;
        }
        String device = safe(Build.DEVICE);
        String fingerprint = safe(Build.FINGERPRINT);
        String kernel = safe(System.getProperty("os.version"));

        boolean deviceOk = DEVICE.equals(device);
        boolean fingerprintOk = fingerprint.contains(FINGERPRINT_PART);
        boolean kernelOk = kernel.startsWith(KERNEL_PREFIX) && kernel.contains(KERNEL_KMI);

        boolean ok = deviceOk && fingerprintOk && kernelOk;
        StringBuilder detail = new StringBuilder();
        detail.append("device=").append(device).append(deviceOk ? "" : " (!= " + DEVICE + ")");
        detail.append(" kernel=").append(kernel.isEmpty() ? "?" : kernel);
        if (!kernelOk) {
            detail.append(" (!= ").append(KERNEL_PREFIX).append("* ").append(KERNEL_KMI).append(")");
        }
        detail.append(" fingerprint=").append(fingerprint.isEmpty() ? "?" : fingerprint);
        result = new Result(ok, detail.toString());
        cached = result;
        Log.i(TAG, "target: ok=" + ok + " " + detail);
        return result;
    }
}
