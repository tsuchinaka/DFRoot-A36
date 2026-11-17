package df.root;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Alarm entry points: the keep alarm restarts the service, the retry alarm
 * enqueues one chain pass on the worker.
 */
public class KeepReceiver extends BroadcastReceiver {

    static final String ACTION = "df.root.action.KEEP";
    static final String ACTION_CHAIN = "df.root.action.CHAIN_RETRY";

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CHAIN.equals(action)) {
            KeepService.requestChain(app, "alarm");
        } else {
            KeepService.start(app);
        }
    }
}
