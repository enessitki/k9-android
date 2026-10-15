package com.esetron.k9.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.esetron.k9.Settings;
import com.esetron.k9.kiosk.Kiosk;
import com.esetron.k9.net.ConfigClient;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Ayarlar: (1) uygulama baglanti ayarlari, (2) K9 uzak ayarlari (ayar kanali,
 * get/set/reset), (3) kiosk cikisi (PIN). Programatik layout: tek dosya, az kod.
 */
public final class SettingsDialog {
    private final Activity a; private final Settings s; private final ConfigClient cfg; private final Runnable onApply;
    private final Map<String, EditText> k9Fields = new HashMap<>();
    private TextView k9Status;

    public SettingsDialog(Activity a, Settings s, ConfigClient cfg, Runnable onApply) {
        this.a = a; this.s = s; this.cfg = cfg; this.onApply = onApply;
    }

    public void show() {
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12); root.setPadding(pad, pad, pad, pad);

        // --- 1. Baglanti ---
        header(root, "Baglanti (uygulama)");
        EditText ip = field(root, "K9 IP", s.k9Ip(), false);
        EditText cport = field(root, "Kontrol portu", String.valueOf(s.controlPort()), true);
        EditText fport = field(root, "Ayar portu", String.valueOf(s.configPort()), true);
        EditText vport = field(root, "Video portu", String.valueOf(s.videoPort()), true);
        EditText arx = field(root, "Ses alma portu", String.valueOf(s.audioRxPort()), true);
        EditText atx = field(root, "Ses gonderme portu", String.valueOf(s.audioTxPort()), true);
        EditText jit = field(root, "Jitter buffer (ms)", String.valueOf(s.jitterMs()), true);

        // --- 2. K9 uzak ayarlari ---
        header(root, "K9 ayarlari (uzak, kalici)");
        k9Status = new TextView(a); k9Status.setText("K9'dan okunmadi"); root.addView(k9Status);
        for (String k : new String[]{"cameraWidth", "cameraHeight", "cameraFps", "outputWidth", "outputHeight", "outputFps", "linkTimeoutSec"})
            k9Fields.put(k, field(root, k, "", true));
        LinearLayout row = new LinearLayout(a); row.setOrientation(LinearLayout.HORIZONTAL); root.addView(row);
        button(row, "Oku", v -> cfg.get(new ConfigClient.Callback() {
            @Override public void onReply(JSONObject r) { fill(r.optJSONObject("values")); k9Status.setText("Okundu" + (r.optBoolean("override") ? " (override aktif)" : " (fabrika)")); }
            @Override public void onFailure(String why) { k9Status.setText("Okunamadi: " + why); }
        }));
        button(row, "Uygula", v -> {
            Map<String, Object> ch = new HashMap<>();
            for (Map.Entry<String, EditText> e : k9Fields.entrySet()) {
                String t = e.getValue().getText().toString().trim();
                if (t.isEmpty()) continue;
                try { ch.put(e.getKey(), e.getKey().equals("linkTimeoutSec") ? (Object) Double.parseDouble(t) : (Object) Integer.parseInt(t)); }
                catch (NumberFormatException ex) { k9Status.setText(e.getKey() + ": sayi degil"); return; }
            }
            if (ch.isEmpty()) { k9Status.setText("Degisecek alan yok"); return; }
            k9Status.setText("Gonderiliyor...");
            cfg.set(ch, new ConfigClient.Callback() {
                @Override public void onReply(JSONObject r) {
                    JSONObject rej = r.optJSONObject("rejected");
                    StringBuilder sb = new StringBuilder("Uygulandi: ").append(r.optJSONObject("applied"));
                    if (rej != null && rej.length() > 0) sb.append("\nRED: ").append(rej);
                    k9Status.setText(sb);
                }
                @Override public void onFailure(String why) { k9Status.setText("Hata: " + why); }
            });
        });
        button(row, "Fabrika", v -> cfg.reset(new ConfigClient.Callback() {
            @Override public void onReply(JSONObject r) { fill(r.optJSONObject("applied")); k9Status.setText("Fabrika degerlerine donuldu"); }
            @Override public void onFailure(String why) { k9Status.setText("Hata: " + why); }
        }));

        // --- 3. Kiosk ---
        header(root, "Kiosk");
        TextView kio = new TextView(a);
        kio.setText(Kiosk.isDeviceOwner(a) ? "Cihaz sahibi: TAM kiosk aktif"
                : "Cihaz sahibi DEGIL: ekran sabitleme modu. Tam kiosk icin:\nadb shell dpm set-device-owner com.esetron.k9/.kiosk.KioskAdminReceiver");
        kio.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12); root.addView(kio);
        EditText pin = field(root, "Cikis PIN", "", true);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        button(root, "Kiosk'tan cik", v -> {
            if (pin.getText().toString().equals(s.kioskPin())) { Kiosk.exit(a); s.setKioskEnabled(false); Toast.makeText(a, "Kiosk kapatildi", Toast.LENGTH_SHORT).show(); }
            else Toast.makeText(a, "PIN yanlis", Toast.LENGTH_SHORT).show();
        });
        button(root, "Kiosk'u ac", v -> { s.setKioskEnabled(true); Kiosk.enter(a); });

        ScrollView sv = new ScrollView(a); sv.addView(root);
        new AlertDialog.Builder(a).setTitle("Ayarlar").setView(sv)
                .setPositiveButton("Kaydet", (d, w) -> {
                    s.setK9Ip(ip.getText().toString().trim());
                    s.setControlPort(num(cport, s.controlPort())); s.setConfigPort(num(fport, s.configPort()));
                    s.setVideoPort(num(vport, s.videoPort())); s.setAudioRxPort(num(arx, s.audioRxPort()));
                    s.setAudioTxPort(num(atx, s.audioTxPort())); s.setJitterMs(num(jit, s.jitterMs()));
                    onApply.run();
                })
                .setNegativeButton("Kapat", null).show();
    }

    private void fill(JSONObject values) {
        if (values == null) return;
        for (Iterator<String> it = values.keys(); it.hasNext(); ) {
            String k = it.next(); EditText e = k9Fields.get(k);
            if (e != null) e.setText(String.valueOf(values.opt(k)));
        }
    }

    private static int num(EditText e, int def) { try { return Integer.parseInt(e.getText().toString().trim()); } catch (NumberFormatException ex) { return def; } }
    private int dp(int v) { return (int) (v * a.getResources().getDisplayMetrics().density); }

    private void header(LinearLayout p, String t) {
        TextView tv = new TextView(a); tv.setText(t); tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16); tv.setPadding(0, dp(10), 0, dp(4)); p.addView(tv);
    }
    private EditText field(LinearLayout p, String label, String value, boolean numeric) {
        LinearLayout row = new LinearLayout(a); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView tv = new TextView(a); tv.setText(label); tv.setWidth(dp(150)); row.addView(tv);
        EditText et = new EditText(a); et.setText(value); et.setSingleLine();
        et.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL : InputType.TYPE_CLASS_TEXT);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(et); p.addView(row); return et;
    }
    private void button(LinearLayout p, String t, android.view.View.OnClickListener l) {
        Button b = new Button(a); b.setText(t); b.setOnClickListener(l); p.addView(b);
    }
}
