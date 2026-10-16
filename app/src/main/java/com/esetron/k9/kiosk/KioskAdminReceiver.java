package com.esetron.k9.kiosk;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;

/**
 * Cihaz sahibi (device owner) alicisi. Root gerekmez; bir kez USB ile:
 *   adb shell dpm set-device-owner com.esetron.k9/.kiosk.KioskAdminReceiver
 *   dpm remove-active-admin com.esetron.k9/.kiosk.KioskAdminReceiver
 * (telefonda hesap olmamali). Sonrasinda tam kiosk: LockTask, HOME, acilista baslatma.
 */
public final class KioskAdminReceiver extends DeviceAdminReceiver {
    public static ComponentName component(Context ctx) { return new ComponentName(ctx, KioskAdminReceiver.class); }
}
