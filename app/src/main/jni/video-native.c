/* k9native -- K9 kontrol birimi medya katmani (GStreamer Android).
 *
 *  video : udpsrc(H.265 RTP) -> rtpjitterbuffer -> rtph265depay -> h265parse
 *          -> Android MediaCodec (amcviddec-*, yoksa avdec_h265) -> glimagesink (Surface)
 *  ses rx: udpsrc(Opus RTP) -> rtpjitterbuffer -> opusdec -> openslessink
 *  ses tx: openslessrc -> opusenc -> rtpopuspay -> valve -> udpsink   (bas-konus: valve)
 *
 * Tum pipeline islemleri TEK context thread'inde (app_function) yapilir; JNI
 * giris noktalari isi queue_on_context ile kuyruklar ve hemen doner. Java
 * tarafi hicbir zaman gst_element_set_state'in icinde beklemez (ANR yok).
 * Hata -> pipeline yikilir, 1/2/4/8 s backoff ile yeniden kurulur.
 */
#include <string.h>
#include <stdint.h>
#include <jni.h>
#include <pthread.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <gst/gst.h>
#include <gst/video/videooverlay.h>

GST_DEBUG_CATEGORY_STATIC (debug_category);
#define GST_CAT_DEFAULT debug_category
#define TAG "k9native"
#define LOGE(...) __android_log_print (ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print (ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define GET_DATA(env, thiz) ((NativeData *) (intptr_t) (*env)->GetLongField (env, thiz, native_data_field))
#define SET_DATA(env, thiz, d) (*env)->SetLongField (env, thiz, native_data_field, (jlong) (intptr_t) (d))

typedef struct {
    GstElement *pipeline;
    GstElement *sink;          /* glimagesink (GstVideoOverlay) */
    GSource    *bus_source;
    GSource    *retry_source;
    guint       retry_count;
    gchar      *desc;
    gint        frames;        /* sink pad probe sayar (streaming thread, atomik) */
    gint        frames_last;   /* stats timer'in son okudugu */
    gboolean    caps_logged;
} VideoSlot;

typedef struct {
    jobject       app;                 /* NativeBridge global ref */
    GMainContext *context;
    GMainLoop    *main_loop;
    pthread_t     thread;
    ANativeWindow *window;
    VideoSlot     video;
    GstElement   *audio_rx;
    GstElement   *audio_tx;
    GstElement   *tx_valve;
    GSource      *stats_source;
    /* ayarlar (context thread okur; JNI tarafi kuyruklayarak degistirir) */
    gchar        *host;                /* K9 IP */
    gint          video_port, audio_rx_port, audio_tx_port, latency_ms;
    gboolean      running;             /* kullanici "baslat" dedi mi */
    gboolean      listen;              /* K9 sesi dinleniyor mu */
} NativeData;

static JavaVM   *java_vm;
static pthread_key_t current_jni_env;
static jfieldID  native_data_field;
static jmethodID on_message_mid, on_stats_mid;

/* ---------------------------------------------------------------- JNI env */
static JNIEnv *attach_current_thread (void) {
    JNIEnv *env; JavaVMAttachArgs args = { JNI_VERSION_1_6, NULL, NULL };
    if ((*java_vm)->AttachCurrentThread (java_vm, &env, &args) < 0) { LOGE ("AttachCurrentThread basarisiz"); return NULL; }
    return env;
}
static void detach_current_thread (void *env) { (void) env; (*java_vm)->DetachCurrentThread (java_vm); }
static JNIEnv *get_jni_env (void) {
    JNIEnv *env = pthread_getspecific (current_jni_env);
    if (!env) { env = attach_current_thread (); pthread_setspecific (current_jni_env, env); }
    return env;
}

static void ui_message (NativeData *d, const gchar *msg) {
    JNIEnv *env = get_jni_env (); if (!env) return;
    jstring js = (*env)->NewStringUTF (env, msg);
    (*env)->CallVoidMethod (env, d->app, on_message_mid, js);
    if ((*env)->ExceptionCheck (env)) (*env)->ExceptionClear (env);
    (*env)->DeleteLocalRef (env, js);
}

/* ------------------------------------------------------- context kuyrugu */
static void queue_on_context (NativeData *d, GSourceFunc fn, gpointer ud, GDestroyNotify notify) {
    GSource *s = g_idle_source_new ();
    g_source_set_priority (s, G_PRIORITY_DEFAULT);
    g_source_set_callback (s, fn, ud, notify);
    g_source_attach (s, d->context);
    g_source_unref (s);
}

