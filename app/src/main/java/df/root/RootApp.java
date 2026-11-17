package df.root;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/**
 * Applies Material You dynamic colour when the platform offers it; harmless on
 * devices that do not.
 */
public class RootApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        KgBroadcast.appOnly(this);
        KeepService.start(this);
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
}
