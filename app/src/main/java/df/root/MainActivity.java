package df.root;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.loadingindicator.LoadingIndicator;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Single-screen controller. It only drives the chain and reports: all of the
 * exploit work lives in {@link Runner} and the native library.
 */
public class MainActivity extends AppCompatActivity implements IReporter {

    private static final String TAG = "dfroot";
    private static final Pattern ANSI = Pattern.compile("\u001b\\[[0-9;]*m");
    private static final int LOG_LIMIT = 200000;
    private static final long PROBE_MIN_INTERVAL_MS = 5000;
    private static final long ROOT_WAIT_MS = 180000;

    /** name, GitHub handle, repository/URL. */
    private static final String[][] CREDITS = {
            {"Tsuchinaka", "@tsuchinaka", "https://github.com/tsuchinaka"},
            {"diabl0w", "@diabl0w", "https://github.com/diabl0w/DFRoot"},
            {"LSPosed — LSPromise", "@LSPosed", "https://github.com/LSPosed/LSPromise"},
            {"polygraphene — DFReroot", "@polygraphene", "https://github.com/polygraphene/DFReroot"},
            {"combeng6th — DirtyInit", "@combeng6th", "https://github.com/combeng6th/DirtyInit"},
            {"tiann — KernelSU", "@tiann", "https://github.com/tiann/KernelSU"},
            {"rushiranpise — Root-My-Galaxy-Next", "@rushiranpise", "https://github.com/rushiranpise/Root-My-Galaxy-Next"},
            {"BuSung-dev — Root-My-Galaxy-Payloads", "@BuSung-dev", "https://github.com/BuSung-dev/Root-My-Galaxy-Payloads"},
            {"marin-m — vmlinux-to-elf", "@marin-m", "https://github.com/marin-m/vmlinux-to-elf"},
    };

    static native int nativeRunAll(IReporter reporter, int encapPort, int spi,
                                   byte[] aesCbcKey, byte[] hmacKey, int icvLen,
                                   int senderPort, String ksudPath, boolean softReboot);

    static {
        System.loadLibrary("exp");
    }

    private View rootView;
    private View settingsSection;
    private View logSection;
    private View aboutSection;
    private BottomNavigationView bottomNav;
    private ViewGroup creditsContainer;
    private MaterialButton runButton;
    private LoadingIndicator loadingIndicator;
    private MaterialSwitch autoSwitch;
    private TextView statusView;
    private TextView grantHint;
    private TextView logView;
    private NestedScrollView logScroll;
    private File logFile;

    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
    private volatile RootProbe.Result suResult;
    private volatile boolean probing;
    private long lastProbeAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        logFile = new File(getFilesDir(), "run.log");

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        rootView = findViewById(R.id.root);
        settingsSection = findViewById(R.id.settingsSection);
        logSection = findViewById(R.id.logSection);
        aboutSection = findViewById(R.id.aboutSection);
        bottomNav = findViewById(R.id.bottomNav);
        creditsContainer = findViewById(R.id.creditsContainer);
        runButton = findViewById(R.id.runButton);
        loadingIndicator = findViewById(R.id.loadingIndicator);
        autoSwitch = findViewById(R.id.autoSwitch);
        statusView = findViewById(R.id.statusText);
        grantHint = findViewById(R.id.grantHint);
        logView = findViewById(R.id.logView);
        logScroll = findViewById(R.id.logScroll);

        applyWindowInsets();

        runButton.setOnClickListener(v -> startRun());
        autoSwitch.setOnCheckedChangeListener(this::onAutoToggled);

        bottomNav.setOnItemSelectedListener(item -> {
            showSection(item.getItemId());
            return true;
        });
        bottomNav.setSelectedItemId(R.id.nav_settings);

        populateCredits();

        Log.i(TAG, RootProbe.diagnose());
        refreshStatus();

