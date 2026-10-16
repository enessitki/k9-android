/* k9native -- K9 kontrol birimi medya katmani (GStreamer Android).
 *
 *  video : udpsrc(H.265 RTP) -> rtpjitterbuffer -> rtph265depay -> h265parse -> appsink
 *          ==AU==> AMediaCodec (donanim HEVC) -> ANativeWindow (SurfaceView) <- SurfaceFlinger
 *          GStreamer yalniz ag+depay+parse; cozme/gosterme GStreamer disinda. GL yok,
 *          SurfaceTexture yok, kare kopyasi yok (Rockchip projesiyle ayni mimari).
 *  ses rx: udpsrc(Opus RTP) -> rtpjitterbuffer -> opusdec -> openslessink
 *  ses tx: openslessrc -> opusenc -> rtpopuspay -> udpsink   (bas-konus: NULL<->PLAYING)
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
#include <gst/app/gstappsink.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaError.h>

GST_DEBUG_CATEGORY_STATIC (debug_category);
#define GST_CAT_DEFAULT debug_category
#define TAG "k9native"
#define LOGE(...) __android_log_print (ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print (ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define GET_DATA(env, thiz) ((NativeData *) (intptr_t) (*env)->GetLongField (env, thiz, native_data_field))
#define SET_DATA(env, thiz, d) (*env)->SetLongField (env, thiz, native_data_field, (jlong) (intptr_t) (d))

/* Donanim decoder (AMediaCodec -> ANativeWindow, sifir kopya).
 * feed : appsink streaming thread'i (hwdec_feed)   - input
 * out  : kendi thread'i (hwdec_output_thread)      - dequeueOutput + render
 * ctx  : context thread (yarat/yok et/yuzey)       - yasam dongusu (VideoSlot.dec_lock) */
#define HWDEC_MIME              "video/hevc"
#define HWDEC_INPUT_TIMEOUT_US  20000     /* bos input beklemesi; asilirsa AU dusur (geri basinc yok) */
#define HWDEC_OUTPUT_TIMEOUT_US 10000     /* out thread dequeue periyodu */
#define HWDEC_KEYFRAME_WAIT_US  1500000   /* csd yokken IRAP icin en fazla bu kadar bekle */
#define STALL_TIMEOUT_US        (4 * G_USEC_PER_SEC)   /* AU veriliyor ama kare yok -> codec yenile */
#define RECYCLE_LIMIT           2                      /* 60 s icinde bu kadar yenileme -> pipeline rebuild */
#define RECYCLE_WINDOW_US       (60 * G_USEC_PER_SEC)

typedef struct {
    AMediaCodec *codec;
    GThread     *out_thread;
    gint         running;           /* atomic */
    gboolean     need_keyframe;     /* yalniz feed thread'i */
    gint64       kf_wait_since_us;
    gboolean     kf_warned;
    gint64      *last_frame_us;     /* slot alani, out thread yazar (atomic) */
    gint64      *last_fed_us;       /* slot alani, feed yazar (atomic) */
    gint        *out_w, *out_h;     /* slot alani, out thread yazar (atomic) */
    gint64       frames_out;        /* atomic */
    gint64       aus_in, aus_fed, aus_dropped, in_timeout_run;   /* atomic */
} HwDecoder;

typedef struct {
    GstElement *pipeline;
    GstElement *appsink;       /* ref'li */
    GSource    *bus_source;
    GSource    *retry_source;
    guint       retry_count;
    gchar      *desc;

    GMutex      dec_lock;      /* dec / csd / accept_input / NativeData.window: appsink thread'i ile paylasilir */
    HwDecoder  *dec;           /* NULL = yok; ilk AU'da pencere varsa acilir */
    GByteArray *csd;           /* son gorulen VPS/SPS/PPS (Annex-B) */
    gboolean    accept_input;  /* FALSE: AU dusur, decoder acma */
    gint64      dec_fail_us;   /* son basarisiz acma; 1 s backoff */

    gint64      last_input_us, last_fed_us, last_frame_us;   /* atomic (watchdog okur) */
    gint        out_w, out_h;                                 /* atomic */
    gint64      frames_last;   /* stats: son okunan kare sayisi */
    gint        recycle_count; gint64 last_recycle_us;
    gboolean    format_logged;
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

/* Annex-B AU icinde IRAP (nal_unit_type 16..23: BLA/IDR/CRA) var mi? */
static gboolean au_has_irap (const guint8 *d, gsize n) {
    for (gsize i = 0; i + 3 < n; i++) {
        if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
            guint t = (d[i + 3] >> 1) & 0x3f;
            if (t >= 16 && t <= 23) return TRUE;
            i += 2;
        }
    }
    return FALSE;
}

