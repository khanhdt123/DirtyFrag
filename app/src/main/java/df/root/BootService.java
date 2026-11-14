package df.root;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs boot autorun in a foreground service after {@code ACTION_BOOT_COMPLETED}.
 *
 * <p>Executing directly inside {@link BootReceiver} via {@code goAsync()} leaves the process
 * exposed to Android's ~10s BroadcastQueue limit and early cached-empty OOM kills during heavy or
 * low-battery cold boots. Running here elevates the process while waiting briefly for boot I/O to
 * settle and keeps the autorun interlock tolerant of a single transient boot failure.
 */
public class BootService extends Service {
    private static final String TAG = "dfroot";
    private static final String CHANNEL_ID = "dfroot_boot";
    private static final int NOTIF_ID = 1001;
    private static final long EARLY_BOOT_THRESHOLD_MS = 60_000L;
    private static final long SETTLE_EARLY_BOOT_MS = 8_000L;
    private static final long SETTLE_LATE_BOOT_MS = 2_000L;
    private static final long WAKELOCK_MS = 180_000L;
    private static final int MAX_CONSECUTIVE_FAILS = 2;
    static final String PREF_BOOT_FAIL_COUNT = "boot_fail_count";

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private volatile int latestStartId = -1;

    static boolean isRunning() {
        return RUNNING.get();
    }

    static void runWithGoAsyncFallback(Context context, BroadcastReceiver.PendingResult pending) {
        if (!RUNNING.compareAndSet(false, true)) {
            Log.i(TAG, "boot: autorun already in progress, skipping duplicate fallback");
            if (pending != null) pending.finish();
            return;
        }
        final Context appCtx = context.getApplicationContext();
        final PowerManager.WakeLock wl = acquireWakeLock(appCtx);
        new Thread(() -> {
            try {
                executeAutorun(appCtx);
            } catch (Throwable t) {
                Log.e(TAG, "boot: fallback exception", t);
            } finally {
                RUNNING.set(false);
                if (wl != null && wl.isHeld()) wl.release();
                if (pending != null) pending.finish();
            }
        }, "dfroot-boot-fallback").start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        promoteForeground();
        if (!RUNNING.compareAndSet(false, true)) {
            Log.i(TAG, "boot: service autorun already in progress, skipping duplicate start");
            return START_NOT_STICKY;
        }
        final Context appCtx = getApplicationContext();
        final PowerManager.WakeLock wl = acquireWakeLock(appCtx);
        new Thread(() -> {
            try {
                executeAutorun(appCtx);
            } catch (Throwable t) {
                Log.e(TAG, "boot: service exception", t);
            } finally {
                RUNNING.set(false);
                if (wl != null && wl.isHeld()) wl.release();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(latestStartId);
            }
        }, "dfroot-boot").start();
        return START_NOT_STICKY;
    }

