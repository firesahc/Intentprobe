package com.example.intentprobe.ui;

import android.app.Activity;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.intentprobe.R;

/**
 * Android 15+ draws the app edge to edge whether or not the app asks for it.
 * Both activities use a plain root LinearLayout, so the status bar and the
 * navigation bar would otherwise overlap the toolbar and the list.
 */
public final class WindowInsetsHelper {

    private WindowInsetsHelper() {
    }

    public static void applyRootInsets(Activity activity) {
        View root = activity.findViewById(R.id.root);
        if (root == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }
}
