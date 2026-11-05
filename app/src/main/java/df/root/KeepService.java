package df.root;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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

/**
 * Stays up for the whole boot. The process is held by a foreground service, a
 * repeating alarm, boot and unlock broadcasts, and a root loop that starts
 * this service again if the system kills it. Each tick runs the post-root scripts.
 */
public class KeepService extends Service {

    private static final String TAG = "dfroot";
    private static final String CHANNEL = "dfroot-keep";
    private static final int NOTIF_ID = 7;
    private static final int ALARM_CODE = 1002;
    private static final long TICK_MS = 60000;
    private static final long ALARM_MS = 180000;
    private static final long ROOT_KICK_MS = 180000;

    private static final Object GATE = new Object();
    private static volatile boolean running;
    private static volatile long lastRootKick;
    private static Thread worker;

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
        schedule(ctx);
    }

    static void requestExemptions(android.app.Activity activity) {
        try {
            if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        } catch (Throwable ignored) {
        }
        try {
            PowerManager pm = (PowerManager) activity.getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(activity.getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(i);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(ALARM_SERVICE);
            if (am == null) {
                return;
            }
            Intent i = new Intent(ctx, KeepReceiver.class).setAction(KeepReceiver.ACTION);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, ALARM_CODE, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long at = SystemClock.elapsedRealtime() + ALARM_MS;
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
        schedule(this);
        ensureWorker();
        poke();
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        schedule(this);
        start(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        poke();
        schedule(this);
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
                    .setContentText(getString(R.string.keep_text))
                    .setOngoing(true)
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
        if (worker != null && worker.isAlive()) {
            return;
        }
        running = true;
        worker = new Thread(() -> {
            while (running) {
                try {
                    if (!RootProbe.probe().live && BootReceiver.isAutoEnabled(this)
                            && SystemClock.elapsedRealtime() - lastRootKick > ROOT_KICK_MS) {
                        lastRootKick = SystemClock.elapsedRealtime();
                        sendBroadcast(new Intent(this, BootReceiver.class)
                                .setAction(BootReceiver.ACTION_BOOT_RETRY));
                    }
                    PostRoot.tick(this);
                } catch (Throwable t) {
                    Log.w(TAG, "keep tick: " + t);
                }
                synchronized (GATE) {
                    try {
                        GATE.wait(TICK_MS);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
            }
        }, "dfroot-keep");
        worker.setDaemon(false);
        worker.start();
    }

    private static void poke() {
        synchronized (GATE) {
            GATE.notifyAll();
        }
    }
}
