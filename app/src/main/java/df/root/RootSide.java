package df.root;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * What the root side looks like right now.
 *
 * The app is untrusted_app and cannot read /data/dfroot, /data/adb or most of
 * /sys: SELinux denies those paths, so probing them from here reports "absent"
 * for a perfectly healthy root side. Instead the supervisor — which is root —
 * publishes one line into /dev/dfroot-status, a tmpfs file the app can read:
 *
 *   boot=<id> hb=<uptime> sup=<pid> watch=<pid>
 *   scripts=<0|1> module=<0|1> mutex=<0|1> su=<0|1>
 *
 * The file disappears on reboot, so it can never report a previous boot. When
 * it is missing the state is UNKNOWN, not "absent": the supervisor may simply
 * not have published yet this boot.
 */
final class RootSide {

    private static final String STATUS = "/dev/dfroot-status";
    private static final long FRESH_MS = 90000L;

    boolean published;
    boolean heartbeatFresh;
    long heartbeatAgeSec = -1;
    int supervisorPid;
    int watchdogPid;
    boolean supervisorAlive;
    boolean watchdogAlive;
    boolean moduleLoaded;
    boolean mutexHeld;
    boolean scriptsInstalled;
    boolean rootProcess;

    private RootSide() {
    }

    static RootSide empty() {
        return new RootSide();
    }

    static RootSide read() {
        RootSide s = new RootSide();
        // /dev/df is world-searchable tmpfs: this one path is readable even
        // while everything else is denied.
        s.mutexHeld = new File("/dev/df").exists();

        Map<String, String> map = readStatus();
        String boot = BootId.current();
        if (map.isEmpty() || boot.isEmpty() || !boot.equals(map.get("boot"))) {
            return s;
        }
        s.published = true;
        s.scriptsInstalled = "1".equals(map.get("scripts"));
        s.moduleLoaded = "1".equals(map.get("module"));
        s.rootProcess = "1".equals(map.get("su"));
        s.supervisorPid = (int) parse(map.get("sup"));
        s.watchdogPid = (int) parse(map.get("watch"));
        s.supervisorAlive = s.supervisorPid > 0
                && new File("/proc/" + s.supervisorPid).isDirectory();
        s.watchdogAlive = s.watchdogPid > 0
                && new File("/proc/" + s.watchdogPid).isDirectory();

        double beat = parseDouble(map.get("hb"));
        double now = BootId.uptime();
        if (beat >= 0 && now >= 0) {
            double age = now - beat;
            s.heartbeatAgeSec = (long) Math.max(0, age);
            s.heartbeatFresh = age >= -5 && age * 1000.0 < FRESH_MS;
        }
        return s;
    }

    private static Map<String, String> readStatus() {
        Map<String, String> map = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(STATUS), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) {
                return map;
            }
            for (String token : line.trim().split("\\s+")) {
                int eq = token.indexOf('=');
                if (eq > 0) {
                    map.put(token.substring(0, eq), token.substring(eq + 1));
                }
            }
        } catch (Throwable ignored) {
        }
        return map;
    }

    private static long parse(String value) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value == null ? "" : value.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    boolean any() {
        return published || mutexHeld;
    }
}
