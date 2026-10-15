package com.esetron.k9.ui;

import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.esetron.k9.BuildConfig;
import com.esetron.k9.MainActivity;
import com.esetron.k9.R;
import com.esetron.k9.kiosk.Kiosk;
import com.esetron.k9.net.ConfigClient;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * Durum ekrani (sirket standardi kartlar). Baglanti olcusu: video bu sayfada
 * bilerek duraklatildigi icin kare sayaci kullanilmaz; K9'un ayar kanalina
 * verdigi yanit "bagli" sayilir. Degerler renkli: yesil iyi, turuncu uyari,
 * kirmizi kotu, cyan bilgi.
 */
public final class StatusPage {
    private static final int GREEN = 0xFF4CAF50, ORANGE = 0xFFFF9800, RED = 0xFFF44336, CYAN = 0xFF00BCD4, GREY = 0xFFB0B0B0;

    private final MainActivity a;
    private final TextView k9Conn, videoState, video, fps, bitrate, led, blind, speaker, noise, config,
            battery, charging, gps, gpsFull, mgrs, map, kiosk, disk, version, updated, k9Empty;
    private final LinearLayout k9Rows;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final float density;
    private boolean querying; private int tickCount;

    public StatusPage(MainActivity a) {
        this.a = a;
        density = a.getResources().getDisplayMetrics().density;
        k9Conn = a.findViewById(R.id.st_k9_conn); videoState = a.findViewById(R.id.st_video_state);
        video = a.findViewById(R.id.st_video); fps = a.findViewById(R.id.st_fps); bitrate = a.findViewById(R.id.st_bitrate);
        led = a.findViewById(R.id.st_led); blind = a.findViewById(R.id.st_blind); speaker = a.findViewById(R.id.st_speaker); noise = a.findViewById(R.id.st_noise);
        config = a.findViewById(R.id.st_config); k9Rows = a.findViewById(R.id.st_k9_rows); k9Empty = a.findViewById(R.id.st_k9_empty);
        battery = a.findViewById(R.id.st_battery); charging = a.findViewById(R.id.st_charging);
        gps = a.findViewById(R.id.st_gps); gpsFull = a.findViewById(R.id.st_gps_full); mgrs = a.findViewById(R.id.st_mgrs); map = a.findViewById(R.id.st_map);
        kiosk = a.findViewById(R.id.st_kiosk); disk = a.findViewById(R.id.st_disk); version = a.findViewById(R.id.st_version); updated = a.findViewById(R.id.st_updated);
        a.findViewById(R.id.st_refresh).setOnClickListener(v -> refresh());
    }

    private static void set(TextView t, String text, int color) { t.setText(text); t.setTextColor(color); }

    /** Sayfa acilinca: her sey + K9 sorgusu. */
    public void refresh() { tickCount = 0; refreshLocal(); queryK9(); }

    /** MainActivity 1 Hz: yerel degerler her saniye, K9 sorgusu 5 saniyede bir. */
    public void tick() {
        refreshLocal();
        if (++tickCount % 5 == 0) queryK9();
    }