/* AU icindeki VPS(32)/SPS(33)/PPS(34) NAL'larini baslangic koduyla out'a yazar.
 * TRUE = en az bir parametre seti bulundu (out once temizlenir). */
static gboolean au_extract_param_sets (const guint8 *d, gsize n, GByteArray *out) {
    gboolean found = FALSE; gsize i = 0;
    while (i + 3 < n) {
        if (!(d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1)) { i++; continue; }
        gsize sc = (i > 0 && d[i - 1] == 0) ? i - 1 : i;
        gsize nal = i + 3; if (nal >= n) break;
        guint t = (d[nal] >> 1) & 0x3f;
        gsize j = nal + 1;
        while (j + 2 < n && !(d[j] == 0 && d[j + 1] == 0 && d[j + 2] == 1)) j++;
        gsize end = (j + 2 < n) ? ((d[j - 1] == 0) ? j - 1 : j) : n;
        if (t >= 32 && t <= 34) {
            if (!found) g_byte_array_set_size (out, 0);
            g_byte_array_append (out, d + sc, (guint) (end - sc));
            found = TRUE;
        }
        i = end;
    }
    return found;
}

static gpointer hwdec_output_thread (gpointer ud) {
    HwDecoder *d = ud; AMediaCodecBufferInfo info;
    while (g_atomic_int_get (&d->running)) {
        ssize_t idx = AMediaCodec_dequeueOutputBuffer (d->codec, &info, HWDEC_OUTPUT_TIMEOUT_US);
        if (idx >= 0) {
            gboolean render = info.size > 0;
            /* render=TRUE: kare dogrudan SurfaceView'a, bir sonraki vsync'te. Kopya yok. */
            if (AMediaCodec_releaseOutputBuffer (d->codec, (size_t) idx, render) != AMEDIA_OK)
                LOGE ("releaseOutputBuffer hatasi");
            if (render) {
                __atomic_add_fetch (&d->frames_out, 1, __ATOMIC_RELAXED);
                __atomic_store_n (d->last_frame_us, g_get_monotonic_time (), __ATOMIC_RELAXED);
            }
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) break;
        } else if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat *f = AMediaCodec_getOutputFormat (d->codec);
            if (f) {
                int32_t w = 0, h = 0;
                /* crop varsa gercek goruntu boyutu odur (stride/slice yuvarlamasi degil) */
                int32_t l = 0, t = 0, r = 0, b = 0;
                if (AMediaFormat_getInt32 (f, "crop-left", &l) && AMediaFormat_getInt32 (f, "crop-right", &r) &&
                    AMediaFormat_getInt32 (f, "crop-top", &t) && AMediaFormat_getInt32 (f, "crop-bottom", &b)) {
                    w = r - l + 1; h = b - t + 1;
                } else {
                    AMediaFormat_getInt32 (f, AMEDIAFORMAT_KEY_WIDTH, &w);
                    AMediaFormat_getInt32 (f, AMEDIAFORMAT_KEY_HEIGHT, &h);
                }
                __atomic_store_n (d->out_w, (gint) w, __ATOMIC_RELAXED);
                __atomic_store_n (d->out_h, (gint) h, __ATOMIC_RELAXED);
                LOGI ("decoder cikis formati: %s", AMediaFormat_toString (f));
                AMediaFormat_delete (f);
            }
        } else if (idx != AMEDIACODEC_INFO_TRY_AGAIN_LATER && idx != AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            /* Codec hata durumu: watchdog kare gelmedigini gorup yeniler. CPU yakma. */
            LOGE ("dequeueOutputBuffer hata %zd", idx);
            g_usleep (HWDEC_OUTPUT_TIMEOUT_US);
        }
    }
    return NULL;
}

