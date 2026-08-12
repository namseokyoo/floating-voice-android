package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.LinearLayout;

/** Rejects fully or partially obscured destination-selection touches. */
public final class SecureDestinationRow extends LinearLayout {
    public SecureDestinationRow(Context context) {
        super(context);
    }

    public SecureDestinationRow(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SecureDestinationRow(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override public boolean onFilterTouchEventForSecurity(MotionEvent event) {
        int unsafeFlags = MotionEvent.FLAG_WINDOW_IS_OBSCURED
                | MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED;
        return (event.getFlags() & unsafeFlags) == 0
                && super.onFilterTouchEventForSecurity(event);
    }
}