typedef struct { GMutex m; GCond c; gboolean done; GSourceFunc fn; gpointer ud; } SyncReq;
static gboolean sync_trampoline (gpointer p) {
    SyncReq *r = p; r->fn (r->ud);
    g_mutex_lock (&r->m); r->done = TRUE; g_cond_signal (&r->c); g_mutex_unlock (&r->m);
    return G_SOURCE_REMOVE;
}
/* Surface yikimi gibi "dondugumde bitmis olmali" isler icin */
static void run_on_context_sync (NativeData *d, GSourceFunc fn, gpointer ud) {
    SyncReq r = { 0 }; g_mutex_init (&r.m); g_cond_init (&r.c); r.fn = fn; r.ud = ud;
    queue_on_context (d, sync_trampoline, &r, NULL);
    g_mutex_lock (&r.m); while (!r.done) g_cond_wait (&r.c, &r.m); g_mutex_unlock (&r.m);
    g_mutex_clear (&r.m); g_cond_clear (&r.c);
}

/* -------------------------------------------------------------- video */
static void schedule_video_rebuild (NativeData *d);

static GstPadProbeReturn count_frames_probe (GstPad *pad, GstPadProbeInfo *info, gpointer ud) {
    (void) pad; (void) info;
    g_atomic_int_add (&((VideoSlot *) ud)->frames, 1);
    return GST_PAD_PROBE_OK;
}

static void teardown_video (NativeData *d) {
    VideoSlot *v = &d->video;
    if (v->retry_source) { g_source_destroy (v->retry_source); g_source_unref (v->retry_source); v->retry_source = NULL; }
    if (v->bus_source) { g_source_destroy (v->bus_source); g_source_unref (v->bus_source); v->bus_source = NULL; }
    if (v->pipeline) {
        gst_element_set_state (v->pipeline, GST_STATE_NULL);
        if (v->sink) { gst_object_unref (v->sink); v->sink = NULL; }
        gst_object_unref (v->pipeline); v->pipeline = NULL;
    }
    v->frames = v->frames_last = 0; v->caps_logged = FALSE;
}

static void video_error_cb (GstBus *bus, GstMessage *msg, NativeData *d) {
    (void) bus; GError *err = NULL; gchar *dbg = NULL;
    gst_message_parse_error (msg, &err, &dbg);
    gchar *m = g_strdup_printf ("Video hatasi [%s]: %s", GST_OBJECT_NAME (msg->src), err ? err->message : "?");
    LOGE ("%s (%s)", m, dbg ? dbg : "");
    ui_message (d, m); g_free (m); g_clear_error (&err); g_free (dbg);
    schedule_video_rebuild (d);                 /* yikim + backoff'lu yeniden kurma */
}

static gboolean build_video (NativeData *d) {
    VideoSlot *v = &d->video; GError *err = NULL;
    teardown_video (d);
    g_free (v->desc);
    /* Bu cihazda calisan referans (tutorial-5.c) ile ayni sekil: decoder'i
     * decodebin3 secer (donanim amcviddec), sink GL yolu. Ekstra bekci/filtre yok. */
    v->desc = g_strdup_printf (
        "udpsrc port=%d buffer-size=2097152 caps=\"application/x-rtp,media=video,clock-rate=90000,encoding-name=H265,payload=96\" ! "
        "rtpjitterbuffer latency=%d drop-on-latency=true ! "
        "rtph265depay ! h265parse config-interval=-1 ! decodebin3 ! "
        "queue max-size-buffers=2 max-size-time=0 max-size-bytes=0 leaky=downstream ! "
        "glimagesink force-aspect-ratio=false name=vsink sync=false",
        d->video_port, d->latency_ms);
    LOGI ("video pipeline: %s", v->desc);
    v->pipeline = gst_parse_launch (v->desc, &err);
    if (err || !v->pipeline) {
        gchar *m = g_strdup_printf ("Video pipeline kurulamadi: %s", err ? err->message : "?");
        LOGE ("%s", m); ui_message (d, m); g_free (m); g_clear_error (&err);
        if (v->pipeline) { gst_object_unref (v->pipeline); v->pipeline = NULL; }
        return FALSE;
    }
    v->sink = gst_bin_get_by_name (GST_BIN (v->pipeline), "vsink");
    GstPad *pad = gst_element_get_static_pad (v->sink, "sink");
    gst_pad_add_probe (pad, GST_PAD_PROBE_TYPE_BUFFER, count_frames_probe, v, NULL);
    gst_object_unref (pad);
    if (d->window) gst_video_overlay_set_window_handle (GST_VIDEO_OVERLAY (v->sink), (guintptr) d->window);

    GstBus *bus = gst_element_get_bus (v->pipeline);
    v->bus_source = gst_bus_create_watch (bus);
    g_source_set_callback (v->bus_source, (GSourceFunc) gst_bus_async_signal_func, NULL, NULL);
    g_source_attach (v->bus_source, d->context);
    g_signal_connect (bus, "message::error", (GCallback) video_error_cb, d);
    gst_object_unref (bus);
    return TRUE;
}

