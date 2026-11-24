package df.root;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Applies Material You dynamic colour when the platform offers it; harmless on
 * devices that do not. Also records uncaught crashes into run.log, because a
 * crash on a rooted device with no adb is otherwise invisible.
 */
public class RootApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashLog();
        DynamicColors.applyToActivitiesIfAvailable(this);
        Thread prep = new Thread(() -> {
            try {
                AssetPrep.prepareIfNeeded(this);
            } catch (Throwable t) {
                android.util.Log.w("dfroot", "asset prep: " + t);
            }
        }, "dfroot-prep");
        prep.setDaemon(true);
        prep.start();
    }

    private void installCrashLog() {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                String header = "\n=== crash " + new java.util.Date()
                        + " thread=" + thread.getName() + " ===\n";
                FileOutputStream out = new FileOutputStream(
                        new File(getFilesDir(), "run.log"), true);
                try {
                    out.write((header + sw).getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }
}
