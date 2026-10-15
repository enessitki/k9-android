package com.esetron.k9.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

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

    private final BroadcastReceiver batteryRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            batteryPct = level >= 0 ? level * 100 / Math.max(1, scale) : -1;
            ukbBattery.setText(batteryPct >= 0 ? "%" + batteryPct + (charging ? "⚡" : "") : "%--");
            ukbBattery.setTextColor(a.getColor(batteryPct >= 0 && batteryPct <= 15 && !charging ? R.color.bad : R.color.white));
        }
    };

    public TopBar(MainActivity a) {
        this.a = a;
        linkIcon = a.findViewById(R.id.link_icon); linkText = a.findViewById(R.id.link_text); videoText = a.findViewById(R.id.video_text);
        datetime = a.findViewById(R.id.datetime); ukbBattery = a.findViewById(R.id.ukb_battery); k9Battery = a.findViewById(R.id.k9_battery);
        gpsPanel = a.findViewById(R.id.gps_panel); gpsSelf = a.findViewById(R.id.gps_self); gpsMgrs = a.findViewById(R.id.gps_mgrs); gpsK9 = a.findViewById(R.id.gps_k9);
        a.findViewById(R.id.datetime_chip).setOnClickListener(v -> gpsPanel.setVisibility(gpsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        ContextCompat.registerReceiver(a, batteryRx, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    /** Saniyede bir (MainActivity.tick). */
    public void tick() {
        datetime.setText(fmt.format(new Date()));
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

    public void release() { try { a.unregisterReceiver(batteryRx); } catch (IllegalArgumentException ignored) { } }
}
