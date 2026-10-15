package com.esetron.k9.ui;

import android.os.StatFs;
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

/** Durum sayfasi: 4 KPI karti (kenar rengi: yesil iyi / sari uyari / kirmizi kotu) + iki bilgi karti. */
public final class StatusPage {
    private final MainActivity a;
    private final TextView link, linkSub, video, bitrate, k9, config, battery, gps, gpsFull, mgrs, kiosk, disk, map, version, updated, k9Empty;
    private final View linkMark, videoMark, k9Mark, ukbMark;
    private final LinearLayout k9Rows;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public StatusPage(MainActivity a) {
        this.a = a;
        link = a.findViewById(R.id.st_link); linkSub = a.findViewById(R.id.st_link_sub);
        video = a.findViewById(R.id.st_video); bitrate = a.findViewById(R.id.st_bitrate);
        k9 = a.findViewById(R.id.st_k9); config = a.findViewById(R.id.st_config);
        battery = a.findViewById(R.id.st_battery); gps = a.findViewById(R.id.st_gps);
        gpsFull = a.findViewById(R.id.st_gps_full); mgrs = a.findViewById(R.id.st_mgrs);
        kiosk = a.findViewById(R.id.st_kiosk); disk = a.findViewById(R.id.st_disk);
        map = a.findViewById(R.id.st_map); version = a.findViewById(R.id.st_version);
        updated = a.findViewById(R.id.st_updated); k9Empty = a.findViewById(R.id.st_k9_empty);
        linkMark = a.findViewById(R.id.tile_link_mark); videoMark = a.findViewById(R.id.tile_video_mark);
        k9Mark = a.findViewById(R.id.tile_k9_mark); ukbMark = a.findViewById(R.id.tile_ukb_mark);
        k9Rows = a.findViewById(R.id.st_k9_rows);
        a.findViewById(R.id.st_refresh).setOnClickListener(v -> refresh());
    }

    private void mark(View v, int color) { v.setBackgroundColor(a.getColor(color)); }

    public void refresh() {
        updated.setText("son: " + fmt.format(new Date()));
        boolean up = a.linkUp();
        link.setText(up ? "BAĞLI" : "YOK"); link.setTextColor(a.getColor(up ? R.color.ok : R.color.bad));
        linkSub.setText(a.settings.k9Ip());
        mark(linkMark, up ? R.color.ok : R.color.bad);

        video.setText(a.lastW > 0 ? a.lastW + "x" + a.lastH : "-");
        bitrate.setText((a.lastFps > 0 ? a.lastFps + " fps · " : "") + "kademe " + a.control.bitrate());
        mark(videoMark, a.lastFps > 0 ? R.color.ok : R.color.nav_label_idle);

        k9.setText(a.settings.k9Ip());
        config.setText("ayar kanalı sorgulanıyor…"); mark(k9Mark, R.color.nav_label_idle);

        int pct = a.topBar.batteryPct;
        battery.setText(pct >= 0 ? "%" + pct + (a.topBar.charging ? " ⚡" : "") : "-");
        gps.setText(a.hasFix ? "GPS fix var" : "GPS fix yok");
        mark(ukbMark, pct >= 0 && pct <= 15 && !a.topBar.charging ? R.color.bad : (a.hasFix ? R.color.ok : R.color.warn));

        gpsFull.setText(a.hasFix ? String.format(Locale.US, "%.5f, %.5f", a.selfLat, a.selfLon) : "-");
        mgrs.setText(a.hasFix ? a.selfMgrs : "-");
        kiosk.setText(Kiosk.isDeviceOwner(a) ? "cihaz sahibi (tam)" : (Kiosk.isLocked(a) ? "ekran sabitleme" : "kapalı"));
        File dir = a.getExternalFilesDir(null);
        if (dir != null) { StatFs st = new StatFs(dir.getAbsolutePath()); disk.setText(String.format(Locale.US, "%.1f / %.1f GB boş", st.getAvailableBytes() / 1073741824.0, st.getTotalBytes() / 1073741824.0)); }
        map.setText(a.mapCtl.isOffline() ? "çevrimdışı" : "ÇEVRİMİÇİ (dosya yok)");
        map.setTextColor(a.getColor(a.mapCtl.isOffline() ? R.color.ok : R.color.warn));
        version.setText(BuildConfig.VERSION_NAME);

        a.config.get(new ConfigClient.Callback() {
            @Override public void onReply(JSONObject r) {
                config.setText("ayar kanalı OK" + (r.optBoolean("override") ? " · override" : " · fabrika"));
                mark(k9Mark, R.color.ok);
                fillRows(r.optJSONObject("values"));
            }
            @Override public void onFailure(String why) {
                config.setText("ayar kanalı yanıt vermiyor"); mark(k9Mark, R.color.bad);
                k9Rows.removeAllViews(); k9Empty.setVisibility(View.VISIBLE); k9Empty.setText("K9'a ulaşılamadı: " + why);
            }
        });
    }

    private void fillRows(JSONObject v) {
        k9Rows.removeAllViews();
        if (v == null || v.length() == 0) { k9Empty.setVisibility(View.VISIBLE); k9Empty.setText("değer yok"); return; }
        k9Empty.setVisibility(View.GONE);
        int i = 0;
        for (Iterator<String> it = v.keys(); it.hasNext(); i++) {
            String key = it.next();
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int pad = (int) (10 * a.getResources().getDisplayMetrics().density);
            row.setPadding(pad, 0, pad, 0);
            row.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (36 * a.getResources().getDisplayMetrics().density)));
            if (i % 2 == 0) row.setBackgroundResource(R.drawable.row_background_alt);
            TextView label = new TextView(a, null, 0, R.style.RowLabel);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            label.setText(key);
            TextView value = new TextView(a, null, 0, R.style.RowValue);
            value.setText(String.valueOf(v.opt(key)));
            row.addView(label); row.addView(value);
            k9Rows.addView(row);
        }
    }
}
