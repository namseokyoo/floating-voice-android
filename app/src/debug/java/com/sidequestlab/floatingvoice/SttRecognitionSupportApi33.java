package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.content.Intent;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.SpeechRecognizer;
import android.widget.TextView;

import androidx.annotation.RequiresApi;

import java.util.List;
import java.util.Locale;

/** API 33 boundary isolated so API 29-32 never resolve RecognitionSupport on the Activity. */
@RequiresApi(33)
final class SttRecognitionSupportApi33 {
    private SttRecognitionSupportApi33() { }

    static void check(Context context, Intent intent, TextView output) {
        SpeechRecognizer probe;
        try {
            probe = SpeechRecognizer.createSpeechRecognizer(context);
        } catch (RuntimeException e) {
            output.append(context.getString(R.string.debug_stt_support_probe_create_failed));
            return;
        }
        probe.checkRecognitionSupport(intent, context.getMainExecutor(),
                new RecognitionSupportCallback() {
                    @Override public void onSupportResult(RecognitionSupport support) {
                        output.append(context.getString(R.string.debug_stt_support_languages,
                                containsKorean(support.getOnlineLanguages()),
                                containsKorean(support.getInstalledOnDeviceLanguages()),
                                containsKorean(support.getPendingOnDeviceLanguages()),
                                containsKorean(support.getSupportedOnDeviceLanguages())));
                        probe.destroy();
                    }

                    @Override public void onError(int error) {
                        output.append(context.getString(R.string.debug_stt_support_probe_error, error));
                        probe.destroy();
                    }
                });
    }

    private static String containsKorean(List<String> languages) {
        if (languages == null) return "?";
        for (String language : languages) {
            if (language != null && language.toLowerCase(Locale.ROOT).startsWith("ko")) return "Y";
        }
        return "N";
    }
}