static HwDecoder *hwdec_new (VideoSlot *v, ANativeWindow *window, gint width, gint height) {
    AMediaCodec *codec = AMediaCodec_createDecoderByType (HWDEC_MIME);
    if (!codec) { LOGE ("HEVC donanim decoder yaratilamadi"); return NULL; }

    AMediaFormat *fmt = AMediaFormat_new ();
    AMediaFormat_setString (fmt, AMEDIAFORMAT_KEY_MIME, HWDEC_MIME);
    AMediaFormat_setInt32 (fmt, AMEDIAFORMAT_KEY_WIDTH, width);
    AMediaFormat_setInt32 (fmt, AMEDIAFORMAT_KEY_HEIGHT, height);
    AMediaFormat_setInt32 (fmt, "low-latency", 1);   /* Android 11+; desteklemeyen yok sayar */
    AMediaFormat_setInt32 (fmt, "priority", 0);      /* 0 = gercek zamanli */
    /* Bilinen VPS/SPS/PPS: codec IDR gelmeden de baslasin (yenileme sonrasi kritik) */
    if (v->csd && v->csd->len > 0) AMediaFormat_setBuffer (fmt, "csd-0", v->csd->data, v->csd->len);

    media_status_t st = AMediaCodec_configure (codec, fmt, window, NULL, 0);
    AMediaFormat_delete (fmt);
    if (st != AMEDIA_OK) { LOGE ("codec configure hatasi %d", (int) st); AMediaCodec_delete (codec); return NULL; }
    if ((st = AMediaCodec_start (codec)) != AMEDIA_OK) { LOGE ("codec start hatasi %d", (int) st); AMediaCodec_delete (codec); return NULL; }

    HwDecoder *d = g_new0 (HwDecoder, 1);
    d->codec = codec;
    d->need_keyframe = !(v->csd && v->csd->len > 0);   /* csd varsa IRAP sart degil: akisi hemen ver */
    d->kf_wait_since_us = g_get_monotonic_time ();
    d->last_frame_us = &v->last_frame_us; d->last_fed_us = &v->last_fed_us;
    d->out_w = &v->out_w; d->out_h = &v->out_h;
    g_atomic_int_set (&d->running, 1);
    d->out_thread = g_thread_try_new ("hwdec-out", hwdec_output_thread, d, NULL);
    if (!d->out_thread) {
        LOGE ("hwdec out thread acilamadi");
        AMediaCodec_stop (codec); AMediaCodec_delete (codec); g_free (d); return NULL;
    }
    LOGI ("donanim decoder acildi %dx%d surface=%p csd=%u byte", width, height, window, v->csd ? v->csd->len : 0);
    return d;
}

/* AU'yu codec'e verir. ASLA uzun bloklamaz (en fazla HWDEC_INPUT_TIMEOUT_US).
 * Paket kaybinda IRAP beklenmez (kullanici tercihi: bekci yok); decoder kisa
 * artifact ile bir sonraki IRAP'ta toparlar. IRAP yalniz csd bilinmeden
 * acilan taze codec icin (sinirli sure) beklenir. */
