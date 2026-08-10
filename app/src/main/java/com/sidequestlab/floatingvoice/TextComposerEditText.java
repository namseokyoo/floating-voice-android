package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;

import androidx.appcompat.widget.AppCompatEditText;

/** Minimal pre-IME Back bridge so the composer Activity can finish while the editor is focused. */
public final class TextComposerEditText extends AppCompatEditText {
    private Runnable backAction;

    public TextComposerEditText(Context context) {
        super(context);
    }

    public TextComposerEditText(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    public TextComposerEditText(Context context, AttributeSet attributes, int defaultStyleAttribute) {
        super(context, attributes, defaultStyleAttribute);
    }

    void setBackAction(Runnable backAction) {
        this.backAction = backAction;
    }

    @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        Runnable action = backAction;
        if (keyCode == KeyEvent.KEYCODE_BACK && action != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                action.run();
            }
            return true;
        }
        return super.onKeyPreIme(keyCode, event);
    }
}
