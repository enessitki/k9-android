package com.esetron.k9.video;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import org.freedesktop.gstreamer.GStreamer;

/**
 * JNI koprusu (libk9native). Tum native cagrilar aninda doner; is native
 * context thread'inde kuyruklanir. Geri cagrilar (onNativeMessage/onNativeStats)
 * native thread'den gelir, burada ana thread'e tasinir.
 */
public final class NativeBridge {
    private static final String TAG = "NativeBridge";

    public interface Listener {
        void onMessage(String msg);
        void onStats(int fps, int width, int height);
    }

    @SuppressWarnings("unused")   // JNI yazar/okur
    private long nativeData;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Listener listener;
    private boolean initialized;

    private static native boolean nativeClassInit();
    private native void nativeInit();
    private native void nativeFinalize();
    private native void nativeConfigure(String host, int videoPort, int audioRxPort, int audioTxPort, int latencyMs);
    private native void nativeStart();
    private native void nativeStop();
    private native void nativeSurfaceSet(Object surface);
    private native void nativeSurfaceRelease();
    private native void nativePtt(boolean on);
    private native void nativeAudioRestart();
    private native void nativeListen(boolean on);

    static {
        System.loadLibrary("gstreamer_android");
        System.loadLibrary("k9native");
        if (!nativeClassInit()) throw new IllegalStateException("k9native class init basarisiz");
    }

    /** GStreamer'i baslatir (androidmedia eklentisi Context ister) ve native tarafi kurar. */
    public synchronized boolean init(Context ctx, Listener l) {
        if (initialized) return true;
        listener = l;
        try {
            GStreamer.init(ctx);
        } catch (Exception e) {
            Log.e(TAG, "GStreamer.init basarisiz", e);
            return false;
        }
        nativeInit();
        initialized = true;
        return true;
    }

    public synchronized void release() {
        if (!initialized) return;
        nativeFinalize();
        initialized = false;
    }

    public void configure(String host, int videoPort, int audioRxPort, int audioTxPort, int latencyMs) {
        if (initialized) nativeConfigure(host, videoPort, audioRxPort, audioTxPort, latencyMs);
    }
    public void start() { if (initialized) nativeStart(); }
    public void stop() { if (initialized) nativeStop(); }
    public void surfaceSet(Surface s) { if (initialized) nativeSurfaceSet(s); }
    /** Senkron: dondugunde native taraf surface'i birakmis olur (surfaceDestroyed icin). */
    public void surfaceRelease() { if (initialized) nativeSurfaceRelease(); }
    public void ptt(boolean on) { if (initialized) nativePtt(on); }
    /** RECORD_AUDIO izni sonradan verilince ses pipeline'larini yeniden kurar. */
    public void audioRestart() { if (initialized) nativeAudioRestart(); }
    /** K9 sesini dinleme ac/kapa. */
    public void listen(boolean on) { if (initialized) nativeListen(on); }

    // ---- native -> Java (native thread) ----
    @SuppressWarnings("unused")
    private void onNativeMessage(String msg) {
        Log.w(TAG, msg);
        Listener l = listener;
        if (l != null) main.post(() -> l.onMessage(msg));
    }

    @SuppressWarnings("unused")
    private void onNativeStats(int fps, int w, int h) {
        Listener l = listener;
        if (l != null) main.post(() -> l.onStats(fps, w, h));
    }
}