static gboolean start_video (NativeData *d) {
    if (!d->running || !d->window) return FALSE;
    if (!d->video.pipeline && !build_video (d)) return FALSE;
    if (gst_element_set_state (d->video.pipeline, GST_STATE_PLAYING) == GST_STATE_CHANGE_FAILURE) {
        ui_message (d, "Video PLAYING olamadi"); return FALSE;
    }
    return TRUE;
}

static gboolean video_retry_cb (gpointer ud) {
    NativeData *d = ud; VideoSlot *v = &d->video;
    if (v->retry_source) { g_source_unref (v->retry_source); v->retry_source = NULL; }
    if (!d->running) return G_SOURCE_REMOVE;
    LOGI ("Video yeniden kuruluyor (deneme %u)", v->retry_count);
    if (!start_video (d)) schedule_video_rebuild (d);
    return G_SOURCE_REMOVE;
}

/* Backoff 1,2,4,8,8... s; bekleyen varsa tekrar kuyruklanmaz (debounce) */
static void schedule_video_rebuild (NativeData *d) {
    VideoSlot *v = &d->video;
    if (v->pipeline) {   /* hatali pipeline'i hemen durdur, sinyal cikisinda yik */
        gst_element_set_state (v->pipeline, GST_STATE_NULL);
    }
    if (v->retry_source || !d->running) return;
    guint shift = v->retry_count < 3 ? v->retry_count : 3;
    if (v->retry_count < 1000) v->retry_count++;
    GSource *s = g_timeout_source_new_seconds (1u << shift);
    g_source_set_callback (s, video_retry_cb, d, NULL);
    g_source_attach (s, d->context);
    v->retry_source = s;
}

/* ------------------------------------------------------------- ses */
static gboolean build_audio (NativeData *d) {
    GError *err = NULL;
    if (d->audio_rx) { gst_element_set_state (d->audio_rx, GST_STATE_NULL); gst_object_unref (d->audio_rx); d->audio_rx = NULL; }
    if (d->audio_tx) { gst_element_set_state (d->audio_tx, GST_STATE_NULL); gst_object_unref (d->audio_tx); d->audio_tx = NULL; d->tx_valve = NULL; }

    gchar *rx = g_strdup_printf (
        "udpsrc port=%d caps=\"application/x-rtp,media=audio,clock-rate=48000,encoding-name=OPUS,payload=96\" ! "
        "rtpjitterbuffer latency=60 do-lost=true ! rtpopusdepay ! opusdec plc=true use-inband-fec=true ! "
        "audioconvert ! audioresample ! openslessink sync=false buffer-time=60000 latency-time=20000",
        d->audio_rx_port);
    d->audio_rx = gst_parse_launch (rx, &err); g_free (rx);
    if (err) { LOGE ("audio_rx kurulamadi: %s", err->message); g_clear_error (&err); if (d->audio_rx) { gst_object_unref (d->audio_rx); d->audio_rx = NULL; } }

    /* Mikrofon surekli acik, udpsink onundeki valve bas-konus: aninda, acilis gecikmesi yok */
    gchar *tx = g_strdup_printf (
        "openslessrc ! queue max-size-buffers=4 max-size-time=0 max-size-bytes=0 leaky=downstream ! "
        "audioconvert ! audioresample ! audio/x-raw,rate=48000,channels=1,format=S16LE ! "
        "opusenc bitrate=32000 audio-type=2048 frame-size=20 inband-fec=true packet-loss-percentage=10 ! "
        "rtpopuspay pt=96 ! valve name=ptt drop=true ! udpsink host=%s port=%d sync=false async=false",
        d->host, d->audio_tx_port);
    d->audio_tx = gst_parse_launch (tx, &err); g_free (tx);
    if (err) { LOGE ("audio_tx kurulamadi: %s", err->message); g_clear_error (&err); if (d->audio_tx) { gst_object_unref (d->audio_tx); d->audio_tx = NULL; } }
    else d->tx_valve = gst_bin_get_by_name (GST_BIN (d->audio_tx), "ptt");
    return d->audio_rx != NULL;
}

