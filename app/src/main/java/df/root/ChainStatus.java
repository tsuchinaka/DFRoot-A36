package df.root;

import android.content.Context;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Read-only view of the chain state BootChain persists in device-protected
 * storage. The UI runs in a different process than the chain, so this file is
 * the only channel: without it "needs-reboot" and "no-allowlist" look exactly
 * like "not rooted".
 */
final class ChainStatus {

    static final String STAGE_FILE = "root-stage";
    static final int NO_RC = -999;

    String stage = "";
    int fails;
    int lastRc = NO_RC;
    long lastMs;
    long lastAt;
    String lastAct = "";
    long retryAt;
    boolean sameBoot;
    long readAt;

    private ChainStatus() {
    }

    static ChainStatus empty() {
        ChainStatus s = new ChainStatus();
        s.readAt = SystemClock.elapsedRealtime();
        return s;
    }

    static ChainStatus read(Context ctx) {
        ChainStatus s = new ChainStatus();
        s.readAt = SystemClock.elapsedRealtime();
        Map<String, String> map = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(new File(dir(ctx), STAGE_FILE)),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    map.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
        } catch (Throwable ignored) {
        }
        String boot = BootId.current();
        s.sameBoot = !boot.isEmpty() && boot.equals(map.getOrDefault("boot_id", ""));
        if (!s.sameBoot) {
            return s;
        }
        s.stage = map.getOrDefault("stage", "");
        s.fails = (int) parse(map.get("fails"));
        s.lastRc = (int) parse(map.get("last_rc"));
        s.lastMs = parse(map.get("last_ms"));
        s.lastAt = parse(map.get("last_at"));
        s.lastAct = map.getOrDefault("last_act", "");
        s.retryAt = parse(map.get("retry_at"));
        return s;
    }

    private static File dir(Context ctx) {
        try {
            return ctx.createDeviceProtectedStorageContext().getFilesDir();
        } catch (Throwable t) {
            return ctx.getFilesDir();
        }
    }

    private static long parse(String value) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    boolean rooted() {
        return "rooted".equals(stage);
    }

    boolean needsReboot() {
        return "needs-reboot".equals(stage);
    }

    boolean noAllowlist() {
        return "no-allowlist".equals(stage);
    }

    boolean unsupported() {
        return "unsupported".equals(stage);
    }

    boolean stalled() {
        return fails >= 6;
    }

    boolean hasRun() {
        return lastRc != NO_RC;
    }

    /** Milliseconds until the armed retry, or -1 when none is armed. */
    long retryInMs() {
        if (retryAt <= 0) {
            return -1L;
        }
        return Math.max(0L, retryAt - SystemClock.elapsedRealtime());
    }
}
