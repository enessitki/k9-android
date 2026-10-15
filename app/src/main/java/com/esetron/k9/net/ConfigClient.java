package com.esetron.k9.net;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * K9 ayar kanali (UDP, JSON, seq + ACK). Istek tek is parcaciginda sirayla
 * gonderilir; yanit gelene kadar ayni seq ile tekrarlanir (UDP kaybi).
 *   {"seq":N,"set":{...}} -> {"seq":N,"applied":{...},"rejected":{...}}
 *   {"seq":N,"get":true}  -> {"seq":N,"values":{...},"defaults":{...},"limits":{...}}
 *   {"seq":N,"reset":true}
 */
public final class ConfigClient {
    private static final String TAG = "ConfigClient";
    private static final int RETRIES = 5, TIMEOUT_MS = 1000;

    public interface Callback { void onReply(JSONObject reply); void onFailure(String why); }

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "k9-config"); t.setDaemon(true); return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile String host; private volatile int port;
    private int seq = (int) (System.currentTimeMillis() & 0x3FFFFFFF);

    public void setTarget(String host, int port) { this.host = host; this.port = port; }

    public void get(Callback cb) {
        JSONObject b = new JSONObject();
        try { b.put("get", true); } catch (JSONException ignored) { }
        send(b, cb);
    }

    public void reset(Callback cb) {
        JSONObject b = new JSONObject();
        try { b.put("reset", true); } catch (JSONException ignored) { }
        send(b, cb);
    }

    public void set(Map<String, Object> changes, Callback cb) {
        JSONObject b = new JSONObject();
        try { b.put("set", new JSONObject(changes)); } catch (JSONException e) { fail(cb, "JSON: " + e.getMessage()); return; }
        send(b, cb);
    }

    private void send(JSONObject body, Callback cb) {
        exec.execute(() -> {
            String h = host; int p = port;
            if (h == null) { fail(cb, "hedef yok"); return; }
            int mySeq;
            synchronized (this) { mySeq = ++seq; }
            byte[] payload;
            try { body.put("seq", mySeq); payload = body.toString().getBytes(StandardCharsets.UTF_8); }
            catch (JSONException e) { fail(cb, e.getMessage()); return; }
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setSoTimeout(TIMEOUT_MS);
                InetAddress addr = InetAddress.getByName(h);
                byte[] buf = new byte[65535];
                for (int attempt = 0; attempt < RETRIES; attempt++) {
                    sock.send(new DatagramPacket(payload, payload.length, addr, p));
                    long deadline = System.currentTimeMillis() + TIMEOUT_MS;
                    while (System.currentTimeMillis() < deadline) {
                        DatagramPacket in = new DatagramPacket(buf, buf.length);
                        try { sock.receive(in); } catch (SocketTimeoutException e) { break; }
                        try {
                            JSONObject r = new JSONObject(new String(in.getData(), in.getOffset(), in.getLength(), StandardCharsets.UTF_8));
                            if (r.optInt("seq", -1) == mySeq) { main.post(() -> cb.onReply(r)); return; }
                        } catch (JSONException ignored) { /* yabanci/bozuk paket */ }
                    }
                }
                fail(cb, "K9 yanit vermedi (" + RETRIES + " deneme)");
            } catch (IOException e) {
                Log.w(TAG, "Ayar istegi hatasi", e);
                fail(cb, e.getMessage());
            }
        });
    }

    private void fail(Callback cb, String why) { main.post(() -> cb.onFailure(why)); }
}
