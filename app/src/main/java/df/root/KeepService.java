package df.root;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the keep-alive and the auto-root retries.
 *
 * BootReceiver and the alarm only enqueue chain passes here; the single
 * dfroot-chain worker runs them inside the foreground service, so a pass is
 * never frozen or killed mid-exploit the way a long-lived broadcast thread
 * can be. The keep worker adapts its cadence: 30 s without root, 120 s with
 * a healthy root side, 15 s while a pass is in flight, and it fires a chain
 * pass the moment a live root is observed to be gone.
 */
public class KeepService extends Service {

    private static final String TAG = "dfroot";
    private static final String CHANNEL = "dfroot-keep";
    private static final String EXTRA_CHAIN = "dfroot.extra.CHAIN_ACTION";
    private static final int NOTIF_ID = 7;
    private static final int KEEP_ALARM_CODE = 1002;
    private static final int RETRY_ALARM_CODE = 1003;
    private static final long KEEP_ALARM_MS = 180000L;
    private static final long KEEP_ALARM_ROOTED_MS = 600000L;
    private static final long TICK_NO_ROOT_MS = 30000L;
    private static final long TICK_NO_ROOT_SLOW_MS = 300000L;
    private static final long TICK_ROOTED_MS = 120000L;
    private static final long TICK_BUSY_MS = 15000L;
    private static final long TICK_MIN_GAP_MS = 10000L;
    private static final long CHAIN_MIN_GAP_MS = 60000L;
    private static final long CHAIN_REQ_DEBOUNCE_MS = 5000L;
    private static final long RETRY_GRACE_MS = 30000L;

