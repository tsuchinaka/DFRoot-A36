package df.root;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Root side of the keep-alive.
 *
 * The supervisor runs as one daemon per boot, launched either by KernelSU's
 * service.d at boot or once by this class, and writes a world-readable
 * heartbeat every 30 s. tick() then verifies the root side without paying a
 * single su call while the heartbeat is fresh: one RootProbe per
 * VERIFY_MS instead of a full supervisor script per minute.
 *
 * The daemon installs itself into /data/adb/service.d as a tiny versionless
 * loader that execs the newest /data/dfroot/run/dfroot-sup-<v>.sh, so the
 * running script file is never overwritten in place, and it exits by itself
 * when the boot id changes (a soft reboot starts a fresh instance).
 */
final class PostRoot {

    private static final String TAG = "dfroot";
    private static final String SUP_BASE = "dfroot-sup-";
    private static final String LOADER_NAME = "dfroot-sup-loader.sh";
    private static final String RUN_DIR = "/data/dfroot/run";
    private static final String HEARTBEAT = RUN_DIR + "/heartbeat";
    private static final long HEARTBEAT_FRESH_MS = 90000L;
    private static final long VERIFY_MS = 300000L;

    static final int DEAD = 0;
    static final int LIVE = 1;
    static final int BUSY = 2;

    private static volatile long lastVerify;

    private PostRoot() {
    }

    private static final String LOADER_SH =
            "#!/system/bin/sh\n"
                    + "f=$(ls -t " + RUN_DIR + "/" + SUP_BASE + "*.sh 2>/dev/null | head -n 1)\n"
                    + "[ -n \"$f\" ] && exec sh \"$f\"\n"
                    + "exit 0\n";

    private static final String SUP_SH =
            "#!/system/bin/sh\n"
                    + "# DFRoot supervisor: one instance per boot, heartbeat every 30 s.\n"
                    + "mkdir -p /data/dfroot/run /data/dfroot/log /data/adb/service.d 2>/dev/null\n"
                    + "pidfile=/data/dfroot/run/sup.pid\n"
                    + "if [ -f \"$pidfile\" ]; then\n"
                    + "  old=$(cat \"$pidfile\" 2>/dev/null)\n"
                    + "  if [ -n \"$old\" ] && [ -d \"/proc/$old\" ]; then exit 0; fi\n"
                    + "fi\n"
                    + "echo $$ > \"$pidfile\"\n"
                    + "START_BOOT=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)\n"
                    + "MARK=/data/dfroot/run/oneshot-$START_BOOT\n"
                    + "if [ ! -f \"${MARK}init\" ]; then\n"
                    + "  dumpsys deviceidle whitelist +df.root >/dev/null 2>&1\n"
                    + "  cmd appops set df.root RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1\n"
                    + "  cmd appops set df.root RUN_IN_BACKGROUND allow >/dev/null 2>&1\n"
                    + "  pm grant df.root android.permission.POST_NOTIFICATIONS >/dev/null 2>&1\n"
                    + "  rm -f /data/adb/service.d/dfroot-keep.sh\n"
                    + "  touch \"${MARK}init\"\n"
                    + "fi\n"
                    + "log() { echo \"$(date +%s) $*\" >> /data/dfroot/log/postroot.log; }\n"
                    + "alive() { [ -f \"$1\" ] && [ -d \"/proc/$(cat \"$1\" 2>/dev/null)\" ]; }\n"
                    + "run_keep() {\n"
                    + "  f=$1\n"
                    + "  [ -f \"$f\" ] || return 0\n"
                    + "  case \"$f\" in *" + SUP_BASE + "*.sh|*dfroot-sup.sh|*dfroot-keep.sh) return 0 ;; esac\n"
                    + "  tag=$(echo \"$f\" | tr / _)\n"
                    + "  pidf=/data/dfroot/run/pid$tag\n"
                    + "  alive \"$pidf\" && return 0\n"
                    + "  log \"start $f\"\n"
                    + "  setsid sh \"$f\" >>/data/dfroot/log/postroot.log 2>&1 </dev/null &\n"
                    + "  echo $! > \"$pidf\"\n"
                    + "}\n"
                    + "run_once() {\n"
                    + "  f=$1\n"
                    + "  [ -f \"$f\" ] || return 0\n"
                    + "  tag=$(echo \"$f\" | tr / _)\n"
                    + "  stamp=${MARK}$tag\n"
                    + "  [ -f \"$stamp\" ] && return 0\n"
                    + "  fail=${stamp}.retry\n"
                    + "  if [ -f \"$fail\" ]; then\n"
                    + "    n=$(cat \"$fail\" 2>/dev/null)\n"
                    + "    [ -n \"$n\" ] && [ \"$(date +%s)\" -lt \"$n\" ] && return 0\n"
                    + "  fi\n"
                    + "  log \"once $f\"\n"
                    + "  timeout 45 sh \"$f\" >>/data/dfroot/log/postroot.log 2>&1\n"
                    + "  rc=$?\n"
                    + "  log \"once $f rc=$rc\"\n"
                    + "  if [ \"$rc\" -eq 0 ]; then\n"
                    + "    touch \"$stamp\"\n"
                    + "  else\n"
                    + "    echo $(( $(date +%s) + 300 )) > \"$fail\"\n"
                    + "  fi\n"
                    + "}\n"
                    + "while true; do\n"
                    + "  boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)\n"
                    + "  if [ -n \"$START_BOOT\" ] && [ -n \"$boot\" ] && [ \"$boot\" != \"$START_BOOT\" ]; then\n"
                    + "    log \"boot id changed, exiting\"\n"
                    + "    exit 0\n"
                    + "  fi\n"
                    + "  echo $(date +%s) > " + HEARTBEAT + "\n"
                    + "  chmod 644 " + HEARTBEAT + " 2>/dev/null\n"
                    + "  pidof df.root >/dev/null 2>&1 || am start-foreground-service -n df.root/.KeepService >/dev/null 2>&1\n"
                    + "  run_keep /data/dfroot/apply.sh\n"
                    + "  for f in /data/dfroot/*.sh; do\n"
                    + "    [ -f \"$f\" ] || continue\n"
                    + "    case \"$f\" in *apply.sh|*" + SUP_BASE + "*.sh) continue ;; esac\n"
                    + "    base=$(basename \"$f\")\n"
                    + "    case \"$base\" in service.sh|*service*) run_keep \"$f\" ;; *) run_once \"$f\" ;; esac\n"
                    + "  done\n"
                    + "  for f in /data/adb/post-fs-data.d/*.sh /data/adb/post-mount.d/*.sh; do\n"
                    + "    run_once \"$f\"\n"
                    + "  done\n"
                    + "  for f in /data/adb/service.d/*.sh; do\n"
                    + "    run_keep \"$f\"\n"
                    + "  done\n"
                    + "  for f in /data/adb/modules/*/post-fs-data.sh /data/adb/modules/*/post-mount.sh /data/adb/modules/*/boot-completed.sh; do\n"
                    + "    run_once \"$f\"\n"
                    + "  done\n"
                    + "  for f in /data/adb/modules/*/service.sh; do\n"
                    + "    run_keep \"$f\"\n"
                    + "  done\n"
                    + "  sleep 30\n"
                    + "done\n";