static void set_audio_state (NativeData *d, GstState st) {
    if (d->audio_rx) gst_element_set_state (d->audio_rx, (st == GST_STATE_PLAYING && !d->listen) ? GST_STATE_READY : st);
    if (d->audio_tx) gst_element_set_state (d->audio_tx, st);
}

/* ------------------------------------------------------------ stats */
static gboolean stats_cb (gpointer ud) {
    NativeData *d = ud; VideoSlot *v = &d->video;
    gint now = g_atomic_int_get (&v->frames);
    gint fps = now - v->frames_last; v->frames_last = now;
    gint w = 0, h = 0;
    if (v->sink) {
        GstPad *pad = gst_element_get_static_pad (v->sink, "sink");
        GstCaps *caps = gst_pad_get_current_caps (pad);
        if (caps) {
            GstStructure *s = gst_caps_get_structure (caps, 0); gst_structure_get_int (s, "width", &w); gst_structure_get_int (s, "height", &h);
            if (!v->caps_logged) { gchar *cs = gst_caps_to_string (caps); LOGI ("video sink caps: %s", cs); g_free (cs); v->caps_logged = TRUE; }
            gst_caps_unref (caps);
        }
        gst_object_unref (pad);
    }
    JNIEnv *env = get_jni_env ();
    if (env) { (*env)->CallVoidMethod (env, d->app, on_stats_mid, fps, w, h); if ((*env)->ExceptionCheck (env)) (*env)->ExceptionClear (env); }
    return G_SOURCE_CONTINUE;
}

/* --------------------------------------------------- context thread */
static void *app_function (void *ud) {
    NativeData *d = ud;
    g_main_context_push_thread_default (d->context);
    build_audio (d);
    d->stats_source = g_timeout_source_new_seconds (1);
    g_source_set_callback (d->stats_source, stats_cb, d, NULL);
    g_source_attach (d->stats_source, d->context);
    g_main_loop_run (d->main_loop);
    /* yikim, context hala ayakta */
    if (d->stats_source) { g_source_destroy (d->stats_source); g_source_unref (d->stats_source); d->stats_source = NULL; }
    teardown_video (d);
    if (d->tx_valve) { gst_object_unref (d->tx_valve); d->tx_valve = NULL; }
    if (d->audio_rx) { gst_element_set_state (d->audio_rx, GST_STATE_NULL); gst_object_unref (d->audio_rx); d->audio_rx = NULL; }
    if (d->audio_tx) { gst_element_set_state (d->audio_tx, GST_STATE_NULL); gst_object_unref (d->audio_tx); d->audio_tx = NULL; }
    g_main_context_pop_thread_default (d->context);
    g_main_context_unref (d->context); d->context = NULL;
    return NULL;
}

/* ------------------------------------------------- kuyruklanan isler */
typedef struct { NativeData *d; gchar *host; gint vport, arx, atx, lat; } ConfigReq;
static void config_req_free (gpointer p) { ConfigReq *r = p; g_free (r->host); g_free (r); }
static gboolean configure_cb (gpointer p) {
    ConfigReq *r = p; NativeData *d = r->d;
    gboolean changed = g_strcmp0 (d->host, r->host) != 0 || d->video_port != r->vport || d->audio_rx_port != r->arx ||
                       d->audio_tx_port != r->atx || d->latency_ms != r->lat;
    g_free (d->host); d->host = g_strdup (r->host);
    d->video_port = r->vport; d->audio_rx_port = r->arx; d->audio_tx_port = r->atx; d->latency_ms = r->lat;
    if (changed) {
        build_audio (d);
        if (d->running) { set_audio_state (d, GST_STATE_PLAYING); teardown_video (d); start_video (d); }
    }
    return G_SOURCE_REMOVE;
}

