package df.root;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarm and root-side poke. Starts the foreground keep-alive if it is down. */
public class KeepReceiver extends BroadcastReceiver {

    static final String ACTION = "df.root.action.KEEP";

    @Override
    public void onReceive(Context context, Intent intent) {
        KeepService.start(context.getApplicationContext());
    }
}