    /**
     * @return DEAD, LIVE or BUSY. Never throws, never blocks for more than
     * a couple of short su round-trips.
     */
    static int tick(Context ctx) {
        if (Runner.isClaimed()) {
            return BUSY;
        }
        long now = SystemClock.elapsedRealtime();
        if (heartbeatFresh()) {
            if (now - lastVerify >= VERIFY_MS) {
                lastVerify = now;
                if (!RootProbe.probe().live) {
                    Log.i(TAG, "postroot: heartbeat present but su refused");
                    return DEAD;
                }
            }
            return LIVE;
        }
        if (!RootProbe.probe().live) {
            return DEAD;
        }
        writeScripts(ctx);
        String out = Runner.suScript(60L, launchScript(ctx));
        Log.i(TAG, "postroot: " + flat(out));
        return out == null ? DEAD : LIVE;
    }

    private static boolean heartbeatFresh() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(HEARTBEAT), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) {
                return false;
            }
            long beat = Long.parseLong(line.trim());
            long age = System.currentTimeMillis() / 1000L - beat;
            return age >= 0 && age * 1000L < HEARTBEAT_FRESH_MS;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void writeScripts(Context ctx) {
        String name = SUP_BASE + versionCode(ctx) + ".sh";
        Context de = deviceProtected(ctx);
        write(new File(de.getFilesDir(), name), SUP_SH);
        write(new File(de.getFilesDir(), LOADER_NAME), LOADER_SH);
        if (de != ctx) {
            write(new File(ctx.getFilesDir(), name), SUP_SH);
            write(new File(ctx.getFilesDir(), LOADER_NAME), LOADER_SH);
        }
    }

    private static Context deviceProtected(Context ctx) {
        try {
            return ctx.createDeviceProtectedStorageContext();
        } catch (Throwable t) {
            return ctx;
        }
    }

    private static String launchScript(Context ctx) {
        Context de = deviceProtected(ctx);
        String name = SUP_BASE + versionCode(ctx) + ".sh";
        File sup = new File(de.getFilesDir(), name);
        if (!sup.isFile()) {
            sup = new File(ctx.getFilesDir(), name);
        }
        File loader = new File(de.getFilesDir(), LOADER_NAME);
        if (!loader.isFile()) {
            loader = new File(ctx.getFilesDir(), LOADER_NAME);
        }
        String supPath = sup.getAbsolutePath();
        String loaderPath = loader.getAbsolutePath();
        String runName = SUP_BASE + versionCode(ctx) + ".sh";
        return ""
                + "set +e\n"
                + "mkdir -p " + RUN_DIR + " /data/dfroot/log /data/adb/service.d\n"
                + "cp '" + supPath + "' " + RUN_DIR + "/" + runName + "\n"
                + "chmod 755 " + RUN_DIR + "/" + runName + "\n"
                + "cmp -s '" + loaderPath + "' /data/adb/service.d/dfroot-sup.sh 2>/dev/null"
                + " || cp '" + loaderPath + "' /data/adb/service.d/dfroot-sup.sh\n"
                + "chmod 755 /data/adb/service.d/dfroot-sup.sh 2>/dev/null\n"
                + "rm -f /data/adb/service.d/dfroot-keep.sh\n"
                + "if [ -f " + RUN_DIR + "/sup.pid ]; then\n"
                + "  old=$(cat " + RUN_DIR + "/sup.pid 2>/dev/null)\n"
                + "  if [ -n \"$old\" ] && [ -d \"/proc/$old\" ]; then echo running; exit 0; fi\n"
                + "fi\n"
                + "setsid sh /data/adb/service.d/dfroot-sup.sh"
                + " >>/data/dfroot/log/sup.log 2>&1 </dev/null &\n"
                + "echo launched\n";
    }

    private static int versionCode(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void write(File dest, String content) {
        try {
            File parent = dest.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return;
            }
            File tmp = new File(dest.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp, false);
            try {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(dest)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            dest.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            dest.setExecutable(true, false);
        } catch (Throwable ignored) {
        }
    }

    private static String flat(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "no-reply";
        }
        String flat = value.trim().replace('\n', ' ');
        return flat.length() > 300 ? flat.substring(0, 300) : flat;
    }
}
