package com.esetron.k9.ui;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.esetron.k9.MainActivity;
import com.esetron.k9.R;
import com.esetron.k9.capture.CaptureService;

/**
 * Sag fonksiyon cubugu, eski projedeki mantikla:
 *  - ANA IKON = eylem: LED ac/kapa, kayit baslat/durdur, hoparlor ac/kapa,
 *    harita goster/gizle, bitrate kademe (1->2->3->4->1). Bas-konus basili tutma.
 *  - EXPAND dugmesi (ikonun solunda) = ilgili ayar panelini sola acar/kapatir;
 *    tek seferde bir panel acik, acik olanin oku 180 derece doner.
 *  - LED seviyesi ve kor etme LED panelinde; seviye LED acikken aninda gider.
 */
public final class RightBar {
    private final MainActivity a;
    private final ImageButton btnBitrate, btnLed, btnMap, btnRecord, btnSpeaker, btnMic;
    private final ImageButton expBitrate, expLed, expMap, expRecord, expSpeaker;
    private final View panelBitrate, panelLed, panelSpeaker, panelRecord, panelMap, panelContainer;
    private final View[] panels; private final ImageButton[] expands; private final View[] rows;
    private final View[] br = new View[4];
    private final TextView ledValue, recordState;
    private final SeekBar ledBar;
    private final int white, cyan, red;

    private boolean ledOn, mapShown, mapFull;
    private int ledLevel = 100;

