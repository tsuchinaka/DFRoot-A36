package df.root;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Hosts the Compose UI. The exploit chain is driven by {@link Runner}; this
 * class only attaches the Compose tree.
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (BootReceiver.isAutoEnabled(this)) {
            KeepService.start(this);
        }
        KeepService.requestExemptions(this);
        DfrootUi.attach(this);
    }
}
