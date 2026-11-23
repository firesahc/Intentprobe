package com.example.intentprobe.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

/**
 * A scroll view that never grows past part of the screen. Dialogs use it so a
 * long message plus many options scrolls instead of pushing the buttons off
 * screen.
 */
public class MaxHeightScrollView extends NestedScrollView {

    private static final float MAX_SCREEN_RATIO = 0.55f;

    public MaxHeightScrollView(@NonNull Context context) {
        super(context);
    }

    public MaxHeightScrollView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public MaxHeightScrollView(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int max = (int) (getResources().getDisplayMetrics().heightPixels * MAX_SCREEN_RATIO);
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        int size = MeasureSpec.getSize(heightMeasureSpec);
        if (mode == MeasureSpec.UNSPECIFIED || size > max) {
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