    private static final Object GATE = new Object();
    private static final ExecutorService CHAIN = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dfroot-chain");
        t.setDaemon(true);
        return t;
    });

    /**
     * The KG pass runs one to three su round-trips with a 90 s timeout each.
     * It must not run on the keep tick: a doze mid-wait would stall the healthy
     * heartbeat loop for minutes and leave a zombie su behind every cycle.
     */
    private static final ExecutorService KG = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dfroot-kg");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicBoolean KG_QUEUED = new AtomicBoolean();

    private static final AtomicBoolean CHAIN_QUEUED = new AtomicBoolean();
    private static volatile boolean running;
    private static volatile boolean knownLive;
    private static volatile long lastChainRequest;
    private static volatile long lastTickAt;
    private static Thread worker;
    private volatile boolean notifRooted;

    static void start(Context ctx) {
        Intent i = new Intent(ctx, KeepService.class);
        try {
            ctx.startForegroundService(i);
        } catch (Throwable t) {
            Log.w(TAG, "keep start fg: " + t);
            try {
                ctx.startService(i);
            } catch (Throwable t2) {
                Log.w(TAG, "keep start: " + t2);
            }
        }
        if (shouldKeep(ctx)) {
            scheduleKeep(ctx, currentKeepDelay());
        }
    }

    static void stopIfIdle(Context ctx) {
        if (Runner.isClaimed(ctx) || knownLive || CHAIN_QUEUED.get()
                || BootReceiver.isAutoEnabled(ctx) || PostRoot.heartbeatHealthy()) {
            return;
        }
        Log.i(TAG, "keep: idle with auto-root off, stopping");
        try {
            ctx.stopService(new Intent(ctx, KeepService.class));
        } catch (Throwable ignored) {
        }
    }

    private static boolean shouldKeep(Context ctx) {
        return knownLive || BootReceiver.isAutoEnabled(ctx);
    }

    /**
     * Enqueue one chain pass; serialized on the dfroot-chain worker.
     *
     * KeepService now lives in :chain, so the queued flag cannot be the
     * cross-process mutex any more: it is set and cleared inside the service
     * process and the caller only rate-limits. Duplicate pokes (SCREEN_ON and
     * USER_PRESENT for the same unlock) collapse here; a real pass is still
     * serialized by BootChain's pass lock and Runner's file claim.
     */
    static void requestChain(Context ctx, String action) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastChainRequest < CHAIN_REQ_DEBOUNCE_MS) {
            Log.i(TAG, "chain: request inside debounce, coalesced");
            return;
        }
        lastChainRequest = now;
        Intent i = new Intent(ctx, KeepService.class)
                .putExtra(EXTRA_CHAIN, action == null ? "boot" : action);
        try {
            ctx.startForegroundService(i);
        } catch (Throwable t) {
            try {
                ctx.startService(i);
            } catch (Throwable t2) {
                Log.w(TAG, "chain start: " + t2);
            }
        }
    }

    /** The only retry alarm in the app. */
    static void scheduleRetry(Context ctx, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(ALARM_SERVICE);
            if (am == null) {
                return;
            }
            Intent i = new Intent(ctx, KeepReceiver.class).setAction(KeepReceiver.ACTION_CHAIN);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, RETRY_ALARM_CODE, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long at = SystemClock.elapsedRealtime() + delayMs;
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } catch (Throwable exactDenied) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Throwable ignored) {
        }
    }

    static void cancelKeep(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(ALARM_SERVICE);
            Intent i = new Intent(ctx, KeepReceiver.class).setAction(KeepReceiver.ACTION);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, KEEP_ALARM_CODE, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (am != null) {
                am.cancel(pi);
            }
            pi.cancel();
        } catch (Throwable ignored) {
        }
    }

    static void cancelRetry(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(ALARM_SERVICE);
            Intent i = new Intent(ctx, KeepReceiver.class).setAction(KeepReceiver.ACTION_CHAIN);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, RETRY_ALARM_CODE, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (am != null) {
                am.cancel(pi);
            }
            pi.cancel();
        } catch (Throwable ignored) {
        }
    }

    /** Notification access prompt, once per version. Battery is asked from
     * the auto-root switch instead of on every launch. */
    static void requestExemptions(Activity activity) {
        try {
            SharedPreferences sp = activity.getSharedPreferences("dfroot", MODE_PRIVATE);
            int version = versionCode(activity);
            if (sp.getInt("notif_prompted_version", -1) >= version) {
                return;
            }
            if (ContextCompat.checkSelfPermission(activity,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
            sp.edit().putInt("notif_prompted_version", version).apply();
        } catch (Throwable ignored) {
        }
    }

    static void requestBatteryExemption(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName())) {
                return;
            }
            SharedPreferences sp = ctx.getSharedPreferences("dfroot", MODE_PRIVATE);
            int version = versionCode(ctx);
            if (sp.getInt("battery_prompted_version", -1) >= version) {
                return;
            }
            sp.edit().putInt("battery_prompted_version", version).apply();
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private static int versionCode(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void scheduleKeep(Context ctx, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(ALARM_SERVICE);
            if (am == null) {
                return;
            }
            Intent i = new Intent(ctx, KeepReceiver.class).setAction(KeepReceiver.ACTION);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, KEEP_ALARM_CODE, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long at = SystemClock.elapsedRealtime() + delayMs;
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } catch (Throwable exactDenied) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        promote();
        registerPokes();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promote();
        if (shouldKeep(this)) {
            scheduleKeep(this, currentKeepDelay());
        }
        String chain = intent == null ? null : intent.getStringExtra(EXTRA_CHAIN);
        if (chain != null) {
            final String action = chain;
            final Context app = this;
            CHAIN_QUEUED.set(true);
            CHAIN.execute(() -> {
                try {
                    BootChain.pass(app, action);
                } catch (Throwable t) {
                    Log.w(TAG, "chain: " + t);
                } finally {
                    CHAIN_QUEUED.set(false);
                }
            });
        } else if (!shouldKeep(this)) {
            stopIfIdle(this);
            return START_NOT_STICKY;
        }
        ensureWorker();
        poke();
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (shouldKeep(this)) {
            scheduleKeep(this, currentKeepDelay());
            start(this);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        CHAIN_QUEUED.set(false);
        poke();
        if (shouldKeep(this)) {
            scheduleKeep(this, currentKeepDelay());
        }
        try {
            unregisterReceiver(pokeReceiver);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void promote() {
        promote(notifRooted);
    }

    private void promote(boolean rooted) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, getString(R.string.keep_channel), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(getString(R.string.keep_title))
                    .setContentText(getString(rooted
                            ? R.string.keep_text_rooted : R.string.keep_text_waiting))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "keep foreground: " + t);
        }
    }

    private final android.content.BroadcastReceiver pokeReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            poke();
        }
    };

    private void registerPokes() {
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(pokeReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable ignored) {
        }
    }

    private void ensureWorker() {
        running = true;
        if (worker != null && worker.isAlive()) {
            return;
        }
        worker = new Thread(() -> {
            while (running) {
                long delay;
                try {
                    long since = SystemClock.elapsedRealtime() - lastTickAt;
                    if (lastTickAt > 0 && since < TICK_MIN_GAP_MS) {
                        // SCREEN_ON and USER_PRESENT poke the worker; do not
                        // pay two su probes for the same event.
                        delay = TICK_MIN_GAP_MS - since;
                    } else {
                        lastTickAt = SystemClock.elapsedRealtime();
                        if (Runner.isClaimed(this)) {
                            delay = TICK_BUSY_MS;
                        } else {
                            int status = PostRoot.tick(this);
                            if (status == PostRoot.DEAD) {
                                updateNotification(false);
                                if (knownLive) {
                                    knownLive = false;
                                    BootChain.noteRootLost();
                                    Log.i(TAG, "keep: root dropped, requesting chain");
                                    requestChain(this, "su-drop");
                                } else if (BootReceiver.isAutoEnabled(this) && chainIdle()) {
                                    requestChain(this, "tick");
                                }
                                if (!BootReceiver.isAutoEnabled(this) && !knownLive
                                        && !CHAIN_QUEUED.get()) {
                                    Log.i(TAG, "keep: no root and auto-root off, stopping");
                                    cancelKeep(this);
                                    cancelRetry(this);
                                    stopSelf();
                                    return;
                                }
                                // Without auto-root there is nothing to gain
                                // from a fast cadence: relax to 5 minutes.
                                delay = BootReceiver.isAutoEnabled(this)
                                        ? TICK_NO_ROOT_MS : TICK_NO_ROOT_SLOW_MS;
                            } else {
                                knownLive = true;
                                updateNotification(true);
                                if (status == PostRoot.LIVE
                                        && KG_QUEUED.compareAndSet(false, true)) {
                                    KG.execute(() -> {
                                        try {
                                            KgWatchdog.maybeFix(KeepService.this);
                                        } catch (Throwable t) {
                                            Log.w(TAG, "kg: " + t);
                                        } finally {
                                            KG_QUEUED.set(false);
                                        }
                                    });
                                }
                                delay = status == PostRoot.BUSY
                                        ? TICK_BUSY_MS : TICK_ROOTED_MS;
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "keep tick: " + t);
                    delay = TICK_NO_ROOT_MS;
                }
                synchronized (GATE) {
                    try {
                        GATE.wait(delay);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
            }
        }, "dfroot-keep");
        worker.setDaemon(false);
        worker.start();
    }

    private static boolean chainIdle() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastChainRequest < CHAIN_MIN_GAP_MS) {
            return false;
        }
        long next = BootChain.nextRetryAt;
        return next == 0L || now > next + RETRY_GRACE_MS;
    }

    private static long currentKeepDelay() {
        return knownLive ? KEEP_ALARM_ROOTED_MS : KEEP_ALARM_MS;
    }

    private void updateNotification(boolean rooted) {
        if (notifRooted == rooted) {
            return;
        }
        notifRooted = rooted;
        promote(rooted);
    }

    private static void poke() {
        synchronized (GATE) {
            GATE.notifyAll();
        }
    }
}