    @SuppressLint("ClickableViewAccessibility")
    public RightBar(MainActivity a) {
        this.a = a;
        white = a.getColor(R.color.white); cyan = a.getColor(R.color.accent_cyan); red = a.getColor(R.color.bad);
        btnBitrate = a.findViewById(R.id.btn_bitrate); btnLed = a.findViewById(R.id.btn_led); btnMap = a.findViewById(R.id.btn_map);
        btnRecord = a.findViewById(R.id.btn_record); btnSpeaker = a.findViewById(R.id.btn_speaker); btnMic = a.findViewById(R.id.btn_mic);
        expBitrate = a.findViewById(R.id.expand_bitrate); expLed = a.findViewById(R.id.expand_led); expMap = a.findViewById(R.id.expand_map);
        expRecord = a.findViewById(R.id.expand_record); expSpeaker = a.findViewById(R.id.expand_speaker);
        panelContainer = a.findViewById(R.id.panel_container);
        panelBitrate = a.findViewById(R.id.dialog_bitrate); panelLed = a.findViewById(R.id.dialog_led_blind);
        panelSpeaker = a.findViewById(R.id.dialog_speaker); panelRecord = a.findViewById(R.id.dialog_record); panelMap = a.findViewById(R.id.dialog_map);
        panels = new View[]{panelBitrate, panelLed, panelRecord, panelSpeaker};
        expands = new ImageButton[]{expBitrate, expLed, expRecord, expSpeaker};
        rows = new View[]{a.findViewById(R.id.row_bitrate), a.findViewById(R.id.row_led), a.findViewById(R.id.row_record), a.findViewById(R.id.row_speaker)};
        for (int i = 0; i < panels.length; i++) { final int k = i; expands[i].setOnClickListener(v -> togglePanel(k)); }

        // --- bitrate: ana ikon kademe dondurur, panelde dogrudan secim ---
        btnBitrate.setOnClickListener(v -> { a.control.setBitrate(a.control.bitrate() % 4 + 1); highlightBitrate(); a.showMessage("Bitrate kademe " + a.control.bitrate()); });
        int[] ids = {R.id.br1, R.id.br2, R.id.br3, R.id.br4};
        for (int i = 0; i < 4; i++) { final int level = i + 1; br[i] = a.findViewById(ids[i]); br[i].setOnClickListener(v -> { a.control.setBitrate(level); highlightBitrate(); }); }
        highlightBitrate();

        // --- LED: ana ikon ac/kapa (seviye panelden, kor etme panelde) ---
        ledValue = a.findViewById(R.id.led_value);
        ledBar = a.findViewById(R.id.led_bar);
        ledBar.setProgress(ledLevel);
        ledBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) { if (user) setLedLevel(p); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        a.findViewById(R.id.led_off).setOnClickListener(v -> setLedOn(false));
        a.findViewById(R.id.led_50).setOnClickListener(v -> { ledBar.setProgress(50); setLedLevel(50); setLedOn(true); });
        a.findViewById(R.id.led_100).setOnClickListener(v -> { ledBar.setProgress(100); setLedLevel(100); setLedOn(true); });
        btnLed.setOnClickListener(v -> setLedOn(!ledOn));
        ((Switch) a.findViewById(R.id.blind_switch)).setOnCheckedChangeListener((b, on) -> a.control.setBlind(on));
        setLedLevel(ledLevel);

        // --- harita: ana ikon goster/gizle, expand tam ekran ---
        btnMap.setOnClickListener(v -> showMap(!mapShown));
        expMap.setOnClickListener(v -> { if (!mapShown) showMap(true); toggleMapSize(); });
        a.findViewById(R.id.center_self).setOnClickListener(v -> a.mapCtl.setFollowSelf(true));

        // --- kayit: ana ikon baslat/durdur, panelde ekran goruntusu ---
        recordState = a.findViewById(R.id.record_state);
        btnRecord.setOnClickListener(v -> a.toggleRecording(!CaptureService.recording.get()));
        ((Switch) a.findViewById(R.id.screen_record_switch)).setOnCheckedChangeListener((b, on) -> { if (b.isPressed()) a.toggleRecording(on); });
        a.findViewById(R.id.screenshot_button).setOnClickListener(v -> {
            closePanels();
            a.screenshot.take(a, a.video, (ok, name) -> a.showMessage(ok ? "Kaydedildi: Pictures/K9/" + name : "Ekran görüntüsü alınamadı"));
        });

        // --- ses: ana ikon K9 hoparloru ac/kapa; panelde gurultu azaltma, dinleme ---
        Switch speakerSw = a.findViewById(R.id.speaker_switch);
        speakerSw.setOnCheckedChangeListener((b, on) -> { a.control.setSpeaker(on); tint(btnSpeaker, on ? red : white); });
        btnSpeaker.setOnClickListener(v -> speakerSw.setChecked(!speakerSw.isChecked()));
        ((Switch) a.findViewById(R.id.noise_switch)).setOnCheckedChangeListener((b, on) -> a.control.setNoiseReduction(on));
        ((Switch) a.findViewById(R.id.listen_switch)).setOnCheckedChangeListener((b, on) -> a.bridge.listen(on));
        btnMic.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: a.bridge.ptt(true); tint(btnMic, cyan); return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: a.bridge.ptt(false); tint(btnMic, white); return true;
                default: return false;
            }
        });
    }

    // ---------------- LED ----------------
    private void setLedLevel(int p) {
        ledLevel = p;
        ledValue.setText("LED seviyesi: %" + p);
        if (ledOn) a.control.setLed(p);
    }
    private void setLedOn(boolean on) {
        ledOn = on;
        a.control.setLed(on ? ledLevel : 0);
        tint(btnLed, on ? red : white);
    }

    // ---------------- paneller ----------------
    private void togglePanel(int k) {
        boolean open = panels[k].getVisibility() == View.VISIBLE;
        closePanels();
        if (!open) {
            // paneli ilgili satirin hizasina getir
            int[] row = new int[2], cont = new int[2];
            rows[k].getLocationInWindow(row); panelContainer.getLocationInWindow(cont);
            panels[k].setTranslationY(Math.max(0, row[1] - cont[1]));
            panels[k].setVisibility(View.VISIBLE);
            expands[k].setRotation(180f);
        }
    }

    public void closePanels() {
        for (int i = 0; i < panels.length; i++) { panels[i].setVisibility(View.GONE); expands[i].setRotation(0f); }
    }

    /** Sayfa degisiminde (LeftBar): paneller ve harita kapanir. */
    public void closeAll() {
        closePanels();
        if (mapShown) showMap(false);
    }

    private void highlightBitrate() { for (int i = 0; i < 4; i++) br[i].setSelected(a.control.bitrate() == i + 1); }

    private void tint(ImageButton b, int color) { b.setBackgroundTintList(ColorStateList.valueOf(color)); }

    // ---------------- harita ----------------
    private void showMap(boolean show) {
        mapShown = show;
        panelMap.setVisibility(show ? View.VISIBLE : View.GONE);
        tint(btnMap, show ? cyan : white);
        if (show) { a.mapCtl.onResume(); a.mapCtl.checkSource(); } else a.mapCtl.onPause();
    }

    private void toggleMapSize() {
        mapFull = !mapFull;
        ViewGroup.LayoutParams lp = panelMap.getLayoutParams();
        float d = a.getResources().getDisplayMetrics().density;
        int screenW = a.getResources().getDisplayMetrics().widthPixels, screenH = a.getResources().getDisplayMetrics().heightPixels;
        if (mapFull) { lp.width = screenW - (int) ((64 + 124 + 8) * d); lp.height = screenH - (int) ((48 + 8) * d); }
        else { lp.width = (int) (360 * d); lp.height = (int) (240 * d); }
        panelMap.setLayoutParams(lp);
        expMap.setRotation(mapFull ? 180f : 0f);
    }

    /** Saniyelik: kayit durumu (servis bagimsiz oldugundan poll). */
    public void refreshRecordState() {
        boolean on = CaptureService.recording.get();
        Switch sw = a.findViewById(R.id.screen_record_switch);
        if (sw.isChecked() != on && !sw.isPressed()) sw.setChecked(on);
        recordState.setText(on ? "Kayıt sürüyor → Movies/K9" : "Kayıt yok");
        recordState.setTextColor(a.getColor(on ? R.color.bad : R.color.text_dim));
        tint(btnRecord, on ? red : white);
    }

    public void onResume() { if (mapShown) a.mapCtl.onResume(); }
    public void onPause() { a.mapCtl.onPause(); }
}