static void hwdec_feed (HwDecoder *d, const guint8 *data, gsize size, gint64 pts_us) {
    __atomic_add_fetch (&d->aus_in, 1, __ATOMIC_RELAXED);
    if (d->need_keyframe) {
        if (au_has_irap (data, size)) d->need_keyframe = FALSE;
        else if (g_get_monotonic_time () - d->kf_wait_since_us < HWDEC_KEYFRAME_WAIT_US) {
            __atomic_add_fetch (&d->aus_dropped, 1, __ATOMIC_RELAXED); return;
        } else {
            if (!d->kf_warned) { LOGE ("%d ms icinde IRAP gelmedi, akis zorla veriliyor", HWDEC_KEYFRAME_WAIT_US / 1000); d->kf_warned = TRUE; }
            d->need_keyframe = FALSE;
        }
    }
    ssize_t idx = AMediaCodec_dequeueInputBuffer (d->codec, HWDEC_INPUT_TIMEOUT_US);
    if (idx < 0) {
        __atomic_add_fetch (&d->aus_dropped, 1, __ATOMIC_RELAXED);
        gint64 run = __atomic_add_fetch (&d->in_timeout_run, 1, __ATOMIC_RELAXED);
        if (run == 1 || run == 30 || run == 300)
            LOGE ("codec input buffer vermiyor (%zd), ust uste %" G_GINT64_FORMAT " | AU in/fed/kare=%" G_GINT64_FORMAT "/%" G_GINT64_FORMAT "/%" G_GINT64_FORMAT,
                  idx, run, __atomic_load_n (&d->aus_in, __ATOMIC_RELAXED), __atomic_load_n (&d->aus_fed, __ATOMIC_RELAXED),
                  __atomic_load_n (&d->frames_out, __ATOMIC_RELAXED));
        return;
    }
    if (__atomic_load_n (&d->in_timeout_run, __ATOMIC_RELAXED)) __atomic_store_n (&d->in_timeout_run, 0, __ATOMIC_RELAXED);
    size_t cap = 0;
    guint8 *buf = AMediaCodec_getInputBuffer (d->codec, (size_t) idx, &cap);
    if (!buf || cap < size) {
        LOGE ("input buffer yetersiz (%zu < %zu)", cap, size);
        AMediaCodec_queueInputBuffer (d->codec, (size_t) idx, 0, 0, (uint64_t) pts_us, 0);   /* slotu geri ver */
        __atomic_add_fetch (&d->aus_dropped, 1, __ATOMIC_RELAXED);
        return;
    }
    memcpy (buf, data, size);   /* tek kopya: sikistirilmis AU (birkac KB) */
    if (AMediaCodec_queueInputBuffer (d->codec, (size_t) idx, 0, size, (uint64_t) pts_us, 0) != AMEDIA_OK) { LOGE ("queueInputBuffer hatasi"); return; }
    __atomic_add_fetch (&d->aus_fed, 1, __ATOMIC_RELAXED);
    __atomic_store_n (d->last_fed_us, g_get_monotonic_time (), __ATOMIC_RELAXED);
}

static gpointer hwdec_close_thread (gpointer p) {
    HwDecoder *d = p; gint64 t0 = g_get_monotonic_time ();
    AMediaCodec_stop (d->codec); AMediaCodec_delete (d->codec);
    gint64 ms = (g_get_monotonic_time () - t0) / 1000;
    if (ms > 200) LOGE ("codec kapatma %lld ms surdu (HAL yavas)", (long long) ms);
    g_free (d);
    return NULL;
}

/* Out thread'i durdurur (sinirli bekleme), codec'i AYRI thread'de kapatir:
 * asili HAL context thread'ini rehin alamaz. */
static void hwdec_destroy_async (HwDecoder *d) {
    g_atomic_int_set (&d->running, 0);
    g_thread_join (d->out_thread); d->out_thread = NULL;
    GThread *th = g_thread_try_new ("hwdec-close", hwdec_close_thread, d, NULL);
    if (th) g_thread_unref (th); else hwdec_close_thread (d);
}

/* appsink'e AU geldi (streaming thread). Bloklamaz: decoder yoksa/mesgulse dusurur. */
static GstFlowReturn appsink_new_sample_cb (GstAppSink *sink, gpointer ud) {
    NativeData *nd = ud; VideoSlot *v = &nd->video;
    GstSample *sample = gst_app_sink_pull_sample (sink);
    if (!sample) return GST_FLOW_OK;
    GstBuffer *buf = gst_sample_get_buffer (sample);
    gint64 now = g_get_monotonic_time ();
    __atomic_store_n (&v->last_input_us, now, __ATOMIC_RELAXED);

    g_mutex_lock (&v->dec_lock);
    if (buf && v->accept_input) {
        GstMapInfo map;
        if (gst_buffer_map (buf, &map, GST_MAP_READ)) {
            if (!v->csd) v->csd = g_byte_array_new ();
            au_extract_param_sets (map.data, map.size, v->csd);   /* parametre seti degisirse guncel kalsin */
            if (!v->dec && nd->window && now - v->dec_fail_us > G_USEC_PER_SEC) {
                gint w = 0, h = 0; GstCaps *caps = gst_sample_get_caps (sample);
                if (caps) { GstStructure *st = gst_caps_get_structure (caps, 0); gst_structure_get_int (st, "width", &w); gst_structure_get_int (st, "height", &h); }
                if (w <= 0 || h <= 0) { w = 1280; h = 720; }   /* ipucu; codec gercek boyutu SPS'ten alir */
                v->dec = hwdec_new (v, nd->window, w, h);
                if (v->dec) { __atomic_store_n (&v->last_frame_us, now, __ATOMIC_RELAXED); __atomic_store_n (&v->last_fed_us, now, __ATOMIC_RELAXED); }
                else v->dec_fail_us = now;
            }
            if (v->dec) hwdec_feed (v->dec, map.data, map.size, now);
            gst_buffer_unmap (buf, &map);
        }
    }
    g_mutex_unlock (&v->dec_lock);
    gst_sample_unref (sample);
    return GST_FLOW_OK;
}

