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
    }
}
