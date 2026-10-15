package com.esetron.k9.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.esetron.k9.MainActivity;
import com.esetron.k9.Settings;

/** Acilista uygulamayi baslat (kiosk acikken). HOME olarak ayarliysa zaten acilir; bu yedek yoldur. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!new Settings(ctx).kioskEnabled()) return;
        Intent i = new Intent(ctx, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ctx.startActivity(i);
    }
}
