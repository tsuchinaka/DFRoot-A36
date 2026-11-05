package df.root;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Runs the post-root scripts the install actually has, and installs a root-side
 * loop that brings this process back if Android kills it.
 *
 * One-shot scripts (post-fs-data, post-mount, boot-completed, other /data/dfroot
 * scripts) run once per boot. apply.sh and service.sh stay supervised: if they
 * exit, the next tick starts them again.
 */
final class PostRoot {

    private static final String TAG = "dfroot";
    private static final String KEEP_NAME = "dfroot-keep.sh";

    private static final String KEEP_SH =
            "#!/system/bin/sh\n"
                    + "pidfile=/data/dfroot/run/keep.pid\n"
                    + "mkdir -p /data/dfroot/run /data/dfroot/log\n"
                    + "if [ -f \"$pidfile\" ]; then\n"
                    + "  old=$(cat \"$pidfile\")\n"
                    + "  if [ -d \"/proc/$old\" ]; then\n"
                    + "    exit 0\n"
                    + "  fi\n"
                    + "fi\n"
                    + "echo $$ > \"$pidfile\"\n"
                    + "dumpsys deviceidle whitelist +df.root >/dev/null 2>&1\n"
                    + "cmd appops set df.root RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1\n"
                    + "cmd appops set df.root RUN_IN_BACKGROUND allow >/dev/null 2>&1\n"
                    + "pm grant df.root android.permission.POST_NOTIFICATIONS >/dev/null 2>&1\n"
                    + "while true; do\n"
                    + "  pidof df.root >/dev/null 2>&1 || am start-foreground-service -n df.root/.KeepService >/dev/null 2>&1\n"
                    + "  sleep 30\n"
                    + "done\n";

    private PostRoot() {
    }

    static void tick(Context ctx) {
        if (Runner.isClaimed()) {
            return;
        }
        if (!RootProbe.probe().live) {
            return;
        }
        writeKeepScript(ctx);
        String files = ctx.getFilesDir().getAbsolutePath();
        String deFiles;
        try {
            deFiles = ctx.createDeviceProtectedStorageContext().getFilesDir().getAbsolutePath();
        } catch (Throwable t) {
            deFiles = files;
        }
        String script = supervisor(files, deFiles);
        String out = Runner.suScript(120, script);
        if (out == null) {
            Log.i(TAG, "postroot: su did not answer");
            return;
        }
        String flat = out.trim().replace('\n', ' ');
        if (flat.length() > 300) {
            flat = flat.substring(0, 300);
        }
        Log.i(TAG, "postroot: " + flat);
    }

    private static void writeKeepScript(Context ctx) {
        write(new File(ctx.getFilesDir(), KEEP_NAME));
        try {
            write(new File(ctx.createDeviceProtectedStorageContext().getFilesDir(), KEEP_NAME));
        } catch (Throwable ignored) {
        }
    }

    private static void write(File dest) {
        try {
            File parent = dest.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return;
            }
            FileOutputStream out = new FileOutputStream(dest, false);
            try {
                out.write(KEEP_SH.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            //noinspection ResultOfMethodCallIgnored
            dest.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            dest.setExecutable(true, false);
        } catch (Throwable ignored) {
        }
    }

    private static String supervisor(String files, String deFiles) {
        return ""
                + "set +e\n"
                + "mkdir -p /data/dfroot/run /data/dfroot/log /data/adb/service.d\n"
                + "exec 9>/data/dfroot/run/tick.lock\n"
                + "if command -v flock >/dev/null 2>&1; then flock -n 9 || exit 0; fi\n"
                + "BOOT=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)\n"
                + "MARK=/data/dfroot/run/oneshot-$BOOT\n"
                + "log() { echo \"$*\" >> /data/dfroot/log/postroot.log; echo \"$*\"; }\n"
                + "alive() { [ -f \"$1\" ] && [ -d \"/proc/$(cat \"$1\")\" ]; }\n"
                + "run_keep() {\n"
                + "  f=$1\n"
                + "  [ -f \"$f\" ] || return 0\n"
                + "  case \"$f\" in */dfroot-keep.sh) return 0 ;; esac\n"
                + "  tag=$(echo \"$f\" | tr / _)\n"
                + "  pidf=/data/dfroot/run/pid$tag\n"
                + "  if alive \"$pidf\"; then return 0; fi\n"
                + "  log \"start $f\"\n"
                + "  setsid sh \"$f\" >>/data/dfroot/log/postroot.log 2>&1 </dev/null &\n"
                + "  echo $! > \"$pidf\"\n"
                + "}\n"
                + "run_once() {\n"
                + "  f=$1\n"
                + "  [ -f \"$f\" ] || return 0\n"
                + "  tag=$(echo \"$f\" | tr / _)\n"
                + "  stamp=$MARK$tag\n"
                + "  [ -f \"$stamp\" ] && return 0\n"
                + "  log \"once $f\"\n"
                + "  timeout 45 sh \"$f\" >>/data/dfroot/log/postroot.log 2>&1\n"
                + "  rc=$?\n"
                + "  log \"once $f rc=$rc\"\n"
                + "  [ \"$rc\" -eq 0 ] && touch \"$stamp\"\n"
                + "}\n"
                + "dumpsys deviceidle whitelist +df.root >/dev/null 2>&1\n"
                + "cmd appops set df.root RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1\n"
                + "cmd appops set df.root RUN_IN_BACKGROUND allow >/dev/null 2>&1\n"
                + "pm grant df.root android.permission.POST_NOTIFICATIONS >/dev/null 2>&1\n"
                + "src=\n"
                + "for c in '" + files + "/" + KEEP_NAME + "' '" + deFiles + "/" + KEEP_NAME + "'; do\n"
                + "  [ -f \"$c\" ] && src=$c\n"
                + "done\n"
                + "if [ -n \"$src\" ]; then\n"
                + "  cp \"$src\" /data/adb/service.d/" + KEEP_NAME + "\n"
                + "  chmod 755 /data/adb/service.d/" + KEEP_NAME + "\n"
                + "  setsid sh /data/adb/service.d/" + KEEP_NAME + " >>/data/dfroot/log/keep.log 2>&1 </dev/null &\n"
                + "fi\n"
                + "run_keep /data/dfroot/apply.sh\n"
                + "for f in /data/dfroot/*.sh; do\n"
                + "  [ -f \"$f\" ] || continue\n"
                + "  case \"$f\" in */apply.sh|*/" + KEEP_NAME + ") continue ;; esac\n"
                + "  base=$(basename \"$f\")\n"
                + "  case \"$base\" in service.sh|*service*) run_keep \"$f\" ;; *) run_once \"$f\" ;; esac\n"
                + "done\n"
                + "for f in /data/adb/post-fs-data.d/*.sh /data/adb/post-mount.d/*.sh; do\n"
                + "  run_once \"$f\"\n"
                + "done\n"
                + "for f in /data/adb/service.d/*.sh; do\n"
                + "  run_keep \"$f\"\n"
                + "done\n"
                + "for f in /data/adb/modules/*/post-fs-data.sh /data/adb/modules/*/post-mount.sh /data/adb/modules/*/boot-completed.sh; do\n"
                + "  run_once \"$f\"\n"
                + "done\n"
                + "for f in /data/adb/modules/*/service.sh; do\n"
                + "  run_keep \"$f\"\n"
                + "done\n"
                + "log tick\n";
    }
}
