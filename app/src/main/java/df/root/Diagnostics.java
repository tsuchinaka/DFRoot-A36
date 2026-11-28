package df.root;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** One text bundle with everything needed to debug a device remotely. */
final class Diagnostics {

    private Diagnostics() {
    }

    static String build(Context ctx) {
        StringBuilder out = new StringBuilder(16384);
        out.append("DFRoot diagnostics\n");
        out.append("app: ").append(versionName(ctx)).append(" (").append(versionCode(ctx)).append(")\n");
        out.append("device: ").append(Build.DEVICE).append(" model=").append(Build.MODEL)
                .append(" product=").append(Build.PRODUCT).append('\n');
        out.append("fingerprint: ").append(Build.FINGERPRINT).append('\n');
        out.append("android: ").append(Build.VERSION.RELEASE).append(" sdk=")
                .append(Build.VERSION.SDK_INT).append('\n');
        out.append("kernel: ").append(System.getProperty("os.version")).append('\n');
        out.append("boot_id: ").append(BootId.current()).append('\n');
        out.append("uptime_s: ").append(BootId.uptime()).append('\n');
        TargetGuard.Result target = TargetGuard.check(ctx);
        out.append("target: ok=").append(target.ok).append(' ').append(target.detail).append('\n');
        out.append("kg_enabled: ").append(KgWatchdog.enabled(ctx)).append('\n');
        out.append("kg_state: ").append(TextParse.kgStateName(TextParse.kgState(Probes.prop("knox.kg.state")))).append(" raw=").append(Probes.prop("knox.kg.state")).append('\n');
        out.append("boot_completed: ").append(Probes.prop("sys.boot_completed")).append('\n');
        out.append("rooted_this_boot: ").append(RootRecord.rootedThisBoot(ctx)).append('\n');
        ChainStatus chain = ChainStatus.read(ctx);
        out.append("chain: stage=").append(chain.stage.isEmpty() ? "-" : chain.stage)
                .append(" fails=").append(chain.fails)
                .append(" last_rc=").append(chain.lastRc)
                .append(" last_ms=").append(chain.lastMs)
                .append(" last_act=").append(chain.lastAct)
                .append(" retry_in_ms=").append(chain.retryInMs())
                .append(" same_boot=").append(chain.sameBoot).append('\n');
        RootSide side = RootSide.read();
        out.append("root_side: published=").append(side.published)
                .append(" heartbeat=").append(side.heartbeatFresh)
                .append(" age_s=").append(side.heartbeatAgeSec)
                .append(" supervisor=").append(side.supervisorAlive)
                .append(" watchdog=").append(side.watchdogAlive)
                .append(" module=").append(side.moduleLoaded)
                .append(" mutex=").append(side.mutexHeld)
                .append(" scripts=").append(side.scriptsInstalled).append('\n');
        KgWatchdog.Last kgLast = KgWatchdog.last(ctx);
        out.append("kg_last: known=").append(kgLast.known)
                .append(" pre=").append(kgLast.pre)
                .append(" unlock=").append(kgLast.unlock)
                .append(" err=").append(kgLast.err)
                .append(" post=").append(kgLast.post)
                .append(" parked=").append(kgLast.parked).append('\n');
        out.append("stats: ").append(Stats.snapshot()).append('\n');
        RootProbe.Result probe = RootProbe.probe();
        out.append("su: live=").append(probe.live).append(" detail=").append(probe.detail).append('\n');
        out.append('\n').append(RootProbe.diagnose()).append('\n');
        out.append("\n== root-stage ==\n").append(tail(new File(
                deviceProtected(ctx).getFilesDir(), "root-stage"), 4096)).append('\n');
        out.append("\n== boot.log ==\n").append(tail(new File(
                deviceProtected(ctx).getFilesDir(), "boot.log"), 65536)).append('\n');
        out.append("\n== run.log ==\n").append(tail(new File(ctx.getFilesDir(), "run.log"), 131072)).append('\n');
        out.append("\n== sup.log ==\n").append(tail(new File("/data/dfroot/log/sup.log"), 32768)).append('\n');
        out.append("\n== root-side status ==\n").append(tail(new File("/dev/dfroot-status"), 4096)).append('\n');
        if (out.length() > 262144) {
            return out.substring(out.length() - 262144);
        }
        return out.toString();
    }

    private static Context deviceProtected(Context ctx) {
        try {
            return ctx.createDeviceProtectedStorageContext();
        } catch (Throwable t) {
            return ctx;
        }
    }

    private static String tail(File file, int maxBytes) {
        if (!file.isFile()) {
            return "(missing)";
        }
        try (FileInputStream in = new FileInputStream(file)) {
            long size = in.getChannel().size();
            long skip = Math.max(0, size - maxBytes);
            //noinspection ResultOfMethodCallIgnored
            in.getChannel().position(skip);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
            return out.toString();
        } catch (Throwable t) {
            return "(unreadable: " + t + ")";
        }
    }

    private static String versionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static int versionCode(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }
}