        if (getIntent() != null && getIntent().getBooleanExtra("autorun", false)) {
            startRun();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        probeExecutor.shutdownNow();
        super.onDestroy();
    }

    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
    }

    private void showSection(int itemId) {
        settingsSection.setVisibility(itemId == R.id.nav_settings ? View.VISIBLE : View.GONE);
        logSection.setVisibility(itemId == R.id.nav_log ? View.VISIBLE : View.GONE);
        aboutSection.setVisibility(itemId == R.id.nav_about ? View.VISIBLE : View.GONE);
    }

    private void populateCredits() {
        LayoutInflater inflater = LayoutInflater.from(this);
        for (String[] credit : CREDITS) {
            View row = inflater.inflate(R.layout.item_credit, creditsContainer, false);
            String handle = credit[1];
            ((TextView) row.findViewById(R.id.creditMonogram))
                    .setText(handle.substring(1, 2).toUpperCase());
            ((TextView) row.findViewById(R.id.creditName)).setText(credit[0]);
            ((TextView) row.findViewById(R.id.creditHandle)).setText(handle);
            row.setOnClickListener(v -> openUrl(credit[2]));
            creditsContainer.addView(row);
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Snackbar.make(rootView, url, Snackbar.LENGTH_SHORT).show();
        }
    }

    private String statusText() {
        RootProbe.Result probed = suResult;
        boolean chainRan = chainRan();
        String rootState;
        if (probed == null) {
            rootState = getString(R.string.root_checking);
        } else if (probed.live) {
            rootState = getString(R.string.root_live);
        } else if (chainRan) {
            rootState = getString(R.string.root_not_granted);
        } else {
            rootState = getString(R.string.root_inactive);
        }
        return getString(R.string.status_kernel) + ": " + System.getProperty("os.version") + "\n"
                + getString(R.string.status_device) + ": " + Build.MODEL + "\n"
                + getString(R.string.status_root) + ": " + rootState + "\n"
                + getString(R.string.status_su) + ": " + suDetail(probed) + "\n"
                + getString(R.string.status_manager) + ": " + managerState();
    }

    private boolean chainRan() {
        return new File("/dev/df").exists() || RootRecord.rootedThisBoot(this);
    }

    private String managerState() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo("me.weishu.kernelsu", 0);
            return getString(R.string.manager_present, info.versionName);
        } catch (Throwable t) {
            return getString(R.string.manager_absent);
        }
    }

    private String suDetail(RootProbe.Result probed) {
        if (probed == null) {
            return getString(R.string.su_checking);
        }
        if (probed.live) {
            return getString(R.string.su_ok);
        }
        return getString(R.string.su_refused, probed.detail);
    }

    private void refreshStatus() {
        String status = statusText();
        statusView.setText(status);
        Log.i(TAG, "status:\n" + status);

        boolean showHint = suResult != null && !suResult.live && chainRan();
        grantHint.setVisibility(showHint ? View.VISIBLE : View.GONE);

        autoSwitch.setOnCheckedChangeListener(null);
        autoSwitch.setChecked(isAutoEnabled());
        autoSwitch.setOnCheckedChangeListener(this::onAutoToggled);

        probeRootIfStale();
    }

    private void probeRootIfStale() {
        long now = SystemClock.elapsedRealtime();
        if (probing || now - lastProbeAt < PROBE_MIN_INTERVAL_MS) {
            return;
        }
        probing = true;
        lastProbeAt = now;
        probeExecutor.execute(() -> {
            RootProbe.Result result = RootProbe.probe();
            probing = false;
            runOnUiThread(() -> {
                suResult = result;
                refreshStatus();
            });
        });
    }

    private void invalidateProbe() {
        lastProbeAt = 0L;
    }

    private boolean isAutoEnabled() {
        return getPackageManager().getComponentEnabledSetting(
                new ComponentName(this, BootReceiver.class)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private void onAutoToggled(CompoundButton button, boolean checked) {
        getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, BootReceiver.class),
                checked ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        report(getString(checked ? R.string.auto_enabled : R.string.auto_disabled) + "\n");
    }

    private void startRun() {
        if (!Runner.claim()) {
            Snackbar.make(rootView, R.string.already_running, Snackbar.LENGTH_LONG).show();
            return;
        }
        runButton.setEnabled(false);
        runButton.setText(R.string.action_running);
        loadingIndicator.setVisibility(View.VISIBLE);
        bottomNav.setSelectedItemId(R.id.nav_log);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        logView.setText("");
        try {
            FileOutputStream out = new FileOutputStream(logFile, false);
            try {
                out.write(new byte[0]);
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
        }

        report("=== DFRoot a36xq / DirtyFrag CVE-2026-43284 ===\n");

        Thread t = new Thread(this::runExploit, TAG);
        t.setDaemon(false);
        t.start();
    }

    private void runExploit() {
        long startedAt = System.currentTimeMillis();
        try {
            int rc = Runner.run(this, this, false);
            report("\nRESULT rc=" + rc + "\n");

            if (rc == 0) {
                RootRecord.markRooted(this);
                report(getString(R.string.waiting_for_install) + "\n");
                long lastNote = 0;
                while (System.currentTimeMillis() - startedAt < ROOT_WAIT_MS) {
                    RootProbe.Result probed = RootProbe.probe();
                    if (probed.live) {
                        report(getString(R.string.su_ok) + "\n");
                        break;
                    }
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (System.currentTimeMillis() - lastNote > 20000) {
                        lastNote = System.currentTimeMillis();
                        report(getString(R.string.still_installing,
                                (System.currentTimeMillis() - startedAt) / 1000) + "\n");
                    }
                }
            }

            RootProbe.Result finalProbe = RootProbe.probe();
            invalidateProbe();
            finishRun(rc, getString(resultMessage(rc, finalProbe.live)));
        } catch (Throwable e) {
            Log.e(TAG, "exploit exception", e);
            report("\nexception: " + e + "\n");
            finishRun(-1, getString(R.string.result_exception, String.valueOf(e)));
        }
    }

    private int resultMessage(int rc, boolean live) {
        if (live) {
            return R.string.result_success;
        }
        if (rc == 0) {
            return R.string.result_installed_not_granted;
        }
        if (rc == 1) {
            return R.string.result_ksud_failed;
        }
        return R.string.result_patch_failed;
    }

    private void finishRun(int rc, String message) {
        runOnUiThread(() -> {
            runButton.setEnabled(true);
            runButton.setText(rc == 0 ? R.string.action_jailbreak_again : R.string.action_jailbreak);
            loadingIndicator.setVisibility(View.GONE);
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            suResult = null;
            refreshStatus();
            Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT).show();
        });
        Runner.release();
    }

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
        try {
            FileOutputStream out = new FileOutputStream(logFile, true);
            try {
                out.write(msg.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
        }

        String shown = ANSI.matcher(msg).replaceAll("");
        runOnUiThread(() -> {
            if (logView == null) {
                return;
            }
            if (logView.length() > LOG_LIMIT) {
                logView.setText(logView.getText()
                        .subSequence(logView.length() - 100000, logView.length()));
            }
            logView.append(shown);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
}
