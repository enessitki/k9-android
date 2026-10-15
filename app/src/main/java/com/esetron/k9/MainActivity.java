package com.esetron.k9;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.esetron.k9.capture.CaptureService;
import com.esetron.k9.capture.Screenshot;
import com.esetron.k9.kiosk.Kiosk;
import com.esetron.k9.map.MapController;
import com.esetron.k9.net.ConfigClient;
import com.esetron.k9.net.ControlSender;
import com.esetron.k9.ui.SettingsDialog;
import com.esetron.k9.video.NativeBridge;

import org.osmdroid.views.MapView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Tek ekran: video (SurfaceView), ust bilgi cubugu, sol kontrol, sag islem.
 * Video/ses native (GStreamer) tarafta; kontrol paketi ve ayar kanali Java.
 */
public final class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback, NativeBridge.Listener {
    private static final int LINK_LOST_SEC = 3;      // bu kadar saniye kare gelmezse LINK YOK

    private Settings settings;
    private final NativeBridge bridge = new NativeBridge();
    private final ControlSender control = new ControlSender();
    private final ConfigClient config = new ConfigClient();
    private final Screenshot screenshot = new Screenshot();
    private MapController mapCtl;

    private SurfaceView video;
    private View linkDot, mapPanel;
    private TextView txtLink, txtVideo, txtGps, txtClock, txtMsg, txtLed;
    private Button rec, mapToggle;
    private final Button[] brButtons = new Button[4];

    private final Handler ui = new Handler(Looper.getMainLooper());
    private int noFrameSec = LINK_LOST_SEC;
    private boolean mapFull;
    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private ActivityResultLauncher<Intent> captureLauncher;
    private ActivityResultLauncher<String[]> permLauncher;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        settings = new Settings(this);

        video = findViewById(R.id.video);
        linkDot = findViewById(R.id.link_dot); mapPanel = findViewById(R.id.map_panel);
        txtLink = findViewById(R.id.txt_link); txtVideo = findViewById(R.id.txt_video);
        txtGps = findViewById(R.id.txt_gps); txtClock = findViewById(R.id.txt_clock);
        txtMsg = findViewById(R.id.txt_msg); txtLed = findViewById(R.id.txt_led);
        rec = findViewById(R.id.rec); mapToggle = findViewById(R.id.map_toggle);

