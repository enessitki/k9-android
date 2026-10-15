package com.esetron.k9;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.TextView;

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
import com.esetron.k9.ui.LeftBar;
import com.esetron.k9.ui.RecordPage;
import com.esetron.k9.ui.RightBar;
import com.esetron.k9.ui.SettingsPage;
import com.esetron.k9.ui.StatusPage;
import com.esetron.k9.ui.TopBar;
import com.esetron.k9.video.NativeBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ana orkestrasyon (eski projedeki MainActivity'nin karsiligi): native video,
 * ag katmani ve yasam dongusu burada; arayuz parcalari LeftBar / RightBar /
 * TopBar / sayfa siniflarinda. Layout: res/layout/main.xml.
 */
public final class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback, NativeBridge.Listener {
    private static final int LINK_LOST_SEC = 3;

    public Settings settings;
    public final NativeBridge bridge = new NativeBridge();
    public final ControlSender control = new ControlSender();
    public final ConfigClient config = new ConfigClient();
    public final Screenshot screenshot = new Screenshot();
    public MapController mapCtl;

    public TopBar topBar;
    public LeftBar leftBar;
    public RightBar rightBar;
    public RecordPage recordPage;
    public StatusPage statusPage;
    public SettingsPage settingsPage;

    public SurfaceView video;
    private TextView txtMsg;
    public final Handler ui = new Handler(Looper.getMainLooper());

    // link / video durumu (TopBar ve StatusPage okur)
    private int noFrameSec = LINK_LOST_SEC;
    public int lastFps, lastW, lastH;
    public double selfLat, selfLon; public String selfMgrs = "-"; public boolean hasFix;

    private ActivityResultLauncher<Intent> captureLauncher;
    private ActivityResultLauncher<String[]> permLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        settings = new Settings(this);
        video = findViewById(R.id.video);
        txtMsg = findViewById(R.id.txt_msg);

        // Harita (sag panel icinde)
        mapCtl = new MapController(this, findViewById(R.id.map));
        mapCtl.setListener((lat, lon, mgrs) -> {
            selfLat = lat; selfLon = lon; selfMgrs = mgrs; hasFix = true;
            topBar.onSelfLocation(lat, lon, mgrs);
        });

        // Arayuz parcalari (sira: ust/sol/sag birbirine bagli degil, sayfalar leftBar'a)
        topBar = new TopBar(this);
        rightBar = new RightBar(this);
        recordPage = new RecordPage(this);
        statusPage = new StatusPage(this);
        settingsPage = new SettingsPage(this);
        leftBar = new LeftBar(this);

        captureLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_START)
                        .putExtra(CaptureService.EXTRA_RESULT_CODE, r.getResultCode())
                        .putExtra(CaptureService.EXTRA_RESULT_DATA, r.getData());
                ContextCompat.startForegroundService(this, i);
            } else showMessage("Ekran kaydı izni verilmedi");
            ui.postDelayed(rightBar::refreshRecordState, 800);
        });
        permLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), res -> {
            if (Boolean.TRUE.equals(res.get(Manifest.permission.ACCESS_FINE_LOCATION))) mapCtl.startLocation();
            if (Boolean.TRUE.equals(res.get(Manifest.permission.RECORD_AUDIO))) bridge.audioRestart();
        });
        askPermissions();

        if (!bridge.init(this, this)) showMessage("GStreamer başlatılamadı");
        applySettings();
        video.getHolder().addCallback(this);
        bridge.start();
        control.start();
        ui.post(tick);
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        for (String p : new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION})
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (need.isEmpty()) mapCtl.startLocation();
        else permLauncher.launch(need.toArray(new String[0]));
    }

    /** Ayarlar kaydedilince: native hedefler, kontrol ve ayar kanali. */
    public void applySettings() {
        bridge.configure(settings.k9Ip(), settings.videoPort(), settings.audioRxPort(), settings.audioTxPort(), settings.jitterMs());
        control.setTarget(settings.k9Ip(), settings.controlPort());
        config.setTarget(settings.k9Ip(), settings.configPort());
    }

    public boolean linkUp() { return noFrameSec < LINK_LOST_SEC; }
    public String videoText() { return lastW > 0 ? String.format(Locale.US, "%dx%d %dfps", lastW, lastH, lastFps) : "-"; }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (noFrameSec < 1000) noFrameSec++;
            topBar.tick();
            rightBar.refreshRecordState();
            ui.postDelayed(this, 1000);
        }
    };

    /** Ekran kaydi baslat/durdur (RightBar'dan). */
    public void toggleRecording(boolean on) {
        if (on == CaptureService.recording.get()) return;
        if (on) {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            captureLauncher.launch(mpm.createScreenCaptureIntent());
        } else {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
            ui.postDelayed(rightBar::refreshRecordState, 500);
        }
    }

    public void showMessage(String msg) {
        txtMsg.setText(msg); txtMsg.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideMsg); ui.postDelayed(hideMsg, 4000);
    }
    private final Runnable hideMsg = () -> txtMsg.setVisibility(View.GONE);

    // ---- NativeBridge.Listener (ana thread) ----
    @Override public void onMessage(String msg) { showMessage(msg); }
    @Override public void onStats(int fps, int w, int h) {
        if (fps > 0) noFrameSec = 0;
        lastFps = fps; lastW = w; lastH = h;
    }

    // ---- Surface ----
    @Override public void surfaceCreated(@NonNull SurfaceHolder holder) { }
    @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int w, int h) { bridge.surfaceSet(holder.getSurface()); }
    @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) { bridge.surfaceRelease(); }

    // ---- yasam dongusu ----
    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (settings.kioskEnabled()) Kiosk.enter(this);
        rightBar.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        rightBar.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        topBar.release();
        recordPage.release();
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
