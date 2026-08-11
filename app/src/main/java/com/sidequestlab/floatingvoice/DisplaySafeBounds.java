package com.sidequestlab.floatingvoice;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import java.util.Objects;

/**
 * Deterministic safe placement bounds for overlay windows.
 *
 * <p>The overlay uses {@code FLAG_LAYOUT_NO_LIMITS}, so it can be laid out under system UI.
 * Placement therefore clamps against the current display bounds reduced by system-bar and
 * cutout insets. This avoids asynchronous inset dispatch, which is unavailable to a
 * Service-owned window at the moment placement is computed.
 */
final class DisplaySafeBounds {
    private static final int DEFAULT_NAVIGATION_BAR_DP = 48;

    private DisplaySafeBounds() {
    }

    @SuppressLint("DeprecatedMethod")
    static Rect from(Context context) {
        Objects.requireNonNull(context, "context");
        WindowManager windowManager =
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager != null && Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
            Rect metricsBounds = metrics.getBounds();
            Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return new Rect(
                    metricsBounds.left + insets.left,
                    metricsBounds.top + insets.top,
                    metricsBounds.right - insets.right,
                    metricsBounds.bottom - insets.bottom);
        }
        Display display = windowManager == null ? null : windowManager.getDefaultDisplay();
        if (display == null) {
            DisplayManager displayManager =
                    (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            display = displayManager == null ? null
                    : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        }
        Rect bounds = new Rect();
        if (display != null) {
            Point realSize = new Point();
            display.getRealSize(realSize);
            bounds.set(0, 0, realSize.x, realSize.y);
        } else {
            bounds.set(0, 0,
                    Resources.getSystem().getDisplayMetrics().widthPixels,
                    Resources.getSystem().getDisplayMetrics().heightPixels);
        }

        int top = statusBarHeight(context);
        int bottom = dp(context, DEFAULT_NAVIGATION_BAR_DP);
        int left = 0;
        int right = 0;
        if (display != null) {
            DisplayCutout cutout = display.getCutout();
            if (cutout != null) {
                top = Math.max(top, cutout.getSafeInsetTop());
                bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                left = Math.max(left, cutout.getSafeInsetLeft());
                right = Math.max(right, cutout.getSafeInsetRight());
            }
        }
        return new Rect(
                bounds.left + left,
                bounds.top + top,
                bounds.right - right,
                bounds.bottom - bottom);
    }

    private static int statusBarHeight(Context context) {
        Resources resources = context.getResources();
        int resourceId = resources.getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            try {
                return resources.getDimensionPixelSize(resourceId);
            } catch (Resources.NotFoundException ignored) {
                // Fall through to the dp fallback below.
            }
        }
        return dp(context, 24);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