/* Mikrofon izni sonradan verildiginde: ses pipeline'larini yeniden kur */
static gboolean audio_restart_cb (gpointer p) {
    NativeData *d = p;
    build_audio (d);
    if (d->running) set_audio_state (d, GST_STATE_PLAYING);
    return G_SOURCE_REMOVE;
}

static gboolean start_cb (gpointer p) {
    NativeData *d = p; d->running = TRUE; d->video.retry_count = 0;
    set_audio_state (d, GST_STATE_PLAYING);
    if (!start_video (d) && d->window) schedule_video_rebuild (d);
    return G_SOURCE_REMOVE;
}
static gboolean stop_cb (gpointer p) {
    NativeData *d = p; d->running = FALSE;
    teardown_video (d); set_audio_state (d, GST_STATE_READY);
    return G_SOURCE_REMOVE;
}

typedef struct { NativeData *d; ANativeWindow *w; } SurfaceReq;
static gboolean surface_set_cb (gpointer p) {
    SurfaceReq *r = p; NativeData *d = r->d;
    if (d->window && d->window == r->w) {
        ANativeWindow_release (r->w);            /* ayni pencere: fazladan alinan ref'i birak */
    } else {
        if (d->window) { teardown_video (d); ANativeWindow_release (d->window); }   /* GL baglami pencereye bagli */
        d->window = r->w;
    }
    if (d->video.sink) { gst_video_overlay_set_window_handle (GST_VIDEO_OVERLAY (d->video.sink), (guintptr) d->window); gst_video_overlay_expose (GST_VIDEO_OVERLAY (d->video.sink)); }
    else if (d->running) start_video (d);
    return G_SOURCE_REMOVE;
}
static gboolean surface_release_cb (gpointer p) {
    NativeData *d = p;
    teardown_video (d);                          /* GL baglami surface'e bagli: once yik */
    if (d->window) { ANativeWindow_release (d->window); d->window = NULL; }
    return G_SOURCE_REMOVE;
}

typedef struct { NativeData *d; gboolean on; } PttReq;
static gboolean ptt_cb (gpointer p) {
    PttReq *r = p;
    if (r->d->tx_valve) g_object_set (r->d->tx_valve, "drop", !r->on, NULL);
    return G_SOURCE_REMOVE;
}
/* K9 sesini dinleme ac/kapa (rx pipeline PLAYING/READY) */
static gboolean listen_cb (gpointer p) {
    PttReq *r = p; NativeData *d = r->d;
    d->listen = r->on;
    if (d->audio_rx && d->running) gst_element_set_state (d->audio_rx, r->on ? GST_STATE_PLAYING : GST_STATE_READY);
    return G_SOURCE_REMOVE;
}

/* ------------------------------------------------------- JNI girisleri */
static void n_init (JNIEnv *env, jobject thiz) {
    NativeData *d = g_new0 (NativeData, 1);
    d->app = (*env)->NewGlobalRef (env, thiz);
    d->listen = TRUE;
    d->host = g_strdup ("192.168.2.199"); d->video_port = 5001; d->audio_rx_port = 14551; d->audio_tx_port = 14552; d->latency_ms = 200;
    d->context = g_main_context_new ();
    d->main_loop = g_main_loop_new (d->context, FALSE);
    SET_DATA (env, thiz, d);
    GST_DEBUG_CATEGORY_INIT (debug_category, TAG, 0, "K9 native");
    pthread_create (&d->thread, NULL, app_function, d);
}

static void n_finalize (JNIEnv *env, jobject thiz) {
    NativeData *d = GET_DATA (env, thiz); if (!d) return;
    g_main_loop_quit (d->main_loop);
    pthread_join (d->thread, NULL);
    g_main_loop_unref (d->main_loop);
    if (d->window) { ANativeWindow_release (d->window); d->window = NULL; }
    (*env)->DeleteGlobalRef (env, d->app);
    g_free (d->video.desc); g_free (d->host); g_free (d);
    SET_DATA (env, thiz, NULL);
}

