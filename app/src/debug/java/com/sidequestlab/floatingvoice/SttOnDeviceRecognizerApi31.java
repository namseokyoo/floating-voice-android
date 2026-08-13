package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.speech.SpeechRecognizer;

import androidx.annotation.RequiresApi;

/** API 31 boundary for on-device recognizer construction. */
@RequiresApi(31)
final class SttOnDeviceRecognizerApi31 {
    private SttOnDeviceRecognizerApi31() { }

    static SpeechRecognizer create(Context context) {
        return SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
    }
}
