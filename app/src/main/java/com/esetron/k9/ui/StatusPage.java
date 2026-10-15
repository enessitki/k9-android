package com.esetron.k9.ui;

import android.os.StatFs;
import android.widget.TextView;

import com.esetron.k9.BuildConfig;
import com.esetron.k9.MainActivity;
import com.esetron.k9.R;
import com.esetron.k9.kiosk.Kiosk;
import com.esetron.k9.net.ConfigClient;

import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;
import java.util.Locale;

/** Durum sayfasi: link/video, K9 ayarlari (ayar kanalindan), telefon durumu. */
public final class StatusPage {
    private final MainActivity a;
    private final TextView link, video, k9, bitrate, configState, k9Values, battery, gps, mgrs, kiosk, disk, version;

    public StatusPage(MainActivity a) {
        this.a = a;
        link = a.findViewById(R.id.st_link); video = a.findViewById(R.id.st_video); k9 = a.findViewById(R.id.st_k9);
        bitrate = a.findViewById(R.id.st_bitrate); configState = a.findViewById(R.id.st_config); k9Values = a.findViewById(R.id.st_k9_values);
        battery = a.findViewById(R.id.st_battery); gps = a.findViewById(R.id.st_gps); mgrs = a.findViewById(R.id.st_mgrs);
        kiosk = a.findViewById(R.id.st_kiosk); disk = a.findViewById(R.id.st_disk); version = a.findViewById(R.id.st_version);
        a.findViewById(R.id.st_refresh).setOnClickListener(v -> refresh());
    }

    public void refresh() {
        boolean up = a.linkUp();
        link.setText(up ? "LINK" : "LINK YOK"); link.setTextColor(a.getColor(up ? R.color.ok : R.color.bad));
        video.setText(a.videoText());
        k9.setText(a.settings.k9Ip() + ":" + a.settings.videoPort());
        bitrate.setText(String.valueOf(a.control.bitrate()));
        battery.setText(a.topBar.batteryPct >= 0 ? "%" + a.topBar.batteryPct + (a.topBar.charging ? " (şarj)" : "") : "-");
        gps.setText(a.hasFix ? String.format(Locale.US, "%.5f, %.5f", a.selfLat, a.selfLon) : "fix yok");
        mgrs.setText(a.hasFix ? a.selfMgrs : "-");
        kiosk.setText(Kiosk.isDeviceOwner(a) ? "cihaz sahibi (tam)" : (Kiosk.isLocked(a) ? "ekran sabitleme" : "kapalı"));
        File dir = a.getExternalFilesDir(null);
        if (dir != null) { StatFs st = new StatFs(dir.getAbsolutePath()); disk.setText(String.format(Locale.US, "%.1f / %.1f GB boş", st.getAvailableBytes() / 1073741824.0, st.getTotalBytes() / 1073741824.0)); }
        version.setText(BuildConfig.VERSION_NAME);
        configState.setText("sorgulanıyor…");
        a.config.get(new ConfigClient.Callback() {
            @Override public void onReply(JSONObject r) {
                configState.setText("OK" + (r.optBoolean("override") ? " (override)" : " (fabrika)")); configState.setTextColor(a.getColor(R.color.ok));
                JSONObject v = r.optJSONObject("values"); StringBuilder sb = new StringBuilder();
                if (v != null) for (Iterator<String> it = v.keys(); it.hasNext(); ) { String key = it.next(); sb.append(key).append(" = ").append(v.opt(key)).append('\n'); }
                k9Values.setText(sb.length() > 0 ? sb.toString().trim() : "-");
            }
            @Override public void onFailure(String why) { configState.setText("yanıt yok"); configState.setTextColor(a.getColor(R.color.bad)); k9Values.setText("-"); }
        });
    }
}