    private void refreshLocal() {
        set(updated, fmt.format(new Date()), GREY);

        // --- Baglanti karti ---
        if (!querying && k9Conn.getText().toString().equals("-")) set(k9Conn, "sorgulanıyor", GREY);
        set(videoState, a.videoPaused ? "duraklatıldı (bu sayfa)" : (a.linkUp() ? "akıyor" : "görüntü yok"),
                a.videoPaused ? ORANGE : (a.linkUp() ? GREEN : RED));
        set(video, a.lastW > 0 ? a.lastW + "x" + a.lastH : "-", CYAN);
        set(fps, a.lastFps > 0 ? a.lastFps + " fps" : "-", CYAN);
        set(bitrate, a.control.bitrate() + " / 4", CYAN);
        set(led, a.control.led() > 0 ? "%" + a.control.led() : "kapalı", a.control.led() > 0 ? ORANGE : GREY);
        set(blind, a.control.blind() ? "AÇIK" : "kapalı", a.control.blind() ? ORANGE : GREY);
        set(speaker, a.control.speaker() ? "AÇIK" : "kapalı", a.control.speaker() ? GREEN : GREY);
        set(noise, a.control.noiseReduction() ? "AÇIK" : "kapalı", a.control.noiseReduction() ? GREEN : GREY);

        // --- Kontrol birimi karti ---
        int pct = a.topBar.batteryPct;
        set(battery, pct >= 0 ? "%" + pct : "-", pct < 0 ? GREY : pct <= 15 ? RED : pct <= 30 ? ORANGE : GREEN);
        set(charging, pct < 0 ? "-" : (a.topBar.charging ? "şarj oluyor" : "pilde"), a.topBar.charging ? GREEN : GREY);
        set(gps, a.hasFix ? "fix var" : "fix yok", a.hasFix ? GREEN : ORANGE);
        set(gpsFull, a.hasFix ? String.format(Locale.US, "%.5f, %.5f", a.selfLat, a.selfLon) : "-", CYAN);
        set(mgrs, a.hasFix ? a.selfMgrs : "-", CYAN);
        set(map, a.mapCtl.isOffline() ? "çevrimdışı" : "çevrimdışı dosya yok", a.mapCtl.isOffline() ? GREEN : ORANGE);
        boolean owner = Kiosk.isDeviceOwner(a);
        set(kiosk, owner ? "tam kiosk" : (Kiosk.isLocked(a) ? "ekran sabitleme" : "kapalı"), owner ? GREEN : (Kiosk.isLocked(a) ? ORANGE : GREY));
        File dir = a.getExternalFilesDir(null);
        if (dir != null) {
            StatFs st = new StatFs(dir.getAbsolutePath());
            double freeGb = st.getAvailableBytes() / 1073741824.0;
            set(disk, String.format(Locale.US, "%.1f GB boş", freeGb), freeGb < 1 ? RED : freeGb < 4 ? ORANGE : GREEN);
        }
        set(version, BuildConfig.VERSION_NAME, GREY);
    }

    /** K9 ayar kanali = baglanti olcusu (video burada duraklatildigi icin kare sayaci kullanilmaz). */
    private void queryK9() {
        if (querying) return;
        querying = true;
        a.config.get(new ConfigClient.Callback() {
            @Override public void onReply(JSONObject r) {
                querying = false;
                set(k9Conn, "BAĞLI", GREEN);
                set(config, r.optBoolean("override") ? "özel ayarlar" : "fabrika ayarları", r.optBoolean("override") ? ORANGE : GREEN);
                fillRows(r.optJSONObject("values"));
            }
            @Override public void onFailure(String why) {
                querying = false;
                set(k9Conn, "YOK", RED);
                set(config, "yanıt yok", RED);
                k9Rows.removeAllViews(); k9Empty.setVisibility(View.VISIBLE); k9Empty.setText("K9'a ulaşılamadı");
            }
        });
    }

    /** K9 ayar anahtarlari -> Turkce etiket */
    private static String turkish(String key) {
        switch (key) {
            case "cameraWidth": return "Kamera genişlik";
            case "cameraHeight": return "Kamera yükseklik";
            case "cameraFps": return "Kamera kare hızı";
            case "cameraIoMode": return "Kamera giriş modu";
            case "outputWidth": return "Yayın genişlik";
            case "outputHeight": return "Yayın yükseklik";
            case "outputFps": return "Yayın kare hızı";
            case "linkTimeoutSec": return "Bağlantı zaman aşımı (s)";
            default: return key;
        }
    }

    private void fillRows(JSONObject v) {
        k9Rows.removeAllViews();
        if (v == null || v.length() == 0) { k9Empty.setVisibility(View.VISIBLE); k9Empty.setText("değer yok"); return; }
        k9Empty.setVisibility(View.GONE);
        for (Iterator<String> it = v.keys(); it.hasNext(); ) {
            String key = it.next();
            LinearLayout row = new LinearLayout(a, null, 0, R.style.StatRow);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_stat_row);
            int pad = (int) (10 * density);
            row.setPadding(pad, 0, pad, 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (30 * density));
            lp.topMargin = (int) (4 * density);
            row.setLayoutParams(lp);
            TextView label = new TextView(a, null, 0, R.style.StatLabel);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            label.setText(turkish(key));
            TextView value = new TextView(a, null, 0, R.style.StatValue);
            value.setText(String.valueOf(v.opt(key)));
            value.setTextColor(CYAN);
            row.addView(label); row.addView(value);
            k9Rows.addView(row);
        }
    }
}
