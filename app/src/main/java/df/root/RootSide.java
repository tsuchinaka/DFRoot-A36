package df.root;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * What the root side actually looks like right now, read from the files the
 * supervisor publishes. The app process is not root and cannot ask it
 * anything, so these five paths are the whole observable surface:
 *
 *   /data/dfroot/run/heartbeat   uptime seconds, rewritten every 30 s
 *   /data/dfroot/run/sup.pid     "pid boot_id" of the supervisor
 *   /data/dfroot/run/watch.pid   "pid boot_id" of the watchdog
 *   /sys/module/kernelsu         the module is in the kernel
 *   /dev/df                      the stage-1 mutex of this boot
 */
final class RootSide {

    private static final String TAG = "dfroot";
    private static final String RUN = "/data/dfroot/run";
    private static final String HEARTBEAT = RUN + "/heartbeat";
    private static final String SUP_PID = RUN + "/sup.pid";
    private static final String WATCH_PID = RUN + "/watch.pid";
    private static final String LOADER = "/data/adb/service.d/dfroot-sup.sh";
    private static final long FRESH_MS = 90000L;

    boolean heartbeatFresh;
    long heartbeatAgeSec = -1;
    boolean supervisorAlive;
    boolean watchdogAlive;
    boolean moduleLoaded;
    boolean mutexHeld;
    boolean scriptsInstalled;

    private RootSide() {
    }

    static RootSide empty() {
        return new RootSide();
    }

    static RootSide read(Context ctx) {
        RootSide s = new RootSide();
        s.scriptsInstalled = new File(LOADER).isFile();
        s.moduleLoaded = new File("/sys/module/kernelsu").isDirectory();
        s.mutexHeld = new File("/dev/df").exists();
        s.supervisorAlive = pidMatchesBoot(SUP_PID);
        s.watchdogAlive = pidMatchesBoot(WATCH_PID);

        double beat = readBeat();
        double now = BootId.uptime();
        if (beat >= 0 && now >= 0) {
            double age = now - beat;
            s.heartbeatAgeSec = (long) Math.max(0, age);
            s.heartbeatFresh = age >= -5 && age * 1000.0 < FRESH_MS;
        }
        return s;
    }

    private static double readBeat() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(HEARTBEAT), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? -1 : Double.parseDouble(line.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * The pid file carries the boot id: a recycled pid from a previous boot
     * must not read as "running".
     */
    private static boolean pidMatchesBoot(String path) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(path), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) {
                return false;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2 || !parts[1].equals(BootId.current())) {
                return false;
            }
            return new File("/proc/" + parts[0]).isDirectory();
        } catch (Throwable t) {
            return false;
        }
    }

    boolean any() {
        return scriptsInstalled || moduleLoaded || mutexHeld || supervisorAlive;
    }
}
