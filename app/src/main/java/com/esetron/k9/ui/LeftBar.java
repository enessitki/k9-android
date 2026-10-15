package com.esetron.k9.ui;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import com.esetron.k9.MainActivity;
import com.esetron.k9.R;
import com.esetron.k9.kiosk.Kiosk;

/** Sol nav sutunu: sayfa secimi (video / kayit / durum / ayarlar) ve cikis. */
public final class LeftBar {
    public static final int PAGE_VIDEO = 0, PAGE_RECORD = 1, PAGE_STATUS = 2, PAGE_SETTINGS = 3;

    private final MainActivity a;
    private final View pageContainer, pageRecord, pageStatus, pageSettings, topLayout, rightLayout;
    private final View[] cells = new View[4], indicators = new View[4];
    private final TextView[] labels = new TextView[4];
    private final ImageButton[] icons = new ImageButton[4];
    private final TextView recordCount;
    private int page = PAGE_VIDEO;

    public LeftBar(MainActivity a) {
        this.a = a;
        pageContainer = a.findViewById(R.id.page_container);
        pageRecord = a.findViewById(R.id.page_record);
        pageStatus = a.findViewById(R.id.page_status);
        pageSettings = a.findViewById(R.id.page_settings);
        recordCount = a.findViewById(R.id.record_count);
        topLayout = a.findViewById(R.id.top_layout);
        rightLayout = a.findViewById(R.id.right_layout);

        int[] cellIds = {R.id.nav_cell_video, R.id.nav_cell_record, R.id.nav_cell_status, R.id.nav_cell_settings};
        int[] indIds = {R.id.nav_indicator_video, R.id.nav_indicator_record, R.id.nav_indicator_status, R.id.nav_indicator_settings};
        int[] labelIds = {R.id.nav_label_video, R.id.nav_label_record, R.id.nav_label_status, R.id.nav_label_settings};
        int[] iconIds = {R.id.nav_icon_video, R.id.nav_icon_record, R.id.nav_icon_status, R.id.nav_icon_settings};
        for (int i = 0; i < 4; i++) {
            cells[i] = a.findViewById(cellIds[i]);
            indicators[i] = a.findViewById(indIds[i]);
            labels[i] = a.findViewById(labelIds[i]);
            icons[i] = a.findViewById(iconIds[i]);
            final int p = i;
            icons[i].setOnClickListener(v -> select(p));
            cells[i].setOnClickListener(v -> select(p));
        }
        a.findViewById(R.id.close).setOnClickListener(v -> confirmExit());
        select(PAGE_VIDEO);
    }

    public int page() { return page; }

    public void select(int p) {
        page = p;
        a.rightBar.closeAll();
        // Ust serit ve sag fonksiyon cubugu yalniz VIDEO sayfasinda (eski uygulamadaki gibi);
        // diger sayfalar sol cubuk disinda tum ekrani kullanir.
        boolean videoPage = p == PAGE_VIDEO;
        topLayout.setVisibility(videoPage ? View.VISIBLE : View.GONE);
        rightLayout.setVisibility(videoPage ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pageContainer.getLayoutParams();
        float d = a.getResources().getDisplayMetrics().density;
        lp.setMargins((int) (64 * d), 0, 0, 0);
        pageContainer.setLayoutParams(lp);
        pageContainer.setVisibility(videoPage ? View.GONE : View.VISIBLE);
        pageRecord.setVisibility(p == PAGE_RECORD ? View.VISIBLE : View.GONE);
        pageStatus.setVisibility(p == PAGE_STATUS ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(p == PAGE_SETTINGS ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 4; i++) {
            boolean active = i == p;
            cells[i].setBackgroundColor(active ? a.getColor(R.color.nav_cell_active_tint) : 0);
            indicators[i].setVisibility(active ? View.VISIBLE : View.INVISIBLE);
            labels[i].setTextColor(a.getColor(active ? R.color.accent_cyan : R.color.nav_label_idle));
            icons[i].setAlpha(active ? 1f : 0.7f);
        }
        if (p == PAGE_RECORD) a.recordPage.refresh();
        else a.recordPage.stopPreview();
        if (p == PAGE_STATUS) a.statusPage.refresh();
        if (p == PAGE_SETTINGS) a.settingsPage.load();
    }

    public void setRecordCount(int n) {
        recordCount.setText(String.valueOf(n));
        recordCount.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
    }

    /** Cikis: onay + kiosk PIN'i. Kiosk kapaliysa dogrudan kapatir. */
    private void confirmExit() {
        EditText pin = new EditText(a);
        pin.setHint(R.string.pin_hint);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new AlertDialog.Builder(a).setMessage(R.string.exit_confirm).setView(pin)
                .setPositiveButton("Çık", (d, w) -> {
                    if (!a.settings.kioskEnabled() || pin.getText().toString().equals(a.settings.kioskPin())) {
                        if (a.settings.kioskEnabled()) Kiosk.exit(a);
                        a.finishAndRemoveTask();
                    } else a.showMessage("PIN yanlış");
                })
                .setNegativeButton("Geri", null).show();
    }
}
