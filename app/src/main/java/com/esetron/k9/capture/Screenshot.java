package com.esetron.k9.capture;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Ekran goruntusu = yalniz video yuzeyi (eski ScreenShotManagement ile ayni:
 * PixelCopy ile SurfaceView). Arayuz dahil degil. PNG, MediaStore Pictures/K9.
 * PixelCopy kendi handler thread'inde dinlenir, dosya yazimi da orada.
 */
public final class Screenshot {
    private static final String TAG = "Screenshot";
    public interface Callback { void onDone(boolean ok); }

    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());

    public Screenshot() {
        HandlerThread t = new HandlerThread("k9-screenshot"); t.start();
        worker = new Handler(t.getLooper());
    }

    public void take(Activity a, SurfaceView video, Callback cb) {
        if (video == null || video.getWidth() <= 0 || video.getHeight() <= 0) { cb.onDone(false); return; }
        Bitmap bmp = Bitmap.createBitmap(video.getWidth(), video.getHeight(), Bitmap.Config.ARGB_8888);
        PixelCopy.request(video, bmp, result -> {
            if (result != PixelCopy.SUCCESS) {
                Log.e(TAG, "PixelCopy basarisiz: " + result);
                bmp.recycle();
                main.post(() -> cb.onDone(false));
                return;
            }
            save(a, bmp, cb);
        }, worker);
    }

    private void save(Activity a, Bitmap bmp, Callback cb) {
        String name = "K9_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/K9");
        cv.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = a.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        boolean ok = false;
        if (uri != null) {
            try (OutputStream os = a.getContentResolver().openOutputStream(uri)) {
                ok = os != null && bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            } catch (IOException e) { Log.e(TAG, "PNG yazilamadi", e); }
            ContentValues done = new ContentValues(); done.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (ok) a.getContentResolver().update(uri, done, null, null);
            else a.getContentResolver().delete(uri, null, null);
        }
        bmp.recycle();
        final boolean r = ok;
        main.post(() -> cb.onDone(r));
    }
}
