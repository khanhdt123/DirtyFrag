package df.root;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Receives {@code ACTION_BOOT_COMPLETED} and delegates autorun to {@link BootService} so the
 * work runs in a foreground service rather than inside BroadcastQueue's short {@code goAsync()}
 * window.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "dfroot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "boot: " + action);
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return;
        }
        if (RootState.hooked()) {
            Log.i(TAG, "boot: /dev/df present, "
                    + (D2Writer.suPresent() ? "su present - already rooted this boot"
                                           : "no su appeared - hooked this boot without root")
                    + "; skipping, the hook cannot run twice in one boot");
            return;
        }
        final Context deCtx = context.createDeviceProtectedStorageContext();
        SharedPreferences sp = deCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);
        if (!sp.getBoolean("expert_mode", false)) {
            Log.i(TAG, "boot: expert mode off - autorun skipped");
            return;
        }
        boolean autoSoftReboot = sp.getBoolean("auto_soft_reboot", false);
        sp.edit().putBoolean(ExploitRunner.PREF_SOFT_REBOOT, autoSoftReboot).apply();

        if (BootService.isRunning()) {
            Log.i(TAG, "boot: autorun already in progress, skipping duplicate broadcast");
            return;
        }
        try {
            context.startForegroundService(new Intent(context, BootService.class));
            Log.i(TAG, "boot: started BootService");
        } catch (RuntimeException e) {
            Log.w(TAG, "boot: startForegroundService denied (" + e.getClass().getSimpleName()
                    + "), falling back to goAsync", e);
            BootService.runWithGoAsyncFallback(context, goAsync());
        }
    }
}