static void teardown_video (NativeData *d) {
    VideoSlot *v = &d->video;
    if (v->retry_source) { g_source_destroy (v->retry_source); g_source_unref (v->retry_source); v->retry_source = NULL; }
    if (v->bus_source) { g_source_destroy (v->bus_source); g_source_unref (v->bus_source); v->bus_source = NULL; }
    /* 1. once girisi kapat ve codec'i (ayri thread'de) kapat */
    g_mutex_lock (&v->dec_lock);
    v->accept_input = FALSE;
    if (v->dec) { hwdec_destroy_async (v->dec); v->dec = NULL; }
    if (v->csd) { g_byte_array_free (v->csd, TRUE); v->csd = NULL; }
    g_mutex_unlock (&v->dec_lock);
    /* 2. appsink callback'lerini sok, pipeline'i yik (appsink bloklamadigi icin NULL hizli) */
    if (v->appsink) {
        GstAppSinkCallbacks none = { 0 };
        gst_app_sink_set_callbacks (GST_APP_SINK (v->appsink), &none, NULL, NULL);
        gst_object_unref (v->appsink); v->appsink = NULL;
    }
    if (v->pipeline) { gst_element_set_state (v->pipeline, GST_STATE_NULL); gst_object_unref (v->pipeline); v->pipeline = NULL; }
    v->frames_last = 0; v->format_logged = FALSE; v->recycle_count = 0;
    __atomic_store_n (&v->out_w, 0, __ATOMIC_RELAXED); __atomic_store_n (&v->out_h, 0, __ATOMIC_RELAXED);
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
    /* GStreamer yalniz ag + depay + parse. AU'lar appsink'ten AMediaCodec'e gider.
     * appsink max-buffers=2 drop=true: codec yetisemezse en eski AU atilir, geri basinc yok. */
    v->desc = g_strdup_printf (
        "udpsrc port=%d buffer-size=2097152 caps=\"application/x-rtp,media=video,clock-rate=90000,encoding-name=H265,payload=96\" ! "
        "rtpjitterbuffer latency=%d drop-on-latency=true ! "
        "rtph265depay ! h265parse config-interval=-1 ! "
        "video/x-h265,stream-format=byte-stream,alignment=au ! "
        "appsink name=asink sync=false max-buffers=2 drop=true emit-signals=false",
        d->video_port, d->latency_ms);
    LOGI ("video pipeline: %s", v->desc);
    v->pipeline = gst_parse_launch (v->desc, &err);
    if (err || !v->pipeline) {
        gchar *m = g_strdup_printf ("Video pipeline kurulamadi: %s", err ? err->message : "?");
        LOGE ("%s", m); ui_message (d, m); g_free (m); g_clear_error (&err);
        if (v->pipeline) { gst_object_unref (v->pipeline); v->pipeline = NULL; }
        return FALSE;
    }
    v->appsink = gst_bin_get_by_name (GST_BIN (v->pipeline), "asink");
    GstAppSinkCallbacks cbs = { 0 }; cbs.new_sample = appsink_new_sample_cb;
    gst_app_sink_set_callbacks (GST_APP_SINK (v->appsink), &cbs, d, NULL);

    g_mutex_lock (&v->dec_lock);
    v->accept_input = TRUE; v->dec_fail_us = 0;
    g_mutex_unlock (&v->dec_lock);
    gint64 now = g_get_monotonic_time ();
    __atomic_store_n (&v->last_input_us, now, __ATOMIC_RELAXED);
    __atomic_store_n (&v->last_fed_us, now, __ATOMIC_RELAXED);
    __atomic_store_n (&v->last_frame_us, now, __ATOMIC_RELAXED);

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
    if (v->pipeline) gst_element_set_state (v->pipeline, GST_STATE_NULL);   /* hatali pipeline'i hemen durdur */
    if (v->retry_source || !d->running) return;
    guint shift = v->retry_count < 3 ? v->retry_count : 3;
    if (v->retry_count < 1000) v->retry_count++;
    GSource *s = g_timeout_source_new_seconds (1u << shift);
    g_source_set_callback (s, video_retry_cb, d, NULL);
    g_source_attach (s, d->context);
    v->retry_source = s;
}

