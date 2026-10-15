package com.esetron.k9.ui;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.esetron.k9.MainActivity;
import com.esetron.k9.R;
import com.esetron.k9.capture.CaptureService;

/**
 * Sag fonksiyon cubugu: her dugme yanindaki paneli acar (tek seferde biri),
 * blind dogrudan toggle, mikrofon bas-konus. Panel acikken dugme cyan.
 */
public final class RightBar {
    private final MainActivity a;
    private final ImageButton btnBitrate, btnLed, btnBlind, btnMap, btnRecord, btnSpeaker, btnMic;
    private final View panelBitrate, panelLed, panelSpeaker, panelRecord, panelMap;
    private final View[] panels; private final ImageButton[] panelButtons;
    private final Button[] br = new Button[4];
    private final TextView ledValue, recordState;
    private final Switch screenRecordSwitch;
    private final int white, cyan;
    private boolean mapFull;

    @SuppressLint("ClickableViewAccessibility")
    public RightBar(MainActivity a) {
        this.a = a;
        white = a.getColor(R.color.white); cyan = a.getColor(R.color.accent_cyan);
        btnBitrate = a.findViewById(R.id.btn_bitrate); btnLed = a.findViewById(R.id.btn_led);
        btnBlind = a.findViewById(R.id.btn_blind); btnMap = a.findViewById(R.id.btn_map);
        btnRecord = a.findViewById(R.id.btn_record); btnSpeaker = a.findViewById(R.id.btn_speaker);
        btnMic = a.findViewById(R.id.btn_mic);
        panelBitrate = a.findViewById(R.id.dialog_bitrate); panelLed = a.findViewById(R.id.dialog_led_blind);
        panelSpeaker = a.findViewById(R.id.dialog_speaker); panelRecord = a.findViewById(R.id.dialog_record);
        panelMap = a.findViewById(R.id.dialog_map);
        panels = new View[]{panelBitrate, panelLed, panelSpeaker, panelRecord, panelMap};
        panelButtons = new ImageButton[]{btnBitrate, btnLed, btnSpeaker, btnRecord, btnMap};
        for (int i = 0; i < panels.length; i++) { final View p = panels[i]; panelButtons[i].setOnClickListener(v -> togglePanel(p)); }

        // --- bitrate ---
        int[] ids = {R.id.br1, R.id.br2, R.id.br3, R.id.br4};
        for (int i = 0; i < 4; i++) {
            final int level = i + 1; br[i] = a.findViewById(ids[i]);
            br[i].setOnClickListener(v -> { a.control.setBitrate(level); highlightBitrate(); });
        }
        highlightBitrate();

        // --- LED / blind ---
        ledValue = a.findViewById(R.id.led_value);
        SeekBar ledBar = a.findViewById(R.id.led_bar);
        ledBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) { if (user) setLed(p); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        a.findViewById(R.id.led_off).setOnClickListener(v -> { ledBar.setProgress(0); setLed(0); });
        a.findViewById(R.id.led_50).setOnClickListener(v -> { ledBar.setProgress(50); setLed(50); });
        a.findViewById(R.id.led_100).setOnClickListener(v -> { ledBar.setProgress(100); setLed(100); });
        Switch blind = a.findViewById(R.id.blind_switch);
        blind.setOnCheckedChangeListener((b, on) -> { a.control.setBlind(on); tint(btnBlind, on); });
        btnBlind.setOnClickListener(v -> blind.setChecked(!blind.isChecked()));

        // --- ses ---
        ((Switch) a.findViewById(R.id.speaker_switch)).setOnCheckedChangeListener((b, on) -> { a.control.setSpeaker(on); tint(btnSpeaker, on || panelSpeaker.getVisibility() == View.VISIBLE); });
        ((Switch) a.findViewById(R.id.noise_switch)).setOnCheckedChangeListener((b, on) -> a.control.setNoiseReduction(on));
        ((Switch) a.findViewById(R.id.listen_switch)).setOnCheckedChangeListener((b, on) -> a.bridge.listen(on));
        btnMic.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: a.bridge.ptt(true); tint(btnMic, true); return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: a.bridge.ptt(false); tint(btnMic, false); return true;
                default: return false;
            }
        });

        // --- kayit ---
        recordState = a.findViewById(R.id.record_state);
        screenRecordSwitch = a.findViewById(R.id.screen_record_switch);
        screenRecordSwitch.setOnCheckedChangeListener((b, on) -> { if (b.isPressed()) a.toggleRecording(on); });
        a.findViewById(R.id.screenshot_button).setOnClickListener(v -> {
            closePanels();
            a.screenshot.take(a, a.video, (ok, name) -> a.showMessage(ok ? "Kaydedildi: Pictures/K9/" + name : "Ekran görüntüsü alınamadı"));
        });

        // --- harita ---
        a.findViewById(R.id.fullscreen_map).setOnClickListener(v -> toggleMapSize());
        a.findViewById(R.id.center_self).setOnClickListener(v -> a.mapCtl.setFollowSelf(true));
    }

    private void setLed(int p) { a.control.setLed(p); ledValue.setText("LED seviyesi: %" + p); tint(btnLed, p > 0 || panelLed.getVisibility() == View.VISIBLE); }

    private void tint(ImageButton b, boolean on) { b.setBackgroundTintList(ColorStateList.valueOf(on ? cyan : white)); }

    private void highlightBitrate() {
        for (int i = 0; i < 4; i++) br[i].setSelected(a.control.bitrate() == i + 1);
    }

    private void togglePanel(View p) {
        boolean open = p.getVisibility() == View.VISIBLE;
        closePanels();
        if (!open) {
            p.setVisibility(View.VISIBLE);
            if (p == panelMap) a.mapCtl.onResume();
        }
        refreshTints();
    }

    public void closePanels() {
        for (View p : panels) p.setVisibility(View.GONE);
        a.mapCtl.onPause();
        refreshTints();
    }

    private void refreshTints() {
        tint(btnBitrate, panelBitrate.getVisibility() == View.VISIBLE);
        tint(btnLed, panelLed.getVisibility() == View.VISIBLE || a.control.led() > 0);
        tint(btnSpeaker, panelSpeaker.getVisibility() == View.VISIBLE || a.control.speaker());
        tint(btnRecord, panelRecord.getVisibility() == View.VISIBLE || CaptureService.recording.get());
        tint(btnMap, panelMap.getVisibility() == View.VISIBLE);
    }

    private void toggleMapSize() {
        mapFull = !mapFull;
        ViewGroup.LayoutParams lp = panelMap.getLayoutParams();
        float d = a.getResources().getDisplayMetrics().density;
        int screenW = a.getResources().getDisplayMetrics().widthPixels, screenH = a.getResources().getDisplayMetrics().heightPixels;
        if (mapFull) { lp.width = screenW - (int) (64 * d) - (int) (84 * d) - (int) (8 * d); lp.height = screenH - (int) (50 * d) - (int) (8 * d); }
        else { lp.width = (int) (360 * d); lp.height = (int) (240 * d); }
        panelMap.setLayoutParams(lp);
    }

    /** Saniyelik: kayit durumu (servis bagimsiz oldugundan poll). */
    public void refreshRecordState() {
        boolean on = CaptureService.recording.get();
        if (screenRecordSwitch.isChecked() != on && !screenRecordSwitch.isPressed()) screenRecordSwitch.setChecked(on);
        recordState.setText(on ? "Kayıt sürüyor → Movies/K9" : "Kayıt yok");
        recordState.setTextColor(a.getColor(on ? R.color.bad : R.color.text_dim));
        tint(btnRecord, panelRecord.getVisibility() == View.VISIBLE || on);
    }

    public void onResume() { if (panelMap.getVisibility() == View.VISIBLE) a.mapCtl.onResume(); }
    public void onPause() { a.mapCtl.onPause(); }
}
