package df.root;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Hosts the Compose UI. The exploit chain is driven by {@link Runner}; this class only keeps
 * the JNI entry point ({@code Java_df_root_MainActivity_nativeRunAll}) stable and attaches
 * the Compose tree.
 */
public class MainActivity extends AppCompatActivity {

    static native int nativeRunAll(IReporter reporter, int encapPort, int spi,
                                   byte[] aesCbcKey, byte[] hmacKey, int icvLen,
                                   int senderPort, String ksudPath, boolean softReboot);

    static {
        System.loadLibrary("exp");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DfrootUi.attach(this);
    }
}