/* Watchdog (stats timer'dan, 1 Hz): AU codec'e veriliyor ama STALL_TIMEOUT_US
 * boyunca kare yok -> yalniz codec yenilenir (ag/pipeline kalir, ilk AU'da csd ile
 * acilir). 60 s icinde RECYCLE_LIMIT yenileme -> pipeline da yeniden kurulur.
 * AU gelmiyorsa (K9 video gondermiyor / link yok) dokunulmaz: udpsrc beklemede. */
static void video_watchdog (NativeData *d, gint64 now) {
    VideoSlot *v = &d->video;
    if (!v->pipeline || v->retry_source) return;
    gint64 fed_age   = now - __atomic_load_n (&v->last_fed_us,   __ATOMIC_RELAXED);
    gint64 frame_age = now - __atomic_load_n (&v->last_frame_us, __ATOMIC_RELAXED);
    if (frame_age < STALL_TIMEOUT_US || fed_age >= STALL_TIMEOUT_US) return;
    if (now - v->last_recycle_us > RECYCLE_WINDOW_US) v->recycle_count = 0;
    g_mutex_lock (&v->dec_lock);
    if (v->dec) {
        v->recycle_count++; v->last_recycle_us = now;
        LOGE ("DECODER STALL: AU veriliyor ama %lld ms kare yok -> codec yenileniyor (%d/%d)",
              (long long) (frame_age / 1000), v->recycle_count, RECYCLE_LIMIT);
        hwdec_destroy_async (v->dec); v->dec = NULL;
        __atomic_store_n (&v->last_frame_us, now, __ATOMIC_RELAXED);
        if (v->recycle_count >= RECYCLE_LIMIT) {
            v->recycle_count = 0; v->retry_count = 0;
            g_mutex_unlock (&v->dec_lock);
            LOGE ("ust uste codec yenileme -> video pipeline yeniden kuruluyor");
            schedule_video_rebuild (d);
            return;
        }
    }
    g_mutex_unlock (&v->dec_lock);
}

