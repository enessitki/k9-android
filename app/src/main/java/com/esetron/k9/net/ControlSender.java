package com.esetron.k9.net;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * K9 kontrol paketi (UDP, 8 bayt): AA bitrate stab blind led spk noise FF.
 * Sabit 4 Hz gonderilir: K9 bunu link heartbeat'i olarak kullanir
 * (linkTimeoutSec icinde paket gelmezse video gonderimini durdurur).
 * Alanlar UI thread'inden atomik yazilir, gonderim thread'i okur.
 */
public final class ControlSender {
    private static final String TAG = "ControlSender";
    private static final int PERIOD_MS = 250;

    private final AtomicInteger bitrate = new AtomicInteger(3);     // 1..4
    private final AtomicInteger blind = new AtomicInteger(0);
    private final AtomicInteger led = new AtomicInteger(0);         // 0..100
    private final AtomicInteger speaker = new AtomicInteger(0);
    private final AtomicInteger noise = new AtomicInteger(0);

    private volatile InetAddress target;
    private volatile int port;
    private volatile boolean running;
    private Thread thread;

    public void setTarget(String host, int port) {
        this.port = port;
        try { target = InetAddress.getByName(host); }
        catch (UnknownHostException e) { Log.e(TAG, "Hedef cozulemedi: " + host); target = null; }
    }

    public void setBitrate(int level) { bitrate.set(Math.max(1, Math.min(4, level))); }
    public void setBlind(boolean on) { blind.set(on ? 1 : 0); }
    public void setLed(int pct) { led.set(Math.max(0, Math.min(100, pct))); }
    public void setSpeaker(boolean on) { speaker.set(on ? 1 : 0); }
    public void setNoiseReduction(boolean on) { noise.set(on ? 1 : 0); }

    public int bitrate() { return bitrate.get(); }
    public boolean blind() { return blind.get() == 1; }
    public int led() { return led.get(); }
    public boolean speaker() { return speaker.get() == 1; }
    public boolean noiseReduction() { return noise.get() == 1; }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "k9-control");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try { thread.join(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            thread = null;
        }
    }

    private void loop() {
        DatagramSocket sock;
        try { sock = new DatagramSocket(); }
        catch (IOException e) { Log.e(TAG, "Soket acilamadi", e); running = false; return; }
        byte[] pkt = new byte[8];
        long next = System.nanoTime();
        try {
            while (running) {
                InetAddress t = target;
                if (t != null) {
                    pkt[0] = (byte) 0xAA;
                    pkt[1] = (byte) bitrate.get();
                    pkt[2] = 0;                         // stabilizasyon: bu surumde yok
                    pkt[3] = (byte) blind.get();
                    pkt[4] = (byte) led.get();
                    pkt[5] = (byte) speaker.get();
                    pkt[6] = (byte) noise.get();
                    pkt[7] = (byte) 0xFF;
                    try { sock.send(new DatagramPacket(pkt, 8, t, port)); }
                    catch (IOException e) { Log.w(TAG, "Gonderim hatasi: " + e.getMessage()); }
                }
                // sabit periyot, drift telafili
                next += PERIOD_MS * 1_000_000L;
                long sleep = next - System.nanoTime();
                if (sleep > 0) Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                else next = System.nanoTime();
            }
        } catch (InterruptedException ignored) {
        } finally {
            sock.close();
        }
    }
}