    private void promoteForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Boot autorun", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification notif = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("DirtyFrag")
                .setContentText("Running post-boot initialization...")
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, notif);
        }
    }

    private static PowerManager.WakeLock acquireWakeLock(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return null;
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dfroot:boot");
        wl.acquire(WAKELOCK_MS);
        return wl;
    }

    private static void executeAutorun(Context appCtx) {
        long elapsedMs = SystemClock.elapsedRealtime();
        long settleMs = elapsedMs < EARLY_BOOT_THRESHOLD_MS
                ? SETTLE_EARLY_BOOT_MS
                : SETTLE_LATE_BOOT_MS;
        Log.i(TAG, "boot: settling for " + settleMs + "ms (elapsed=" + elapsedMs + "ms)");
        try {
            Thread.sleep(settleMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        if (RootState.hooked()) {
            Log.i(TAG, "boot: /dev/df present after settle, "
                    + (D2Writer.suPresent() ? "su present - already rooted this boot"
                                           : "no su appeared - hooked this boot without root")
                    + "; skipping, the hook cannot run twice in one boot");
            return;
        }

        final Context deCtx = appCtx.createDeviceProtectedStorageContext();
        SharedPreferences sp = deCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);
        if (!sp.getBoolean("expert_mode", false)) {
            Log.i(TAG, "boot: expert mode off after settle - autorun skipped");
            return;
        }
        ComponentName receiver = new ComponentName(appCtx, BootReceiver.class);
        if (appCtx.getPackageManager().getComponentEnabledSetting(receiver)
                != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            Log.i(TAG, "boot: receiver disabled after settle - autorun skipped");
            return;
        }

        boolean autoSoftReboot = sp.getBoolean("auto_soft_reboot", false);
        // auto_soft_reboot=true: bootstrap reads soft_reboot from DE prefs, not argv.
        sp.edit().putBoolean(ExploitRunner.PREF_SOFT_REBOOT, autoSoftReboot).apply();

        final StringBuilder bootLog = new StringBuilder("SYSTEM\n");
        final String[] lastFailReason = { null };
        IReporter reporter = msg -> {
            for (String line : msg.split("\n", -1)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) Log.i(TAG, trimmed);
                int err = trimmed.indexOf("ERROR - ");
                if (err >= 0) {
                    lastFailReason[0] = trimmed.substring(err + "ERROR - ".length()).trim();
                }
                String formatted = ExploitRunner.formatLogLine(line);
                if (formatted != null) {
                    bootLog.append(formatted).append('\n');
                }
            }
        };

        boolean ok = false;
        int rc = 2;
        try {
            rc = ExploitRunner.run(deCtx, reporter);
            Log.i(TAG, "boot: exploit rc=" + rc);
            ok = rc == 0;
            if (!ok && RootState.hooked()) {
                for (int i = 0; i < 4 && !D2Writer.suPresent(); i++) {
                    Thread.sleep(1000L);
                }
                if (RootState.state() == RootState.State.ROOTED) {
                    ok = true;
                    Log.i(TAG, "boot: su appeared after nonzero rc, treating boot as rooted");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "boot: exploit exception", e);
            lastFailReason[0] = e.toString();
        }

        if (!ok) {
            String why = lastFailReason[0] != null ? lastFailReason[0]
                    : rc == 1 ? "ksud nonzero exit"
                    : rc == 2 ? "check logcat & dmesg"
                    : "failed to patch files";
            bootLog.append("\n=== exploit failed: ").append(why).append(" ===\n");
        }
        saveBootLog(deCtx, sp, bootLog.toString(), ok);

        if (ok) {
            sp.edit().putInt(PREF_BOOT_FAIL_COUNT, 0).apply();
            armD2Vault(deCtx);
            return;
        }

        int fails = sp.getInt(PREF_BOOT_FAIL_COUNT, 0) + 1;
        if (fails >= MAX_CONSECUTIVE_FAILS) {
            Log.i(TAG, "boot: failed " + fails
                    + " times consecutively - disabling autorun interlock");
            sp.edit().putInt(PREF_BOOT_FAIL_COUNT, 0).apply();
            appCtx.getPackageManager().setComponentEnabledSetting(
                    receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } else {
            Log.i(TAG, "boot: failed (attempt " + fails + "/" + MAX_CONSECUTIVE_FAILS
                    + ") - keeping autorun enabled for next reboot");
            sp.edit().putInt(PREF_BOOT_FAIL_COUNT, fails).apply();
        }
    }

    private static void saveBootLog(Context deCtx, SharedPreferences sp, String content, boolean ok) {
        File lastLogFile = new File(deCtx.getFilesDir(), "last_run.log");
        try (FileOutputStream out = new FileOutputStream(lastLogFile)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "boot: failed to write last_run.log", e);
        }
        sp.edit()
                .putBoolean("last_run_success", ok)
                .putInt("log_boot_count", ExploitRunner.bootCount(deCtx))
                .apply();
    }

    /**
     * Re-arm the vault's download-mode byte for owners who opted into "Autorun Writer".
     */
    private static void armD2Vault(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);
        if (!sp.getBoolean("d2_auto", false)) return;
        if (!D2Status.read(ctx).supported) {
            Log.i(TAG, "boot: d2 re-arm skipped - this firmware does not gate download mode");
            return;
        }
        try {
            for (int i = 0; i < 10 && !D2Writer.suPresent(); i++) Thread.sleep(3000L);
            D2Writer.Vault v = D2Writer.status(ctx);
            if (!v.ok) {
                Log.i(TAG, "boot: d2 re-arm skipped, vault unreadable: " + v.state);
                return;
            }
            if (v.atCommand == 1) {
                Log.i(TAG, "boot: d2 already authorised, nothing to write");
                return;
            }
            v = D2Writer.authorize(ctx);
            Log.i(TAG, "boot: d2 re-arm " + v.state
                    + (v.ok ? " at_command=" + v.atCommand : " - " + v.detail));
        } catch (Exception e) {
            Log.e(TAG, "boot: d2 re-arm threw", e);
        }
    }
}