/* ------------------------------------------------------------- ses */
static gboolean build_audio (NativeData *d) {
    GError *err = NULL;
    if (d->audio_rx) { gst_element_set_state (d->audio_rx, GST_STATE_NULL); gst_object_unref (d->audio_rx); d->audio_rx = NULL; }
    if (d->audio_tx) { gst_element_set_state (d->audio_tx, GST_STATE_NULL); gst_object_unref (d->audio_tx); d->audio_tx = NULL; }

    gchar *rx = g_strdup_printf (
        "udpsrc port=%d caps=\"application/x-rtp,media=audio,clock-rate=48000,encoding-name=OPUS,payload=96\" ! "
        "rtpjitterbuffer latency=40 do-lost=true ! rtpopusdepay ! opusdec plc=true use-inband-fec=true ! "
        "audioconvert ! audioresample ! "
        /* Sizdiran kucuk kuyruk: K9 saat hizi telefonun DAC saatinden bir tik hizliysa
         * sync=false sink'in halkasi dolar, yukari dogru paket birikir ve gecikme
         * dakikalar icinde buyur. Halka doluyken kuyruk EN ESKI kareyi atar (10 ms,
         * duyulmaz) -> gecikme ust siniri sabit: ~40 jb + 20 kuyruk + 60 halka ms. */
        "queue max-size-buffers=2 max-size-time=0 max-size-bytes=0 leaky=downstream ! "
        "openslessink sync=false buffer-time=60000 latency-time=20000",
        d->audio_rx_port);
    d->audio_rx = gst_parse_launch (rx, &err); g_free (rx);
    if (err) { LOGE ("audio_rx kurulamadi: %s", err->message); g_clear_error (&err); if (d->audio_rx) { gst_object_unref (d->audio_rx); d->audio_rx = NULL; } }

    /* Bas-konus: eski uygulamadaki gibi pipeline basta NULL, tusa basinca PLAYING,
     * birakinca NULL. Her basista yeni RTP akisi (yeni SSRC) -> K9 jitterbuffer
     * icin temiz baslangic; kayip paket firtinasi / gap yok. */
    gchar *tx = g_strdup_printf (
        "openslessrc ! queue max-size-buffers=4 max-size-time=0 max-size-bytes=0 leaky=downstream ! "
        "audioconvert ! audioresample ! audio/x-raw,rate=48000,channels=1,format=S16LE ! "
        "opusenc bitrate=32000 audio-type=2048 frame-size=20 inband-fec=true packet-loss-percentage=10 ! "
        "rtpopuspay pt=96 ! udpsink host=%s port=%d sync=false async=false",
        d->host, d->audio_tx_port);
    d->audio_tx = gst_parse_launch (tx, &err); g_free (tx);
    if (err) { LOGE ("audio_tx kurulamadi: %s", err->message); g_clear_error (&err); if (d->audio_tx) { gst_object_unref (d->audio_tx); d->audio_tx = NULL; } }
    return d->audio_rx != NULL;
}

/* Ses durumu. Kapatmada READY degil NULL: udpsrc soketi READY'de acik kalir,
 * kimse okumadigi icin cekirdek tamponunda paket birikir ve tekrar PLAYING'de
 * jitterbuffer o eski paketleri sirayla calar -> saniyelerce gecikme. NULL'da
 * soket kapanir, acilista taze akis. (Eski uygulama da NULL->PLAYING yapiyordu.)
 * on=TRUE: dinleme aciksa rx PLAYING. tx yalniz bas-konus ile PLAYING olur. */
static void set_audio_state (NativeData *d, gboolean on) {
    if (d->audio_rx) gst_element_set_state (d->audio_rx, (on && d->listen) ? GST_STATE_PLAYING : GST_STATE_NULL);
    if (d->audio_tx && !on) gst_element_set_state (d->audio_tx, GST_STATE_NULL);
}

/* ------------------------------------------------------------ stats */
static gboolean stats_cb (gpointer ud) {
    NativeData *d = ud; VideoSlot *v = &d->video;
    gint64 now = g_get_monotonic_time ();
    gint64 frames = 0;
    g_mutex_lock (&v->dec_lock);
    if (v->dec) frames = __atomic_load_n (&v->dec->frames_out, __ATOMIC_RELAXED);
    g_mutex_unlock (&v->dec_lock);
    gint fps = (gint) (frames - v->frames_last); if (fps < 0) fps = 0;
    v->frames_last = frames;
    gint w = __atomic_load_n (&v->out_w, __ATOMIC_RELAXED), h = __atomic_load_n (&v->out_h, __ATOMIC_RELAXED);
    if (w > 0 && !v->format_logged) { LOGI ("video %dx%d", w, h); v->format_logged = TRUE; }
    video_watchdog (d, now);
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
        if (d->running) { set_audio_state (d, TRUE); teardown_video (d); start_video (d); }
    }
    return G_SOURCE_REMOVE;
}

/* Mikrofon izni sonradan verildiginde: ses pipeline'larini yeniden kur */
static gboolean audio_restart_cb (gpointer p) {
    NativeData *d = p;
    build_audio (d);
    if (d->running) set_audio_state (d, TRUE);
    return G_SOURCE_REMOVE;
}

static gboolean start_cb (gpointer p) {
    NativeData *d = p; d->running = TRUE; d->video.retry_count = 0;
    set_audio_state (d, TRUE);
    if (!start_video (d) && d->window) schedule_video_rebuild (d);
    return G_SOURCE_REMOVE;
}
static gboolean stop_cb (gpointer p) {
    NativeData *d = p; d->running = FALSE;
    teardown_video (d); set_audio_state (d, FALSE);
    return G_SOURCE_REMOVE;
}

