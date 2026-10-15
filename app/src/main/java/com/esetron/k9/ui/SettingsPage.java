package com.esetron.k9.ui;

import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import com.esetron.k9.MainActivity;
import com.esetron.k9.R;
import com.esetron.k9.Settings;
import com.esetron.k9.kiosk.Kiosk;
import com.esetron.k9.net.ConfigClient;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Ayarlar sayfasi: uygulama baglantisi, K9 uzak ayarlari (get/set/reset), kiosk. */
public final class SettingsPage {
    private static final String[] K9_KEYS = {"cameraWidth", "cameraHeight", "cameraFps", "outputWidth", "outputHeight", "outputFps", "linkTimeoutSec"};

    private final MainActivity a; private final Settings s;
    private final EditText ip, controlPort, configPort, videoPort, audioRxPort, audioTxPort, jitter, kioskPin;
    private final TextView kioskState, k9Status, loginWarning;
    private final View lock, content;
    private final EditText password, newPassword, newPassword2;
    private final Switch swDecoder;
    private final Map<String, EditText> k9Fields = new HashMap<>();

    public SettingsPage(MainActivity a) {
        this.a = a; this.s = a.settings;
        ip = a.findViewById(R.id.set_ip); controlPort = a.findViewById(R.id.set_control_port); configPort = a.findViewById(R.id.set_config_port);
        videoPort = a.findViewById(R.id.set_video_port); audioRxPort = a.findViewById(R.id.set_audio_rx_port); audioTxPort = a.findViewById(R.id.set_audio_tx_port);
        jitter = a.findViewById(R.id.set_jitter); swDecoder = a.findViewById(R.id.set_sw_decoder); kioskPin = a.findViewById(R.id.kiosk_pin); kioskState = a.findViewById(R.id.kiosk_state); k9Status = a.findViewById(R.id.k9_status);
        int[] ids = {R.id.k9_cameraWidth, R.id.k9_cameraHeight, R.id.k9_cameraFps, R.id.k9_outputWidth, R.id.k9_outputHeight, R.id.k9_outputFps, R.id.k9_linkTimeoutSec};
        for (int i = 0; i < K9_KEYS.length; i++) k9Fields.put(K9_KEYS[i], a.findViewById(ids[i]));

        // --- sifre kapisi ---
        lock = a.findViewById(R.id.settings_lock); content = a.findViewById(R.id.settings_content);
        password = a.findViewById(R.id.settings_password); loginWarning = a.findViewById(R.id.settings_login_warning);
        newPassword = a.findViewById(R.id.new_password); newPassword2 = a.findViewById(R.id.new_password2);
        a.findViewById(R.id.settings_login).setOnClickListener(v -> tryLogin());
        password.setOnEditorActionListener((tv, id, ev) -> { if (id == EditorInfo.IME_ACTION_DONE) { tryLogin(); return true; } return false; });
        a.findViewById(R.id.settings_logout).setOnClickListener(v -> lockPage());
        a.findViewById(R.id.password_save).setOnClickListener(v -> {
            String p1 = newPassword.getText().toString(), p2 = newPassword2.getText().toString();
            if (p1.length() < 4) { a.showMessage("Şifre en az 4 karakter olmalı"); return; }
            if (!p1.equals(p2)) { a.showMessage("Şifreler aynı değil"); return; }
            s.setAdminPassword(p1); newPassword.setText(""); newPassword2.setText("");
            a.showMessage("Yönetici şifresi değiştirildi");
        });

        a.findViewById(R.id.set_save).setOnClickListener(v -> save());
        a.findViewById(R.id.k9_read).setOnClickListener(v -> read());
        a.findViewById(R.id.k9_apply).setOnClickListener(v -> apply());
        a.findViewById(R.id.k9_reset).setOnClickListener(v -> a.config.reset(new ConfigClient.Callback() {
            @Override public void onReply(JSONObject r) { fill(r.optJSONObject("applied")); k9Status.setText("Fabrika değerlerine dönüldü"); }
            @Override public void onFailure(String why) { k9Status.setText("Hata: " + why); }
        }));
        a.findViewById(R.id.kiosk_exit).setOnClickListener(v -> {
            if (kioskPin.getText().toString().equals(s.kioskPin())) { Kiosk.exit(a); s.setKioskEnabled(false); a.showMessage("Kiosk kapatıldı"); }
            else a.showMessage("PIN yanlış");
            kioskPin.setText(""); loadContent();
        });
        a.findViewById(R.id.kiosk_enter).setOnClickListener(v -> { s.setKioskEnabled(true); Kiosk.enter(a); loadContent(); });
    }

