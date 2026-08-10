package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;

/**
 * One-shot, focusable text-composer overlay used by the V5-02 keyboard spike.
 * It deliberately has no transport or persistence behavior.
 */
public final class TextComposerOverlay implements AutoCloseable {
    private static final String TAG = "TextComposerOverlay";

    interface DismissListener {
        void onDismissed(TextComposerOverlay overlay);
    }

    private final Context context;
    private final WindowManager windowManager;
    private final DismissListener dismissListener;

    private View rootView;
    private TextComposerEditText editor;
    private boolean attached;
    private boolean closed;

    public TextComposerOverlay(Context context) {
        this(context, null);
    }

    TextComposerOverlay(Context context, DismissListener dismissListener) {
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext == null ? context : applicationContext;
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.dismissListener = dismissListener;
    }

    /** Returns false when overlay permission is unavailable or the window cannot be attached. */
    public synchronized boolean show() {
        if (closed) return false;
        if (attached) return true;
        if (windowManager == null || !Settings.canDrawOverlays(context)) return false;

        Context localizedContext = LocalizedStrings.context(context);
        FrameLayout inflationParent = new FrameLayout(localizedContext);
        View candidate = LayoutInflater.from(localizedContext)
                .inflate(R.layout.overlay_text_composer, inflationParent, false);
        TextComposerEditText candidateEditor = candidate.findViewById(R.id.text_composer_input);
        ImageButton closeButton = candidate.findViewById(R.id.text_composer_close);
        candidateEditor.setBackAction(this::close);
        View.OnKeyListener backListener = (view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) close();
            return true;
        };
        candidateEditor.setOnKeyListener(backListener);
        closeButton.setOnKeyListener(backListener);
        closeButton.setOnClickListener(view -> close());

        WindowManager.LayoutParams parameters = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        parameters.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        parameters.gravity = Gravity.TOP | Gravity.START;
        parameters.y = dp(48);
        parameters.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;

        try {
            windowManager.addView(candidate, parameters);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Could not attach text composer overlay", exception);
            return false;
        }

        rootView = candidate;
        editor = candidateEditor;
        attached = true;

        candidateEditor.requestFocus();
        candidateEditor.post(() -> showKeyboard(candidate, candidateEditor));
        return true;
    }

    public synchronized boolean isShowing() {
        return attached && !closed;
    }

    private synchronized void showKeyboard(View expectedRoot,
                                           TextComposerEditText expectedEditor) {
        if (!attached || closed || rootView != expectedRoot || editor != expectedEditor) return;
        try {
            InputMethodManager inputMethodManager =
                    (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.showSoftInput(expectedEditor, InputMethodManager.SHOW_IMPLICIT);
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not show the keyboard", exception);
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;

        View viewToRemove = rootView;
        TextComposerEditText editorToClear = editor;
        boolean removeAttachedView = attached && viewToRemove != null;
        attached = false;
        rootView = null;
        editor = null;

        if (editorToClear != null) {
            editorToClear.setBackAction(null);
            try {
                InputMethodManager inputMethodManager =
                        (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (inputMethodManager != null) {
                    inputMethodManager.hideSoftInputFromWindow(editorToClear.getWindowToken(), 0);
                }
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not hide the keyboard", exception);
            }
        }

        if (removeAttachedView) {
            try {
                windowManager.removeView(viewToRemove);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Could not remove text composer overlay", exception);
            }
        }

        if (dismissListener != null) {
            try {
                dismissListener.onDismissed(this);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Text composer dismissal listener failed", exception);
            }
        }
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
