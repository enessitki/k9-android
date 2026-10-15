package com.esetron.k9.ui;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import cn.pedant.SweetAlert.SweetAlertDialog;

/** Eski projedeki FullScreenSweetAlertDialog: SweetAlert, sistem cubuklarini gostermeden (kiosk). */
public class FullScreenSweetAlertDialog extends SweetAlertDialog {

    public FullScreenSweetAlertDialog(Context context, int alertType) {
        super(context, alertType);
    }

    @Override
    public void show() {
        super.show();
        if (getWindow() != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            View decorView = getWindow().getDecorView();
            decorView.setOnApplyWindowInsetsListener((view, insets) -> WindowInsets.CONSUMED);
        }
    }
}
