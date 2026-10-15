package com.esetron.k9.ui;

import android.app.AlertDialog;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.esetron.k9.MainActivity;
import com.esetron.k9.R;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Kayit sayfasi (eski RecordActivity + MyVideoFileDialog karsiligi):
 * disk kullanimi karti, tur filtresi + arama, kart listesi (RecyclerView),
 * tam sayfa oynatici/goruntuleyici. Kaynak: MediaStore Movies/K9, Pictures/K9.
 */
public final class RecordPage {
    private static final class Item {
        final Uri uri; final String name; final long size, date; final boolean video;
        Item(Uri u, String n, long s, long d, boolean v) { uri = u; name = n; size = s; date = d; video = v; }
    }
    private enum Filter { ALL, VIDEO, PHOTO }

    private final MainActivity a;
    private final RecyclerView recycler;
    private final View playerLayout, emptyText;
    private final VideoView playerVideo;
    private final ImageView playerImage;
    private final TextView playerTitle, diskPercent, diskAvailable, diskTotal, recordTotal;
    private final CircularProgressIndicator diskProgress;
    private final Button[] filterButtons;
    private final EditText search;
    private final List<Item> all = new ArrayList<>(), shown = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private Filter filter = Filter.ALL;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US);

    public RecordPage(MainActivity a) {
        this.a = a;
        recycler = a.findViewById(R.id.record_recycler);
        recycler.setLayoutManager(new LinearLayoutManager(a));
        recycler.setAdapter(adapter);
        playerLayout = a.findViewById(R.id.player_layout); emptyText = a.findViewById(R.id.record_empty);
        playerVideo = a.findViewById(R.id.player_video); playerImage = a.findViewById(R.id.player_image); playerTitle = a.findViewById(R.id.player_title);
        diskPercent = a.findViewById(R.id.disk_percent); diskAvailable = a.findViewById(R.id.disk_available); diskTotal = a.findViewById(R.id.disk_total);
        recordTotal = a.findViewById(R.id.record_total); diskProgress = a.findViewById(R.id.disk_progress);
        search = a.findViewById(R.id.record_search);
        filterButtons = new Button[]{a.findViewById(R.id.filter_all), a.findViewById(R.id.filter_video), a.findViewById(R.id.filter_photo)};
        Filter[] fs = Filter.values();
        for (int i = 0; i < 3; i++) { final Filter f = fs[i]; filterButtons[i].setOnClickListener(v -> { filter = f; applyFilter(); }); }
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { applyFilter(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        a.findViewById(R.id.record_refresh).setOnClickListener(v -> refresh());
        a.findViewById(R.id.player_close).setOnClickListener(v -> stopPreview());
        playerVideo.setOnCompletionListener(mp -> playerVideo.seekTo(0));
        playerVideo.setOnErrorListener((mp, w, e) -> { a.showMessage("Video oynatılamadı"); stopPreview(); return true; });
    }

    /** Sayfa acilinca ve yenilemede: liste + disk bilgisi. */
    public void refresh() {
        all.clear();
        query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/K9/", true);
        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/K9/", false);
        all.sort((x, y) -> Long.compare(y.date, x.date));
        a.leftBar.setRecordCount(all.size());
        recordTotal.setText(String.valueOf(all.size()));
        File dir = a.getExternalFilesDir(null);
        if (dir != null) {
            StatFs st = new StatFs(dir.getAbsolutePath());
            long total = st.getTotalBytes(), avail = st.getAvailableBytes();
            int used = total > 0 ? (int) (100 - avail * 100 / total) : 0;
            diskProgress.setProgress(used);
            diskPercent.setText(used + "%");
            diskAvailable.setText(gb(avail)); diskTotal.setText(gb(total));
        }
        applyFilter();
    }

    private static String gb(long b) { return String.format(Locale.US, "%.1f GB", b / 1073741824.0); }

    private void applyFilter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.US);
        shown.clear();
        for (Item it : all) {
            if (filter == Filter.VIDEO && !it.video) continue;
            if (filter == Filter.PHOTO && it.video) continue;
            if (!q.isEmpty() && !it.name.toLowerCase(Locale.US).contains(q) && !timeFmt.format(new Date(it.date * 1000)).contains(q)) continue;
            shown.add(it);
        }
        for (int i = 0; i < 3; i++) filterButtons[i].setSelected(Filter.values()[i] == filter);
        adapter.notifyDataSetChanged();
        emptyText.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void query(Uri base, String relPath, boolean video) {
        String[] proj = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED};
        try (Cursor c = a.getContentResolver().query(base, proj, MediaStore.MediaColumns.RELATIVE_PATH + "=?", new String[]{relPath}, null)) {
            if (c == null) return;
            while (c.moveToNext())
                all.add(new Item(ContentUris.withAppendedId(base, c.getLong(0)), c.getString(1), c.getLong(2), c.getLong(3), video));
        } catch (SecurityException e) { a.showMessage("Kayıt listesi okunamadı: " + e.getMessage()); }
    }

    private void play(Item it) {
        stopPreview();
        playerTitle.setText(it.name);
        playerLayout.setVisibility(View.VISIBLE);
        if (it.video) { playerVideo.setVisibility(View.VISIBLE); playerVideo.setVideoURI(it.uri); playerVideo.start(); }
        else { playerImage.setVisibility(View.VISIBLE); playerImage.setImageURI(it.uri); }
    }

    private void confirmDelete(Item it) {
        new AlertDialog.Builder(a).setTitle("Kaydı sil").setMessage(it.name + " kalıcı olarak silinsin mi?")
                .setPositiveButton("Sil", (d, w) -> { a.getContentResolver().delete(it.uri, null, null); refresh(); })
                .setNegativeButton("Geri", null).show();
    }

    public void stopPreview() {
        if (playerVideo.isPlaying()) playerVideo.stopPlayback();
        playerVideo.setVisibility(View.GONE); playerImage.setVisibility(View.GONE); playerImage.setImageDrawable(null);
        playerLayout.setVisibility(View.GONE);
    }

    public void release() { stopPreview(); }

    // ---------------- RecyclerView ----------------
    private final class Holder extends RecyclerView.ViewHolder {
        final TextView time, name, type, size; final View play, delete;
        Holder(View v) {
            super(v);
            time = v.findViewById(R.id.item_time); name = v.findViewById(R.id.item_name); type = v.findViewById(R.id.item_type);
            size = v.findViewById(R.id.item_size); play = v.findViewById(R.id.item_play); delete = v.findViewById(R.id.item_delete);
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_record, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull Holder h, int pos) {
            Item it = shown.get(pos);
            h.time.setText(timeFmt.format(new Date(it.date * 1000)));
            h.name.setText(it.name);
            h.type.setText(it.video ? "VİDEO" : "FOTOĞRAF");
            h.type.setTextColor(it.video ? 0xFF4CAF50 : 0xFF00BCD4);
            h.size.setText(it.size >= 1048576 ? String.format(Locale.US, "%.1f MB", it.size / 1048576.0) : String.format(Locale.US, "%d KB", it.size / 1024));
            h.play.setOnClickListener(v -> play(it));
            h.delete.setOnClickListener(v -> confirmDelete(it));
            h.itemView.setOnClickListener(v -> play(it));
        }
        @Override public int getItemCount() { return shown.size(); }
    }
}
