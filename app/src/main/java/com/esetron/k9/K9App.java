package com.esetron.k9;

import android.app.Application;
import android.util.Log;

/** Uygulama: yakalanmayan istisnalari logla (kiosk'ta sessiz olum olmasin). */
public final class K9App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            Log.e("K9App", "Yakalanmayan istisna: " + t.getName(), e);
            if (def != null) def.uncaughtException(t, e);
        });
    }
}