        // --- Sol bar ---
        int[] brIds = {R.id.br1, R.id.br2, R.id.br3, R.id.br4};
        for (int i = 0; i < 4; i++) {
            final int level = i + 1;
            brButtons[i] = findViewById(brIds[i]);
            brButtons[i].setOnClickListener(v -> { control.setBitrate(level); highlightBitrate(); });
        }
        highlightBitrate();
        SeekBar led = findViewById(R.id.led);
        led.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) { control.setLed(p); txtLed.setText(getString(R.string.led) + " " + p + "%"); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        ((ToggleButton) findViewById(R.id.blind)).setOnCheckedChangeListener((b, on) -> control.setBlind(on));
        ((ToggleButton) findViewById(R.id.speaker)).setOnCheckedChangeListener((b, on) -> control.setSpeaker(on));
        ((ToggleButton) findViewById(R.id.noise)).setOnCheckedChangeListener((b, on) -> control.setNoiseReduction(on));

        // --- Sag bar ---
        findViewById(R.id.ptt).setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: bridge.ptt(true); v.setPressed(true); return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: bridge.ptt(false); v.setPressed(false); return true;
                default: return false;
            }
        });
        rec.setOnClickListener(v -> toggleRecording());
        findViewById(R.id.snap).setOnClickListener(v -> screenshot.take(this, video,
                (ok, name) -> showMessage(ok ? "Kaydedildi: Pictures/K9/" + name : "Ekran goruntusu alinamadi")));
        mapToggle.setOnClickListener(v -> toggleMap());
        mapPanel.setOnClickListener(v -> toggleMapSize());
        findViewById(R.id.settings).setOnClickListener(v -> new SettingsDialog(this, settings, config, this::applySettings).show());

        // --- Harita ---
        MapView mapView = findViewById(R.id.map);
        mapCtl = new MapController(this, mapView);
        mapCtl.setListener((lat, lon, mgrs) -> txtGps.setText(String.format(Locale.US, "%.5f %.5f  %s", lat, lon, mgrs)));

        // --- Izinler ve launcher'lar ---
        captureLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_START)
                        .putExtra(CaptureService.EXTRA_RESULT_CODE, r.getResultCode())
                        .putExtra(CaptureService.EXTRA_RESULT_DATA, r.getData());
                ContextCompat.startForegroundService(this, i);
                ui.postDelayed(this::updateRecButton, 800);
            } else showMessage("Ekran kaydi izni verilmedi");
        });
        permLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), res -> {
            if (Boolean.TRUE.equals(res.get(Manifest.permission.ACCESS_FINE_LOCATION))) mapCtl.startLocation();
            if (Boolean.TRUE.equals(res.get(Manifest.permission.RECORD_AUDIO))) bridge.audioRestart();
        });
        askPermissions();

        // --- Native + ag ---
        if (!bridge.init(this, this)) showMessage("GStreamer baslatilamadi");
        applySettings();
        video.getHolder().addCallback(this);
        bridge.start();
        control.start();

        ui.post(tick);     // kiosk kilidi onResume'da (activity on planda olmali)
    }

    private void askPermissions() {
        java.util.List<String> need = new java.util.ArrayList<>();
        for (String p : new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION})
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (need.isEmpty()) mapCtl.startLocation();
        else permLauncher.launch(need.toArray(new String[0]));
    }

    /** Ayarlar degisince: native hedefleri, kontrol ve ayar kanali hedefleri. */
    private void applySettings() {
        bridge.configure(settings.k9Ip(), settings.videoPort(), settings.audioRxPort(), settings.audioTxPort(), settings.jitterMs());
        control.setTarget(settings.k9Ip(), settings.controlPort());
        config.setTarget(settings.k9Ip(), settings.configPort());
    }

    // ---------------- saniyelik tik: saat, link durumu, kayit dugmesi ----------------
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            txtClock.setText(clockFmt.format(new Date()));
            boolean up = noFrameSec < LINK_LOST_SEC;
            linkDot.setBackgroundResource(up ? R.drawable.dot_green : R.drawable.dot_red);
            txtLink.setText(up ? R.string.link_up : R.string.link_down);
            if (noFrameSec < 1000) noFrameSec++;
            updateRecButton();
            ui.postDelayed(this, 1000);
        }
    };

    private void updateRecButton() {
        boolean on = CaptureService.recording.get();
        rec.setText(on ? R.string.rec_stop : R.string.rec);
        rec.setBackgroundTintList(ContextCompat.getColorStateList(this, on ? R.color.rec : R.color.accent));
    }

    private void highlightBitrate() {
        for (int i = 0; i < 4; i++) brButtons[i].setAlpha(control.bitrate() == i + 1 ? 1f : 0.45f);
    }

    private void toggleRecording() {
        if (CaptureService.recording.get()) {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
            ui.postDelayed(this::updateRecButton, 500);
        } else {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            captureLauncher.launch(mpm.createScreenCaptureIntent());
        }
    }

    private void toggleMap() {
        boolean show = mapPanel.getVisibility() != View.VISIBLE;
        mapPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) mapCtl.onResume(); else mapCtl.onPause();
    }

    private void toggleMapSize() {
        mapFull = !mapFull;
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) mapPanel.getLayoutParams();
        float d = getResources().getDisplayMetrics().density;
        if (mapFull) { lp.width = lp.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT; lp.setMargins((int) (100 * d), (int) (28 * d), (int) (100 * d), 0); }
        else { lp.width = (int) (320 * d); lp.height = (int) (220 * d); lp.setMargins(0, 0, (int) (96 * d), (int) (8 * d)); }
        mapPanel.setLayoutParams(lp);
    }

    public void showMessage(String msg) {
        txtMsg.setText(msg); txtMsg.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideMsg); ui.postDelayed(hideMsg, 4000);
    }
    private final Runnable hideMsg = () -> txtMsg.setVisibility(View.GONE);

    // ---------------- NativeBridge.Listener (ana thread) ----------------
    @Override public void onMessage(String msg) { showMessage(msg); }
    @Override public void onStats(int fps, int w, int h) {
        if (fps > 0) noFrameSec = 0;
        txtVideo.setText(w > 0 ? String.format(Locale.US, "%dx%d %d fps", w, h, fps) : "-");
    }

    // ---------------- Surface ----------------
    @Override public void surfaceCreated(@NonNull SurfaceHolder holder) { }
    @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int w, int h) { bridge.surfaceSet(holder.getSurface()); }
    @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) { bridge.surfaceRelease(); }

    // ---------------- yasam dongusu ----------------
    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (settings.kioskEnabled()) Kiosk.enter(this);
        if (mapPanel.getVisibility() == View.VISIBLE) mapCtl.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mapPanel.getVisibility() == View.VISIBLE) mapCtl.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        control.stop();
        mapCtl.stopLocation();
        bridge.stop();
        bridge.release();
        super.onDestroy();
    }

    @Override public void onBackPressed() { /* kiosk: geri tusu kapali */ }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) { c.hide(WindowInsets.Type.systemBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }
}