typedef struct { NativeData *d; ANativeWindow *w; } SurfaceReq;
/* Yuzey degisti: pipeline'a dokunulmaz. Codec varsa yuzeyi yerinde degistirir
 * (setOutputSurface, API 23+), olmazsa codec yenilenir; ilk AU'da yeniden acilir. */
static gboolean surface_set_cb (gpointer p) {
    SurfaceReq *r = p; NativeData *d = r->d; VideoSlot *v = &d->video;
    ANativeWindow *old = NULL;
    g_mutex_lock (&v->dec_lock);
    if (d->window && d->window == r->w) {
        ANativeWindow_release (r->w);            /* ayni pencere: fazladan alinan ref'i birak */
    } else {
        old = d->window; d->window = r->w;
        if (v->dec) {
            if (!d->window || AMediaCodec_setOutputSurface (v->dec->codec, d->window) != AMEDIA_OK) {
                if (d->window) LOGE ("setOutputSurface basarisiz, decoder yenileniyor");
                hwdec_destroy_async (v->dec); v->dec = NULL;
            }
        }
    }
    g_mutex_unlock (&v->dec_lock);
    if (old) ANativeWindow_release (old);        /* codec yeni yuzeye gectikten sonra */
    if (d->running && !v->pipeline) start_video (d);
    return G_SOURCE_REMOVE;
}
/* Yuzey gidiyor: codec yuzeysiz calisamaz -> kapat. Pipeline kalir, AU'lar dusurulur. */
static gboolean surface_release_cb (gpointer p) {
    NativeData *d = p; VideoSlot *v = &d->video; ANativeWindow *w;
    g_mutex_lock (&v->dec_lock);
    if (v->dec) { hwdec_destroy_async (v->dec); v->dec = NULL; }
    w = d->window; d->window = NULL;
    g_mutex_unlock (&v->dec_lock);
    if (w) ANativeWindow_release (w);
    return G_SOURCE_REMOVE;
}

typedef struct { NativeData *d; gboolean on; } PttReq;
/* Bas-konus: basinca NULL->PLAYING (taze akis), birakinca NULL. Eski uygulamadaki
 * gst_native_send_audio_start/stop ile ayni. Video sayfasi disinda (running=FALSE) gonderilmez. */
static gboolean ptt_cb (gpointer p) {
    PttReq *r = p; NativeData *d = r->d;
    if (!d->audio_tx) return G_SOURCE_REMOVE;
    gst_element_set_state (d->audio_tx, GST_STATE_NULL);
    if (r->on && d->running) {
        if (gst_element_set_state (d->audio_tx, GST_STATE_PLAYING) == GST_STATE_CHANGE_FAILURE) {
            LOGE ("audio_tx PLAYING olamadi (mikrofon izni?)");
            gst_element_set_state (d->audio_tx, GST_STATE_NULL);
            ui_message (d, "Mikrofon açılamadı");
        }
    }
    return G_SOURCE_REMOVE;
}
/* K9 sesini dinleme ac/kapa (rx pipeline PLAYING/NULL) */
static gboolean listen_cb (gpointer p) {
    PttReq *r = p; NativeData *d = r->d;
    d->listen = r->on;
    if (d->audio_rx) gst_element_set_state (d->audio_rx, (r->on && d->running) ? GST_STATE_PLAYING : GST_STATE_NULL);
    return G_SOURCE_REMOVE;
}

/* ------------------------------------------------------- JNI girisleri */
static void n_init (JNIEnv *env, jobject thiz) {
    NativeData *d = g_new0 (NativeData, 1);
    d->app = (*env)->NewGlobalRef (env, thiz);
    d->listen = TRUE;
    d->host = g_strdup ("192.168.2.199"); d->video_port = 5001; d->audio_rx_port = 14551; d->audio_tx_port = 14552; d->latency_ms = 200;
    g_mutex_init (&d->video.dec_lock);
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
    g_mutex_clear (&d->video.dec_lock);
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
