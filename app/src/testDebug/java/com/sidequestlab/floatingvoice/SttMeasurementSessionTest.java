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
        assertEquals(250L, recorded.latencyMs());
        assertTrue(session.summaryText().contains("1/20"));
        assertTrue(session.summaryText().contains("250ms"));
    }
}
