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

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import mil.nga.grid.features.Point;
import mil.nga.mgrs.MGRS;

/**
 * Harita (osmdroid), cevrimdisi. Tile arsivleri (.sqlite/.zip/.mbtiles)
 * Android/data/com.esetron.k9/files/osmdroid/ altina USB ile kopyalanir;
 * root gerekmez. Arsiv yoksa cevrimici MAPNIK (ag varsa).
 * Marker'lar: operator (telefon GPS'i) ve K9 (ileride telemetri ile).
 */
public final class MapController implements LocationListener {
    private static final String TAG = "MapController";
    public interface Listener { void onSelfLocation(double lat, double lon, String mgrs); }

    private final Context ctx;
    private final MapView map;
    private final Marker self, k9;
    private final LocationManager lm;
    private Listener listener;
    private boolean followSelf = true;

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

    private void setupTiles(File base) {
        List<File> archives = new ArrayList<>();
        File[] files = base != null ? base.listFiles() : null;
        if (files != null) for (File f : files) {
            String n = f.getName().toLowerCase();
            if (n.endsWith(".sqlite") || n.endsWith(".zip") || n.endsWith(".mbtiles") || n.endsWith(".gemf")) archives.add(f);
        }
        if (!archives.isEmpty()) {
            try {
                OfflineTileProvider p = new OfflineTileProvider(new SimpleRegisterReceiver(ctx), archives.toArray(new File[0]));
                map.setTileProvider(p);
                String src = p.getArchives()[0].getTileSources().iterator().next();   // arsivdeki kaynak adi
                ITileSource ts = FileBasedTileSource.getSource(src);
                map.setTileSource(ts);
                map.setUseDataConnection(false);
                Log.i(TAG, "Cevrimdisi harita: " + archives.size() + " arsiv, kaynak " + src);
                return;
            } catch (Exception e) { Log.w(TAG, "Arsiv acilamadi, cevrimici kaynak", e); }
        }
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setUseDataConnection(true);
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
