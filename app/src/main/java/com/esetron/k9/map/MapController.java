package com.esetron.k9.map;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.modules.OfflineTileProvider;
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import mil.nga.grid.features.Point;
import mil.nga.mgrs.MGRS;

/**
 * Harita (osmdroid), cevrimdisi. Eski projedeki egolabs OfflineMapView ile
 * ayni mekanizma: tile arsivi (.sqlite/.zip/.mbtiles/.gemf) OfflineTileProvider
 * ile acilir. Aranan yerler, sirayla:
 *   1. Android/data/com.esetron.k9/files/osmdroid/   (root ve izin gerekmez)
 *   2. /sdcard/osmdroid/                              (eski projenin yeri; okunabiliyorsa)
 *   3. assets/map.sqlite (APK icinde geldiyse ilk acilista 1'e kopyalanir)
 * Hicbiri yoksa cevrimici MAPNIK kullanilir ve kullaniciya dosya yolu soylenir.
 * Marker'lar: operator (telefon GPS'i) ve K9 (ileride telemetri ile).
 */
public final class MapController implements LocationListener {
    private static final String TAG = "MapController";
    public interface Listener { void onSelfLocation(double lat, double lon, String mgrs); void onMapMessage(String msg); }

    private final Context ctx;
    private final MapView map;
    private final Marker self, k9;
    private final LocationManager lm;
    private Listener listener;
    private boolean followSelf = true;
    private String sourceInfo = "-";
    private boolean offline;

    public MapController(Context ctx, MapView map) {
        this.ctx = ctx; this.map = map;
        File base = ctx.getExternalFilesDir("osmdroid");
        if (base != null) {
            Configuration.getInstance().setOsmdroidBasePath(base);
            Configuration.getInstance().setOsmdroidTileCache(new File(base, "cache"));
        }
        Configuration.getInstance().setUserAgentValue(ctx.getPackageName());
        setupTiles(base);
        map.setMultiTouchControls(true);
        map.setBuiltInZoomControls(false);
        map.setMinZoomLevel(4.0); map.setMaxZoomLevel(19.0);
        map.getController().setZoom(16.0);
        self = new Marker(map); self.setTitle("Operator");
        self.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        k9 = new Marker(map); k9.setTitle("K9");
        k9.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        k9.setEnabled(false);                      // konum gelene kadar gizli
        map.getOverlays().add(self); map.getOverlays().add(k9);
        lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
    }

    private static boolean isArchive(File f) {
        String n = f.getName().toLowerCase();
        return f.isFile() && (n.endsWith(".sqlite") || n.endsWith(".zip") || n.endsWith(".mbtiles") || n.endsWith(".gemf"));
    }

    private void setupTiles(File base) {
        List<File> archives = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        if (base != null) { copyAssetMap(base); dirs.add(base); }
        dirs.add(new File(Environment.getExternalStorageDirectory(), "osmdroid"));   // eski projenin yeri
        for (File dir : dirs) {
            File[] files = dir.listFiles();
            if (files == null) continue;                       // yok ya da okunamiyor (scoped storage)
            for (File f : files) if (isArchive(f)) archives.add(f);
            if (!archives.isEmpty()) break;
        }
        if (!archives.isEmpty()) {
            try {
                OfflineTileProvider p = new OfflineTileProvider(new SimpleRegisterReceiver(ctx), archives.toArray(new File[0]));
                map.setTileProvider(p);
                String src = p.getArchives()[0].getTileSources().iterator().next();   // arsivdeki kaynak adi
                ITileSource ts = FileBasedTileSource.getSource(src);
                map.setTileSource(ts);
                map.setUseDataConnection(false);
                map.setTilesScaledToDpi(true);
                offline = true;
                sourceInfo = "çevrimdışı: " + archives.get(0).getAbsolutePath();
                Log.i(TAG, "Cevrimdisi harita: " + archives.size() + " arsiv, kaynak " + src);
                return;
            } catch (Exception e) { Log.w(TAG, "Arsiv acilamadi, cevrimici kaynak", e); }
        }
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setUseDataConnection(true);
        offline = false;
        sourceInfo = "çevrimdışı harita dosyası yok";
        Log.w(TAG, "Harita arsivi bulunamadi; beklenen yer: " + (base != null ? base.getAbsolutePath() : "?") + "/map.sqlite");
    }

    /** APK ile assets/map.sqlite geldiyse ilk acilista uygulama dizinine kopyala (eski FileUtils). */
    private void copyAssetMap(File base) {
        File target = new File(base, "map.sqlite");
        if (target.exists()) return;
        try (InputStream in = ctx.getAssets().open("map.sqlite")) {
            if (!base.exists() && !base.mkdirs()) return;
            try (OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Log.i(TAG, "assets/map.sqlite kopyalandi: " + target);
        } catch (java.io.IOException ignored) {       // asset yok: normal
            if (target.exists() && target.length() == 0) target.delete();
        }
    }

    public boolean isOffline() { return offline; }
    public String sourceInfo() { return sourceInfo; }

    /** Harita acilinca: dosya yoksa kullaniciya soyle (sessizce cevrimici kalmasin). */
    public void checkSource() {
        if (!offline && listener != null) listener.onMapMessage("Çevrimdışı harita dosyası bulunamadı, çevrimiçi harita kullanılıyor");
    }

    public void setListener(Listener l) { listener = l; }
    public void setFollowSelf(boolean f) { followSelf = f; }

    /** Konum izni verildikten sonra cagir. */
    public void startLocation() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, this);
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) onLocationChanged(last);
        } catch (SecurityException | IllegalArgumentException e) { Log.w(TAG, "GPS: " + e.getMessage()); }
    }
    public void stopLocation() { lm.removeUpdates(this); }

    /** K9 konumu (telemetri gelince). */
    public void setK9Position(double lat, double lon) {
        k9.setPosition(new GeoPoint(lat, lon)); k9.setEnabled(true); map.invalidate();
    }

    public void onResume() { map.onResume(); }
    public void onPause() { map.onPause(); }

    public static String mgrs(double lat, double lon) {
        try { return MGRS.from(Point.point(lon, lat)).toString(); } catch (Exception e) { return "-"; }
    }

    @Override public void onLocationChanged(@NonNull Location loc) {
        GeoPoint p = new GeoPoint(loc.getLatitude(), loc.getLongitude());
        self.setPosition(p);
        if (loc.hasBearing()) self.setRotation(-loc.getBearing());
        if (followSelf) map.getController().animateTo(p);
        map.invalidate();
        if (listener != null) listener.onSelfLocation(loc.getLatitude(), loc.getLongitude(), mgrs(loc.getLatitude(), loc.getLongitude()));
    }
    @Override public void onProviderEnabled(@NonNull String provider) { }
    @Override public void onProviderDisabled(@NonNull String provider) { }
}
