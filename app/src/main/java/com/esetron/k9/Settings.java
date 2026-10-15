package com.esetron.k9;

import android.content.Context;
import android.content.SharedPreferences;

/** Uygulama ayarlari (SharedPreferences). K9 tarafi params.py ile eslesir. */
public final class Settings {
    private static final String PREFS = "k9";
    private final SharedPreferences sp;

    public Settings(Context ctx) { sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public String k9Ip()        { return sp.getString("k9Ip", "192.168.2.10"); }
    public int controlPort()    { return sp.getInt("controlPort", 5005); }   // K9 selfPort
    public int configPort()     { return sp.getInt("configPort", 5006); }    // K9 configPort
    public int videoPort()      { return sp.getInt("videoPort", 5001); }     // K9 ukbVideoSendPort
    public int audioRxPort()    { return sp.getInt("audioRxPort", 14551); }  // K9 ukbAudioSendPort
    public int audioTxPort()    { return sp.getInt("audioTxPort", 14552); }  // K9 ukbAudioReceivePort
    public int jitterMs()       { return sp.getInt("jitterMs", 80); }
    public String kioskPin()    { return sp.getString("kioskPin", "1453"); }
    public boolean kioskEnabled() { return sp.getBoolean("kiosk", true); }
    /** Ayarlar sayfasi girisi (eski uygulamadaki yonetici sifresinin karsiligi). */
    public String adminPassword() { return sp.getString("adminPassword", "palem"); }

    public void setK9Ip(String v)        { sp.edit().putString("k9Ip", v).apply(); }
    public void setControlPort(int v)    { sp.edit().putInt("controlPort", v).apply(); }
    public void setConfigPort(int v)     { sp.edit().putInt("configPort", v).apply(); }
    public void setVideoPort(int v)      { sp.edit().putInt("videoPort", v).apply(); }
    public void setAudioRxPort(int v)    { sp.edit().putInt("audioRxPort", v).apply(); }
    public void setAudioTxPort(int v)    { sp.edit().putInt("audioTxPort", v).apply(); }
    public void setJitterMs(int v)       { sp.edit().putInt("jitterMs", v).apply(); }
    public void setKioskPin(String v)    { sp.edit().putString("kioskPin", v).apply(); }
    public void setKioskEnabled(boolean v) { sp.edit().putBoolean("kiosk", v).apply(); }
    public void setAdminPassword(String v) { sp.edit().putString("adminPassword", v).apply(); }
}
