package df.root;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Boot-time entry point. It no longer runs the chain: it only makes sure the
 * keep-alive service is up and enqueues one pass on the dfroot-chain worker.
 * The receiver returns immediately, so no broadcast is held for minutes and
 * the process is not pinned in the receiver state through the whole chain.
 */
public class BootReceiver extends BroadcastReceiver {

    static final String ACTION_BOOT_RETRY = "df.root.action.BOOT_RETRY";

    private static final String TAG = "dfroot";

    static boolean isAutoEnabled(Context context) {
        try {
            int state = context.getPackageManager().getComponentEnabledSetting(
                    new ComponentName(context, BootReceiver.class));
            return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "null" : String.valueOf(intent.getAction());
        Log.i(TAG, "boot: " + action);
        if (!isAutoEnabled(context)) {
            Log.i(TAG, "boot: auto disabled, ignoring " + action);
            return;
        }
        KeepService.start(context.getApplicationContext());
        KeepService.requestChain(context.getApplicationContext(), action);
    }
}