static void n_configure (JNIEnv *env, jobject thiz, jstring host, jint vport, jint arx, jint atx, jint lat) {
    NativeData *d = GET_DATA (env, thiz); if (!d || !d->context || !host) return;
    const char *h = (*env)->GetStringUTFChars (env, host, NULL); if (!h) return;
    ConfigReq *r = g_new0 (ConfigReq, 1);
    r->d = d; r->host = g_strdup (h); r->vport = vport; r->arx = arx; r->atx = atx; r->lat = lat;
    (*env)->ReleaseStringUTFChars (env, host, h);
    queue_on_context (d, configure_cb, r, config_req_free);
}
static void n_start (JNIEnv *env, jobject thiz) { NativeData *d = GET_DATA (env, thiz); if (d && d->context) queue_on_context (d, start_cb, d, NULL); }
static void n_stop (JNIEnv *env, jobject thiz)  { NativeData *d = GET_DATA (env, thiz); if (d && d->context) queue_on_context (d, stop_cb, d, NULL); }

static void n_surface_set (JNIEnv *env, jobject thiz, jobject surface) {
    NativeData *d = GET_DATA (env, thiz); if (!d || !d->context) return;
    SurfaceReq *r = g_new0 (SurfaceReq, 1); r->d = d;
    r->w = surface ? ANativeWindow_fromSurface (env, surface) : NULL;   /* JNIEnv ister: bu thread'de */
    queue_on_context (d, surface_set_cb, r, g_free);
}
static void n_surface_release (JNIEnv *env, jobject thiz) {
    NativeData *d = GET_DATA (env, thiz); if (!d || !d->context) return;
    run_on_context_sync (d, surface_release_cb, d);   /* Android: dondukten sonra surface kullanilmaz */
}
static void n_listen (JNIEnv *env, jobject thiz, jboolean on) {
    NativeData *d = GET_DATA (env, thiz); if (!d || !d->context) return;
    PttReq *r = g_new0 (PttReq, 1); r->d = d; r->on = on;
    queue_on_context (d, listen_cb, r, g_free);
}
static void n_audio_restart (JNIEnv *env, jobject thiz) { NativeData *d = GET_DATA (env, thiz); if (d && d->context) queue_on_context (d, audio_restart_cb, d, NULL); }
static void n_ptt (JNIEnv *env, jobject thiz, jboolean on) {
    NativeData *d = GET_DATA (env, thiz); if (!d || !d->context) return;
    PttReq *r = g_new0 (PttReq, 1); r->d = d; r->on = on;
    queue_on_context (d, ptt_cb, r, g_free);
}

static jboolean n_class_init (JNIEnv *env, jclass klass) {
    native_data_field = (*env)->GetFieldID (env, klass, "nativeData", "J");
    on_message_mid = (*env)->GetMethodID (env, klass, "onNativeMessage", "(Ljava/lang/String;)V");
    on_stats_mid = (*env)->GetMethodID (env, klass, "onNativeStats", "(III)V");
    if (!native_data_field || !on_message_mid || !on_stats_mid) { LOGE ("NativeBridge alanlari eksik"); return JNI_FALSE; }
    return JNI_TRUE;
}

static JNINativeMethod methods[] = {
    { "nativeClassInit", "()Z", (void *) n_class_init },
    { "nativeInit", "()V", (void *) n_init },
    { "nativeFinalize", "()V", (void *) n_finalize },
    { "nativeConfigure", "(Ljava/lang/String;IIII)V", (void *) n_configure },
    { "nativeStart", "()V", (void *) n_start },
    { "nativeStop", "()V", (void *) n_stop },
    { "nativeSurfaceSet", "(Ljava/lang/Object;)V", (void *) n_surface_set },
    { "nativeSurfaceRelease", "()V", (void *) n_surface_release },
    { "nativePtt", "(Z)V", (void *) n_ptt },
    { "nativeAudioRestart", "()V", (void *) n_audio_restart },
    { "nativeListen", "(Z)V", (void *) n_listen },
};

jint JNI_OnLoad (JavaVM *vm, void *reserved) {
    (void) reserved; JNIEnv *env = NULL; java_vm = vm;
    if ((*vm)->GetEnv (vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) { LOGE ("JNIEnv alinamadi"); return 0; }
    jclass klass = (*env)->FindClass (env, "com/esetron/k9/video/NativeBridge");
    if (!klass) { LOGE ("NativeBridge sinifi bulunamadi"); return 0; }
    (*env)->RegisterNatives (env, klass, methods, G_N_ELEMENTS (methods));
    pthread_key_create (&current_jni_env, detach_current_thread);
    return JNI_VERSION_1_6;
}
