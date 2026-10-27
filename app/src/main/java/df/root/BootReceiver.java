package df.root;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Boot-time entry point. Runs from direct-boot storage so it works before the
 * user unlocks, and retries the chain a few times because a cold boot is the
 * least reliable moment to win the race.
 */
public class BootReceiver extends BroadcastReceiver implements IReporter {

    private static final String TAG = "dfroot";
    private static final int ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 30000;

    private File logFile;

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
        if (logFile == null) {
            return;
        }
        try {
            FileOutputStream out = new FileOutputStream(logFile, true);
            try {
                out.write(msg.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context deCtx = context.createDeviceProtectedStorageContext();
        logFile = new File(deCtx.getFilesDir(), "boot.log");

        if (new File("/dev/df").exists()) {
            Log.i(TAG, "boot: chain already ran; re-applying the overlay");
            final PendingResult pending = goAsync();
            Thread t = new Thread(() -> {
                try {
                    report("boot: " + intent.getAction() + " (chain already ran)\n");
                    Runner.applyOverlay(deCtx, this, 300000L);
                } catch (Throwable e) {
                    report("boot: overlay failed: " + e + "\n");
                } finally {
                    pending.finish();
                }
            }, "dfroot-overlay");
            t.setDaemon(false);
            t.start();
            return;
        }

        if (!Runner.claim()) {
            Log.i(TAG, "boot: a run is already in flight, skipping");
            return;
        }

        final PendingResult pending = goAsync();
        Thread t = new Thread(() -> {
            try {
                report("boot: " + intent.getAction() + "\n");
                for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                    if (new File("/dev/df").exists()) {
                        report("boot: /dev/df present, nothing to do\n");
                        break;
                    }
                    try {
                        int rc = Runner.run(deCtx, this, false);
                        report("boot: attempt " + attempt + " rc=" + rc + "\n");
                        if (rc == 0) {
                            break;
                        }
                        if (attempt < ATTEMPTS) {
                            try {
                                Thread.sleep(RETRY_DELAY_MS);
                            } catch (InterruptedException ignored) {
                            }
                        }
                    } catch (Throwable e) {
                        report("boot: attempt " + attempt + " failed: " + e + "\n");
                    }
                }
            } finally {
                Runner.release();
                pending.finish();
            }
        }, "dfroot-boot");
        t.setDaemon(false);
        t.start();
    }
}
