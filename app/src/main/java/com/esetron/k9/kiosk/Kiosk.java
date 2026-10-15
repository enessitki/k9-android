package com.esetron.k9.kiosk;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;
import android.view.WindowManager;

/**
 * Kiosk modu, root'suz.
 *  - Cihaz sahibiyse: LockTask paket izni, HOME olarak kalici tercih, kilit
 *    ekrani ve durum cubugu kapali, startLockTask() onaysiz -> cikis yok.
 *  - Degilse: startLockTask() ekran sabitleme (ilk seferde onay sorar;
 *    Geri+Son uygulamalar ile cikilabilir). Ayarlarda uyari gosterilir.
 */
public final class Kiosk {
    private static final String TAG = "Kiosk";

    private Kiosk() { }

    public static boolean isDeviceOwner(Context ctx) {
        DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(ctx.getPackageName());
    }

    /** Activity.onCreate/onResume'dan: kiosk aktifse kilidi kur. */
    public static void enter(Activity a) {
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        DevicePolicyManager dpm = (DevicePolicyManager) a.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = KioskAdminReceiver.component(a);
        if (dpm != null && dpm.isDeviceOwnerApp(a.getPackageName())) {
            try {
                dpm.setLockTaskPackages(admin, new String[]{a.getPackageName()});
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Yalniz sistem bilgisi (saat/pil) gorunsun; home/overview/bildirim kapali
                    dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO);
                }
                dpm.setKeyguardDisabled(admin, true);
                dpm.setStatusBarDisabled(admin, true);
                IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.addCategory(Intent.CATEGORY_DEFAULT);
                dpm.addPersistentPreferredActivity(admin, home, new ComponentName(a, a.getClass()));
            } catch (SecurityException e) {
                Log.w(TAG, "Device owner ayarlari uygulanamadi", e);
            }
        }
        if (!isLocked(a)) {
            try { a.startLockTask(); } catch (IllegalStateException | SecurityException e) { Log.w(TAG, "startLockTask: " + e.getMessage()); }
        }
    }

    /** PIN dogrulandiktan sonra: kilidi ac, HOME tercihini kaldir. */
    public static void exit(Activity a) {
        try { a.stopLockTask(); } catch (IllegalStateException e) { Log.w(TAG, "stopLockTask: " + e.getMessage()); }
        DevicePolicyManager dpm = (DevicePolicyManager) a.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = KioskAdminReceiver.component(a);
        if (dpm != null && dpm.isDeviceOwnerApp(a.getPackageName())) {
            try {
                dpm.clearPackagePersistentPreferredActivities(admin, a.getPackageName());
                dpm.setStatusBarDisabled(admin, false);
                dpm.setKeyguardDisabled(admin, false);
            } catch (SecurityException e) { Log.w(TAG, "exit: " + e.getMessage()); }
        }
        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public static boolean isLocked(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        return am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }
}
