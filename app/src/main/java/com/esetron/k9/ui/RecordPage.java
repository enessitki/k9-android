package com.esetron.k9.ui;

import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.NonNull;

import com.esetron.k9.MainActivity;
import com.esetron.k9.R;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Kayit sayfasi: Movies/K9 ve Pictures/K9 listesi (MediaStore), onizleme, silme. */
public final class RecordPage {
    private static final class Item {
        final Uri uri; final String name; final long size, date; final boolean video;
        Item(Uri u, String n, long s, long d, boolean v) { uri = u; name = n; size = s; date = d; video = v; }
    }

    private final MainActivity a;
    private final ListView list;
    private final VideoView previewVideo;
    private final ImageView previewImage;
    private final TextView previewHint, diskInfo;
    private final View btnDelete, btnClose;
    private final List<Item> items = new ArrayList<>();
    private final ArrayAdapter<Item> adapter;
    private Item current;

    public RecordPage(MainActivity a) {
        this.a = a;
        list = a.findViewById(R.id.record_list);
        previewVideo = a.findViewById(R.id.preview_video); previewImage = a.findViewById(R.id.preview_image);
        previewHint = a.findViewById(R.id.preview_hint); diskInfo = a.findViewById(R.id.disk_info);
        btnDelete = a.findViewById(R.id.preview_delete); btnClose = a.findViewById(R.id.preview_close);
        adapter = new ArrayAdapter<Item>(a, R.layout.item_record, items) {
            @NonNull @Override public View getView(int pos, View cv, @NonNull ViewGroup parent) {
                View v = cv != null ? cv : LayoutInflater.from(a).inflate(R.layout.item_record, parent, false);
                Item it = items.get(pos);
                ((ImageView) v.findViewById(R.id.item_icon)).setImageResource(it.video ? R.drawable.ic_play_24 : R.drawable.ic_image_24);
                ((TextView) v.findViewById(R.id.item_name)).setText(it.name);
                ((TextView) v.findViewById(R.id.item_meta)).setText(String.format(Locale.US, "%s  %.1f MB",
                        new SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new Date(it.date * 1000)), it.size / 1048576.0));
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> show(items.get(pos)));
        btnClose.setOnClickListener(v -> stopPreview());
        btnDelete.setOnClickListener(v -> {
            if (current == null) return;
            a.getContentResolver().delete(current.uri, null, null);
            stopPreview(); refresh();
        });
        previewVideo.setOnCompletionListener(mp -> previewVideo.seekTo(0));
    }

    public void refresh() {
        items.clear();
        query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/K9/", true);
        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/K9/", false);
        items.sort((x, y) -> Long.compare(y.date, x.date));
        adapter.notifyDataSetChanged();
        a.leftBar.setRecordCount(items.size());
        File dir = a.getExternalFilesDir(null);
        if (dir != null) {
            StatFs st = new StatFs(dir.getAbsolutePath());
            diskInfo.setText(String.format(Locale.US, "boş %.1f GB", st.getAvailableBytes() / 1073741824.0));
        }
    }

    private void query(Uri base, String relPath, boolean video) {
        String[] proj = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED};
        try (Cursor c = a.getContentResolver().query(base, proj, MediaStore.MediaColumns.RELATIVE_PATH + "=?", new String[]{relPath}, null)) {
            if (c == null) return;
            while (c.moveToNext())
                items.add(new Item(ContentUris.withAppendedId(base, c.getLong(0)), c.getString(1), c.getLong(2), c.getLong(3), video));
        } catch (SecurityException e) { a.showMessage("Kayıt listesi okunamadı: " + e.getMessage()); }
    }

    private void show(Item it) {
        stopPreview();
        current = it;
        previewHint.setVisibility(View.GONE); btnDelete.setVisibility(View.VISIBLE); btnClose.setVisibility(View.VISIBLE);
        if (it.video) { previewVideo.setVisibility(View.VISIBLE); previewVideo.setVideoURI(it.uri); previewVideo.start(); }
        else { previewImage.setVisibility(View.VISIBLE); previewImage.setImageURI(it.uri); }
    }

    public void stopPreview() {
        if (previewVideo.isPlaying()) previewVideo.stopPlayback();
        previewVideo.setVisibility(View.GONE); previewImage.setVisibility(View.GONE); previewImage.setImageDrawable(null);
        previewHint.setVisibility(View.VISIBLE); btnDelete.setVisibility(View.GONE); btnClose.setVisibility(View.GONE);
        current = null;
    }

    public void release() { stopPreview(); }
}
