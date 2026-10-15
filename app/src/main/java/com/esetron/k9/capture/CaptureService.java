package com.esetron.k9.capture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.esetron.k9.R;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ekran kaydi (MediaProjection + MediaRecorder), root'suz. Android 14'te
 * on plan servisi (tip: mediaProjection) ZORUNLU; bu yuzden kayit burada.
 * Cikti MediaStore Movies/K9 altina yazilir: izin gerekmez, USB (MTP) ile
 * gorunur. Mikrofon kaydedilmez (interkom ayri kanal).
 */
public final class CaptureService extends Service {
    private static final String TAG = "CaptureService";
    private static final String CHANNEL = "k9_capture";
    private static final int NOTIF_ID = 42;
    public static final String ACTION_START = "com.esetron.k9.capture.START";
    public static final String ACTION_STOP = "com.esetron.k9.capture.STOP";
    public static final String EXTRA_RESULT_CODE = "code";
    public static final String EXTRA_RESULT_DATA = "data";

    public static final AtomicBoolean recording = new AtomicBoolean(false);

    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaRecorder recorder;
    private Uri outputUri;

    private final MediaProjection.Callback projectionCb = new MediaProjection.Callback() {
        @Override public void onStop() { Log.w(TAG, "MediaProjection sistem tarafindan durduruldu"); stopRecording(); }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) { stopRecording(); return START_NOT_STICKY; }
        if (ACTION_START.equals(intent.getAction()) && !recording.get()) {
            startForegroundCompat();
            int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (!startRecording(code, data)) stopSelfSafely();
        }
        return START_NOT_STICKY;
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "K9 ekran kaydi", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("K9 ekran kaydi").setContentText("Kayit suruyor")
                .setSmallIcon(R.drawable.ic_rec).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else
            startForeground(NOTIF_ID, n);
    }

    private boolean startRecording(int code, Intent data) {
        if (data == null) { Log.e(TAG, "MediaProjection verisi yok"); return false; }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) { Log.e(TAG, "MediaProjection alinamadi"); return false; }
        projection.registerCallback(projectionCb, null);   // Android 14: createVirtualDisplay'den ONCE sart

        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        int w = dm.widthPixels & ~1, h = dm.heightPixels & ~1;

        String name = "K9_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/K9");
        cv.put(MediaStore.Video.Media.IS_PENDING, 1);
        outputUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
        if (outputUri == null) { Log.e(TAG, "MediaStore kaydi olusturulamadi"); release(); return false; }

        try {
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(outputUri, "w");
            if (pfd == null) throw new IOException("fd yok");
            recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(w, h);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(8_000_000);
            recorder.setOutputFile(pfd.getFileDescriptor());
            recorder.prepare();
            display = projection.createVirtualDisplay("K9Capture", w, h, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.getSurface(), null, null);
            recorder.start();
            pfd.close();                         // recorder kendi dup'ini tutar
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "Kayit baslatilamadi", e);
            release(); discardOutput(); return false;
        }
        recording.set(true);
        Log.i(TAG, "Kayit basladi: " + name + " " + w + "x" + h);
        return true;
    }

    private void stopRecording() {
        if (recording.getAndSet(false)) {
            try { if (recorder != null) recorder.stop(); } catch (RuntimeException e) { Log.w(TAG, "recorder.stop: " + e.getMessage()); discardOutput(); }
            release();
            if (outputUri != null) {
                ContentValues cv = new ContentValues(); cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(outputUri, cv, null, null);
                outputUri = null;
            }
            Log.i(TAG, "Kayit durdu");
        }
        stopSelfSafely();
    }

    private void release() {
        if (display != null) { display.release(); display = null; }
        if (recorder != null) { recorder.reset(); recorder.release(); recorder = null; }
        if (projection != null) { projection.unregisterCallback(projectionCb); projection.stop(); projection = null; }
    }

    private void discardOutput() {
        if (outputUri != null) { getContentResolver().delete(outputUri, null, null); outputUri = null; }
    }

    private void stopSelfSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() { stopRecording(); super.onDestroy(); }
}
