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
    private static final String WATCH_NAME = "dfroot-watch.sh";
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

    /**
     * Second, independent watchdog. KernelSU runs service.d once per boot, so
     * this is a separate process with no shared state: if the supervisor wedges
     * or is killed, this restarts it.
     */
    private static final String WATCH_SH =
            "#!/system/bin/sh\n"
                    + "RUN=" + RUN_DIR + "\n"
                    + "LOG=/data/dfroot/log\n"
                    + "mkdir -p \"$RUN\" \"$LOG\" 2>/dev/null\n"
                    + "boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)\n"
                    + "wp=\"$RUN/watch.pid\"\n"
                    + "if [ -f \"$wp\" ]; then\n"
                    + "  read opid oboot < \"$wp\" 2>/dev/null\n"
                    + "  if [ -n \"$opid\" ] && [ \"$oboot\" = \"$boot\" ] && [ -d \"/proc/$opid\" ]; then\n"
                    + "    exit 0\n"
                    + "  fi\n"
                    + "fi\n"
                    + "echo \"$$ $boot\" > \"$wp\"\n"
                    + "while true; do\n"
                    + "  boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)\n"
                    + "  pid=\"\"; pboot=\"\"\n"
                    + "  if [ -f \"$RUN/sup.pid\" ]; then read pid pboot < \"$RUN/sup.pid\" 2>/dev/null; fi\n"
                    + "  if [ -z \"$pid\" ] || [ \"$pboot\" != \"$boot\" ] || [ ! -d \"/proc/$pid\" ]; then\n"
                    + "    setsid sh /data/adb/service.d/dfroot-sup.sh >>\"$LOG/sup.log\" 2>&1 </dev/null &\n"
                    + "    sleep 5\n"
                    + "  fi\n"
                    + "  sleep 60\n"
                    + "done\n";

    /**
     * Root-side keeper. One instance per boot, heartbeat every 30 s, and it is
     * the only component that can still act when the app is force-stopped,
     * disabled by Samsung's app sweeps, or sitting behind a poisoned /dev/df.
     */
    private static final String SUP_SH =
            "#!/system/bin/sh\n"
                    + "export PATH=/system/bin:/system/xbin:/vendor/bin\n"
                    + "umask 022\n"
                    + "RUN=" + RUN_DIR + "\n"
                    + "LOG=/data/dfroot/log\n"
                    + "mkdir -p \"$RUN\" \"$LOG\" /data/adb/service.d 2>/dev/null\n"
                    + "START_BOOT=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)\n"
                    + "pidf=\"$RUN/sup.pid\"\n"
                    + "if [ -f \"$pidf\" ]; then\n"
                    + "  read opid oboot < \"$pidf\" 2>/dev/null\n"
                    + "  if [ -n \"$opid\" ] && [ \"$oboot\" = \"$START_BOOT\" ] && [ -d \"/proc/$opid\" ]; then\n"
                    + "    exit 0\n"
                    + "  fi\n"
                    + "fi\n"
                    + "echo \"$$ $START_BOOT\" > \"$pidf\"\n"
                    + "log() { echo \"$(date +%s) $*\" >> \"$LOG/sup.log\"; }\n"
                    + "rotate() {\n"
                    + "  for f in \"$LOG/sup.log\" \"$LOG/postroot.log\"; do\n"
                    + "    [ -f \"$f\" ] || continue\n"
                    + "    [ \"$(wc -c < \"$f\" 2>/dev/null || echo 0)\" -gt 524288 ] || continue\n"
                    + "    tail -c 262144 \"$f\" > \"$f.tmp\" 2>/dev/null && mv \"$f.tmp\" \"$f\"\n"
                    + "    chmod 644 \"$f\" 2>/dev/null\n"
                    + "  done\n"
                    + "}\n"
                    + "su_ok() { su -c id 2>/dev/null | grep -q 'uid=0'; }\n"
                    + "revive_app() {\n"
                    + "  am start -n df.root/.MainActivity >/dev/null 2>&1\n"
                    + "  am start-foreground-service -n df.root/.KeepService >/dev/null 2>&1\n"
                    + "}\n"
                    + "police_app() {\n"
                    + "  pm enable df.root >/dev/null 2>&1\n"
                    + "  auto=$(cat /data/user_de/0/df.root/files/auto-root 2>/dev/null)\n"
                    + "  [ -n \"$auto\" ] || auto=1\n"
                    + "  if [ \"$auto\" = \"1\" ]; then pm enable df.root/.BootReceiver >/dev/null 2>&1; fi\n"
                    + "  pm enable df.root/.KeepService >/dev/null 2>&1\n"
                    + "  pm enable df.root/.KeepReceiver >/dev/null 2>&1\n"
                    + "  pm grant df.root android.permission.POST_NOTIFICATIONS >/dev/null 2>&1\n"
                    + "  dumpsys deviceidle whitelist +df.root >/dev/null 2>&1\n"
                    + "  cmd appops set df.root RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1\n"
                    + "  cmd appops set df.root RUN_IN_BACKGROUND allow >/dev/null 2>&1\n"
                    + "  cmd appops set df.root START_FOREGROUND allow >/dev/null 2>&1\n"
                    + "  am set-standby-bucket df.root active >/dev/null 2>&1\n"
                    + "  cmd activity set-inactive df.root false >/dev/null 2>&1\n"
                    + "}\n"
                    + "police_manager() {\n"
                    + "  for p in me.weishu.kernelsu com.rifsxd.ksunext com.resukisu.resukisu; do\n"
                    + "    pm list packages 2>/dev/null | grep -qx \"package:$p\" || continue\n"
                    + "    dumpsys deviceidle whitelist +$p >/dev/null 2>&1\n"
                    + "    cmd appops set $p RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1\n"
                    + "    am set-standby-bucket $p active >/dev/null 2>&1\n"
                    + "  done\n"
                    + "}\n"
                    + "clear_poison() {\n"
                    + "  [ -e /dev/df ] || return 0\n"
                    + "  su_ok && return 0\n"
                    + "  log 'poisoned mutex with su dead, clearing'\n"
                    + "  rm -f /dev/df /dev/dfm* 2>/dev/null\n"
                    + "  revive_app\n"
                    + "}\n"
                    + "alive() { [ -f \"$1\" ] && [ -d \"/proc/$(cat \"$1\" 2>/dev/null)\" ]; }\n"
                    + "run_keep() {\n"
                    + "  f=$1\n"
                    + "  [ -f \"$f\" ] || return 0\n"
                    + "  case \"$f\" in *" + SUP_BASE + "*.sh|*dfroot-sup.sh) return 0 ;; esac\n"
                    + "  tag=$(echo \"$f\" | tr / _)\n"
                    + "  pidf2=\"$RUN/pid$tag\"\n"
                    + "  alive \"$pidf2\" && return 0\n"
                    + "  log \"start $f\"\n"
                    + "  setsid sh \"$f\" >>\"$LOG/postroot.log\" 2>&1 </dev/null &\n"
                    + "  echo $! > \"$pidf2\"\n"
                    + "}\n"
                    + "run_once() {\n"
                    + "  f=$1\n"
                    + "  [ -f \"$f\" ] || return 0\n"
                    + "  tag=$(echo \"$f\" | tr / _)\n"
                    + "  stamp=\"$RUN/oneshot-$START_BOOT$tag\"\n"
                    + "  [ -f \"$stamp\" ] && return 0\n"
                    + "  fail=\"${stamp}.retry\"\n"
                    + "  if [ -f \"$fail\" ]; then\n"
                    + "    n=$(cat \"$fail\" 2>/dev/null)\n"
                    + "    if [ -n \"$n\" ] && [ \"$(date +%s)\" -lt \"$n\" ]; then return 0; fi\n"
                    + "  fi\n"
                    + "  log \"once $f\"\n"
                    + "  timeout 45 sh \"$f\" >>\"$LOG/postroot.log\" 2>&1\n"
                    + "  rc=$?\n"
                    + "  log \"once $f rc=$rc\"\n"
                    + "  if [ \"$rc\" -eq 0 ]; then touch \"$stamp\"; else\n"
                    + "    echo $(( $(date +%s) + 300 )) > \"$fail\"\n"
                    + "  fi\n"
                    + "}\n"
                    + "hb=\"$RUN/heartbeat\"\n"
                    + "( while true; do\n"
                    + "    cut -d' ' -f1 /proc/uptime > \"$hb.tmp\" 2>/dev/null && mv \"$hb.tmp\" \"$hb\"\n"
                    + "    chmod 644 \"$hb\" 2>/dev/null\n"
                    + "    sleep 30\n"
                    + "  done ) &\n"
                    + "HBPID=$!\n"
                    + "n=0\n"
                    + "while true; do\n"
                    + "  boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)\n"
                    + "  if [ -n \"$START_BOOT\" ] && [ -n \"$boot\" ] && [ \"$boot\" != \"$START_BOOT\" ]; then\n"
                    + "    log 'boot id changed, exiting'\n"
                    + "    kill $HBPID 2>/dev/null\n"
                    + "    exit 0\n"
                    + "  fi\n"
                    + "  rotate\n"
                    + "  clear_poison\n"
                    + "  pidof df.root >/dev/null 2>&1 || revive_app\n"
                    + "  n=$((n + 1))\n"
                    + "  if [ $((n % 8)) -eq 0 ]; then police_app; police_manager; fi\n"
                    + "  run_keep /data/dfroot/apply.sh\n"
                    + "  for f in /data/dfroot/*.sh; do\n"
                    + "    [ -f \"$f\" ] || continue\n"
                    + "    case \"$f\" in *apply.sh) continue ;; esac\n"
                    + "    case \"$(basename \"$f\")\" in *service*) run_keep \"$f\" ;; *) run_once \"$f\" ;; esac\n"
                    + "  done\n"
                    + "  for f in /data/dfroot/keep.d/*.sh; do run_keep \"$f\"; done\n"
                    + "  sleep 15\n"
                    + "done\n";

    /**
     * @return DEAD, LIVE or BUSY. Never throws, never blocks for more than
     * a couple of short su round-trips.
     */
    static int tick(Context ctx) {
        if (Runner.isClaimed(ctx)) {
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

    static boolean heartbeatHealthy() {
        return heartbeatFresh();
    }

    private static boolean heartbeatFresh() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(HEARTBEAT), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) {
                return false;
            }
            double beat = Double.parseDouble(line.trim());
            double now = BootId.uptime();
            if (now < 0) {
                return false;
            }
            double age = now - beat;
            return age >= -5 && age * 1000.0 < HEARTBEAT_FRESH_MS;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void writeScripts(Context ctx) {
        String name = SUP_BASE + versionCode(ctx) + ".sh";
        Context de = deviceProtected(ctx);
        write(new File(de.getFilesDir(), name), SUP_SH);
        write(new File(de.getFilesDir(), LOADER_NAME), LOADER_SH);
        write(new File(de.getFilesDir(), WATCH_NAME), WATCH_SH);
        if (de != ctx) {
            write(new File(ctx.getFilesDir(), name), SUP_SH);
            write(new File(ctx.getFilesDir(), LOADER_NAME), LOADER_SH);
            write(new File(ctx.getFilesDir(), WATCH_NAME), WATCH_SH);
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
        File watch = new File(de.getFilesDir(), WATCH_NAME);
        if (!watch.isFile()) {
            watch = new File(ctx.getFilesDir(), WATCH_NAME);
        }
        String supPath = sup.getAbsolutePath();
        String loaderPath = loader.getAbsolutePath();
        String watchPath = watch.getAbsolutePath();
        String runName = SUP_BASE + versionCode(ctx) + ".sh";
        return ""
                + "set +e\n"
                + "mkdir -p " + RUN_DIR + " /data/dfroot/log /data/adb/service.d\n"
                + "cp '" + supPath + "' " + RUN_DIR + "/" + runName + "\n"
                + "chmod 755 " + RUN_DIR + "/" + runName + "\n"
                + "cmp -s '" + loaderPath + "' /data/adb/service.d/dfroot-sup.sh 2>/dev/null"
                + " || cp '" + loaderPath + "' /data/adb/service.d/dfroot-sup.sh\n"
                + "chmod 755 /data/adb/service.d/dfroot-sup.sh 2>/dev/null\n"
                + "cmp -s '" + watchPath + "' /data/adb/service.d/dfroot-watch.sh 2>/dev/null"
                + " || cp '" + watchPath + "' /data/adb/service.d/dfroot-watch.sh\n"
                + "chmod 755 /data/adb/service.d/dfroot-watch.sh 2>/dev/null\n"
                + "rm -f /data/adb/service.d/dfroot-keep.sh\n"
                + "setsid sh /data/adb/service.d/dfroot-watch.sh"
                + " >>/data/dfroot/log/sup.log 2>&1 </dev/null &\n"
                + "if [ -f " + RUN_DIR + "/sup.pid ]; then\n"
                + "  read old oboot < " + RUN_DIR + "/sup.pid 2>/dev/null\n"
                + "  now=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)\n"
                + "  if [ -n \"$old\" ] && [ -d \"/proc/$old\" ] && [ \"$oboot\" = \"$now\" ]; then echo running; exit 0; fi\n"
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
