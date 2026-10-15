package com.esetron.k9.capture;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Ekran goruntusu: video (SurfaceView, PixelCopy) + uzerine UI (decorView.draw).
 * SurfaceView yazilim cizimine katilmaz; bu yuzden iki katman birlestirilir.
 * PNG, MediaStore Pictures/K9 (izin gerekmez). Dosya yazimi arka thread'de.
 */
public final class Screenshot {
    private static final String TAG = "Screenshot";
    public interface Callback { void onDone(boolean ok, String name); }

    private final Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());

    public Screenshot() {
        HandlerThread t = new HandlerThread("k9-screenshot"); t.start();
        worker = new Handler(t.getLooper());
    }

    public void take(Activity a, SurfaceView video, Callback cb) {
        View decor = a.getWindow().getDecorView();
        int w = decor.getWidth(), h = decor.getHeight();
        if (w <= 0 || h <= 0) { cb.onDone(false, "pencere yok"); return; }
        Bitmap frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] loc = new int[2]; video.getLocationInWindow(loc);
        Rect dst = new Rect(loc[0], loc[1], loc[0] + video.getWidth(), loc[1] + video.getHeight());
        Bitmap vid = Bitmap.createBitmap(Math.max(1, video.getWidth()), Math.max(1, video.getHeight()), Bitmap.Config.ARGB_8888);
        PixelCopy.request(video, vid, result -> {
            // PixelCopy sonucu worker thread'de; UI cizimi ana thread'de olmali
            main.post(() -> {
                Canvas c = new Canvas(frame);
                if (result == PixelCopy.SUCCESS) c.drawBitmap(vid, null, dst, null);
                decor.draw(c);                    // SurfaceView seffaf kalir, UI ustune gelir
                vid.recycle();
                worker.post(() -> save(a, frame, cb));
            });
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
        main.post(() -> cb.onDone(r, name));
    }
}
