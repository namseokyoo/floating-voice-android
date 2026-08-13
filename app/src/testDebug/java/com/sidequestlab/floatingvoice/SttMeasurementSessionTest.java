package com.sidequestlab.floatingvoice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SttMeasurementSessionTest {
    @Test
    public void corpusIsFixedAndAnAttemptAcceptsOnlyItsCurrentGeneration() {
        SttMeasurementSession session = new SttMeasurementSession();

        assertEquals(20, session.phraseCount());
        assertEquals("오늘 회의는 오후 세 시에 시작합니다.", session.expectedPhrase());

        long first = session.beginAttempt(1_000L, SttMeasurementSession.RecognizerMode.STANDARD,
                SttMeasurementSession.Environment.ONLINE);
        session.acceptPartial(first, "오늘 회의는");
        assertEquals("오늘 회의는", session.partialText());

        session.cancelAttempt();
        assertFalse(session.acceptFinal(first, "오래된 결과", 2_000L));
        assertTrue(session.rawFinalText().isEmpty());

        long second = session.beginAttempt(3_000L, SttMeasurementSession.RecognizerMode.ON_DEVICE,
                SttMeasurementSession.Environment.AIRPLANE_MODE);
        session.markSpeechEnded(second, 4_000L);
        assertTrue(session.acceptFinal(second, "오늘 회의는 오후 세 시에 시작합니다.", 4_250L));
        assertEquals(250L, session.currentLatencyMs());
        assertEquals(SttMeasurementSession.RecognizerMode.ON_DEVICE, session.currentMode());
        assertEquals(SttMeasurementSession.Environment.AIRPLANE_MODE, session.currentEnvironment());
        assertEquals(1, SttMeasurementSession.correctionDistance("안녕하세오", "안녕하세요"));

        SttMeasurementSession.Measurement recorded =
                session.recordCurrentCorrection("오늘 회의는 오후 세 시에 시작합니다.");
        assertEquals(1, session.recordedCount());
        assertEquals(0, recorded.correctionDistance());
        assertEquals(0.0d, recorded.correctionRatePercent(), 0.001d);
        assertEquals(250L, recorded.latencyMs());
        assertEquals(0, recorded.errorCode());
        assertTrue(session.summaryText().contains("1/20"));
        assertTrue(session.summaryText().contains("250ms"));
    }

    @Test
    public void failuresAndModeSpecificRetriesAreAccumulatedWithErrorCodes() {
        SttMeasurementSession session = new SttMeasurementSession();

        long standard = session.beginAttempt(1_000L,
                SttMeasurementSession.RecognizerMode.STANDARD,
                SttMeasurementSession.Environment.ONLINE);
        assertTrue(session.recordFailure(standard, 6, "SPEECH_TIMEOUT", 2_500L));

        long onDevice = session.beginAttempt(3_000L,
                SttMeasurementSession.RecognizerMode.ON_DEVICE,
                SttMeasurementSession.Environment.AIRPLANE_MODE);
        session.markSpeechEnded(onDevice, 4_000L);
        assertTrue(session.acceptFinal(onDevice, "안녕하세오", 4_120L));
        SttMeasurementSession.Measurement success = session.recordCurrentCorrection("안녕하세요");

        assertEquals(2, session.recordedCount());
        assertEquals(20.0d, success.correctionRatePercent(), 0.001d);
        assertEquals(0, success.errorCode());
        String summary = session.summaryText();
        assertTrue(summary.contains("error=6:SPEECH_TIMEOUT"));
        assertTrue(summary.contains("STANDARD attempts=1 success=0"));
        assertTrue(summary.contains("ON_DEVICE attempts=1 success=1"));
        assertTrue(summary.contains("medianCorrection=20.0%"));
    }

    @Test
    public void duplicateRecordTapUpdatesOneSuccessfulAttemptInsteadOfAddingRows() {
        SttMeasurementSession session = new SttMeasurementSession();
        long generation = session.beginAttempt(0L,
                SttMeasurementSession.RecognizerMode.STANDARD,
                SttMeasurementSession.Environment.ONLINE);
        assertTrue(session.acceptFinal(generation, "안녕하세오", 100L));

        session.recordCurrentCorrection("안녕하세오");
        SttMeasurementSession.Measurement corrected = session.recordCurrentCorrection("안녕하세요");

        assertEquals(1, session.recordedCount());
        assertEquals(1, corrected.correctionDistance());
        assertEquals(20.0d, corrected.correctionRatePercent(), 0.001d);
    }

    @Test
    public void immediateAndBlankFinalFailuresAreVisibleInTheSummary() {
        SttMeasurementSession session = new SttMeasurementSession();
        session.recordImmediateFailure(SttMeasurementSession.RecognizerMode.ON_DEVICE,
                SttMeasurementSession.Environment.AIRPLANE_MODE, 1002, "CREATE_FAILED");
        long generation = session.beginAttempt(100L,
                SttMeasurementSession.RecognizerMode.STANDARD,
                SttMeasurementSession.Environment.ONLINE);
        assertTrue(session.acceptFinal(generation, "", 250L));
        session.recordBlankFinalFailure();

        assertEquals(2, session.recordedCount());
        assertTrue(session.summaryText().contains("error=1002:CREATE_FAILED"));
        assertTrue(session.summaryText().contains("error=1001:EMPTY_FINAL"));
    }
}