    /** Sayfa acilinca: once sifre kapisi. Dogru sifreyle icerik yuklenir. */
    public void load() {
        lockPage();
    }

    private void lockPage() {
        password.setText(""); loginWarning.setText("");
        lock.setVisibility(View.VISIBLE); content.setVisibility(View.GONE);
    }

    private void tryLogin() {
        if (!password.getText().toString().equals(s.adminPassword())) { loginWarning.setText("Hatalı şifre"); password.setText(""); return; }
        lock.setVisibility(View.GONE); content.setVisibility(View.VISIBLE);
        loadContent();
    }

    private void loadContent() {
        ip.setText(s.k9Ip()); controlPort.setText(String.valueOf(s.controlPort())); configPort.setText(String.valueOf(s.configPort()));
        videoPort.setText(String.valueOf(s.videoPort())); audioRxPort.setText(String.valueOf(s.audioRxPort()));
        audioTxPort.setText(String.valueOf(s.audioTxPort())); jitter.setText(String.valueOf(s.jitterMs()));
        swDecoder.setChecked(s.swDecoder());
        kioskState.setText((Kiosk.isDeviceOwner(a) ? "Cihaz sahibi: TAM kiosk" : "Cihaz sahibi değil: ekran sabitleme. Tam kiosk: adb shell dpm set-device-owner com.esetron.k9/.kiosk.KioskAdminReceiver")
                + (s.kioskEnabled() ? " — aktif" : " — kapalı"));
        read();
    }

    private void save() {
        s.setK9Ip(ip.getText().toString().trim());
        s.setControlPort(num(controlPort, s.controlPort())); s.setConfigPort(num(configPort, s.configPort()));
        s.setVideoPort(num(videoPort, s.videoPort())); s.setAudioRxPort(num(audioRxPort, s.audioRxPort()));
        s.setAudioTxPort(num(audioTxPort, s.audioTxPort())); s.setJitterMs(num(jitter, s.jitterMs()));
        s.setSwDecoder(swDecoder.isChecked());
        a.applySettings();
        a.showMessage("Ayarlar uygulandı");
    }

    private void read() {
        k9Status.setText("K9 okunuyor…");
        a.config.get(new ConfigClient.Callback() {
            @Override public void onReply(JSONObject r) { fill(r.optJSONObject("values")); k9Status.setText("Okundu" + (r.optBoolean("override") ? " (override aktif)" : " (fabrika)")); }
            @Override public void onFailure(String why) { k9Status.setText("Okunamadı: " + why); }
        });
    }

    private void apply() {
        Map<String, Object> ch = new HashMap<>();
        for (Map.Entry<String, EditText> e : k9Fields.entrySet()) {
            String t = e.getValue().getText().toString().trim();
            if (t.isEmpty()) continue;
            try { ch.put(e.getKey(), e.getKey().equals("linkTimeoutSec") ? (Object) Double.parseDouble(t) : (Object) Integer.parseInt(t)); }
            catch (NumberFormatException ex) { k9Status.setText(e.getKey() + ": sayı değil"); return; }
        }
        if (ch.isEmpty()) { k9Status.setText("Değişecek alan yok"); return; }
        k9Status.setText("Gönderiliyor…");
        a.config.set(ch, new ConfigClient.Callback() {
            @Override public void onReply(JSONObject r) {
                JSONObject rej = r.optJSONObject("rejected");
                StringBuilder sb = new StringBuilder("Uygulandı: ").append(r.optJSONObject("applied"));
                if (rej != null && rej.length() > 0) sb.append("\nRED: ").append(rej);
                k9Status.setText(sb);
            }
            @Override public void onFailure(String why) { k9Status.setText("Hata: " + why); }
        });
    }

    private void fill(JSONObject values) {
        if (values == null) return;
        for (Iterator<String> it = values.keys(); it.hasNext(); ) {
            String k = it.next(); EditText e = k9Fields.get(k);
            if (e != null) e.setText(String.valueOf(values.opt(k)));
        }
    }

    private static int num(EditText e, int def) { try { return Integer.parseInt(e.getText().toString().trim()); } catch (NumberFormatException ex) { return def; } }
}
