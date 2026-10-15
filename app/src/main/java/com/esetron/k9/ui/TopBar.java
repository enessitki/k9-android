package com.esetron.k9.ui;

import android.content.Context;
import android.os.BatteryManager;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.esetron.k9.MainActivity;
import com.esetron.k9.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Ust serit: link chip (durum + fps), tarih/saat chip (acilir GPS paneli), pil chip. */
public final class TopBar {
    private final MainActivity a;
    private final ImageView linkIcon;
    private final TextView linkText, videoText, datetime, ukbBattery, k9Battery, gpsSelf, gpsMgrs, gpsK9;
    private final View gpsPanel;
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US);
    public int batteryPct = -1; public boolean charging;
    private final BatteryManager bm;

    public TopBar(MainActivity a) {
        this.a = a;
        linkIcon = a.findViewById(R.id.link_icon); linkText = a.findViewById(R.id.link_text); videoText = a.findViewById(R.id.video_text);
        datetime = a.findViewById(R.id.datetime); ukbBattery = a.findViewById(R.id.ukb_battery); k9Battery = a.findViewById(R.id.k9_battery);
        gpsPanel = a.findViewById(R.id.gps_panel); gpsSelf = a.findViewById(R.id.gps_self); gpsMgrs = a.findViewById(R.id.gps_mgrs); gpsK9 = a.findViewById(R.id.gps_k9);
        a.findViewById(R.id.datetime_chip).setOnClickListener(v -> gpsPanel.setVisibility(gpsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        bm = (BatteryManager) a.getSystemService(Context.BATTERY_SERVICE);
    }

    /** Saniyede bir (MainActivity.tick). */
    public void tick() {
        datetime.setText(fmt.format(new Date()));
        // Pil: yayin alicisi yerine dogrudan BatteryManager (RECEIVER_NOT_EXPORTED ile
        // sistem yayini bazi cihazlarda gelmiyordu). Saniyede bir okumak ucuz.
        if (bm != null) {
            int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            batteryPct = (pct >= 0 && pct <= 100) ? pct : -1;
            charging = bm.isCharging();
            ukbBattery.setText(batteryPct >= 0 ? "%" + batteryPct + (charging ? "⚡" : "") : "%--");
            ukbBattery.setTextColor(a.getColor(batteryPct >= 0 && batteryPct <= 15 && !charging ? R.color.bad : R.color.white));
        }
        boolean up = a.linkUp();
        linkIcon.setImageResource(up ? R.drawable.wifi_strength_4 : R.drawable.wifi_strength_off_outline);
        linkText.setText(up ? R.string.link_up : R.string.link_down);
        linkText.setTextColor(a.getColor(up ? R.color.ok : R.color.bad));
        videoText.setText(a.videoText());
    }

    public void onSelfLocation(double lat, double lon, String mgrs) {
        gpsSelf.setText(String.format(Locale.US, "UKB: %.5f, %.5f", lat, lon));
        gpsMgrs.setText("MGRS: " + mgrs);
    }

    /** K9 telemetrisi gelince (ileride). */
    public void onK9Status(String gpsText, int batteryPct) {
        gpsK9.setText("K9: " + gpsText);
        k9Battery.setText(batteryPct >= 0 ? "%" + batteryPct : "%--");
    }

    public void release() { }
}
