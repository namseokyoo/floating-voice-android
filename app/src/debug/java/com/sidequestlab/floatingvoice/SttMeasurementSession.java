package com.sidequestlab.floatingvoice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Debug-only in-memory measurement state. Never persists transcripts. */
public final class SttMeasurementSession {
    public enum RecognizerMode { STANDARD, ON_DEVICE }
    public enum Environment { ONLINE, AIRPLANE_MODE }
    public record Measurement(int phraseNumber, RecognizerMode mode, Environment environment,
                              long latencyMs, int correctionDistance,
                              double correctionRatePercent, boolean success,
                              int errorCode, String errorName) { }

    private static final List<String> CORPUS = List.of(
            "오늘 회의는 오후 세 시에 시작합니다.",
            "집에 도착하면 전화해 주세요.",
            "우유와 달걀을 사야 합니다.",
            "내일 서울은 비가 올 수도 있습니다.",
            "아이 약은 저녁 식사 후에 먹입니다.",
            "주차한 위치를 잊지 않도록 메모해 줘.",
            "카카오톡으로 일정 링크를 공유해 주세요.",
            "Floating Voice 버전 영 점 칠 점 영을 테스트합니다.",
            "API 응답 시간이 이 초를 넘었습니다.",
            "GitHub Release에서 APK를 내려받았습니다.",
            "SpeechRecognizer가 한국어를 제대로 인식하는지 확인합니다.",
            "MediaRecorder와 동시에 마이크를 사용하면 안 됩니다.",
            "OGG Opus 파일을 텔레그램 음성으로 보냅니다.",
            "OLED 시뮬레이션 결과를 다시 확인해 주세요.",
            "TDLib temporary message ID를 기록합니다.",
            "텔레그램 개인 비서 봇으로 음성을 보냅니다.",
            "세탁기가 끝나면 빨래를 건조대로 옮깁니다.",
            "어린이집 준비물은 가방 앞주머니에 넣었습니다.",
            "거실에서 텔레비전 소리가 나는 동안 받아쓰기를 시험합니다.",
            "내용을 확인하고 공유 버튼을 눌러 주세요."
    );

    private int phraseIndex;
    private long generation;
    private boolean active;
    private long startedAtMs;
    private long speechEndedAtMs = -1L;
    private long latencyMs = -1L;
    private String partialText = "";
    private String finalText = "";
    private RecognizerMode mode = RecognizerMode.STANDARD;
    private Environment environment = Environment.ONLINE;
    private final List<Measurement> measurements = new ArrayList<>();
    private int currentSuccessMeasurementIndex = -1;

    public int phraseCount() { return CORPUS.size(); }
    public int phraseNumber() { return phraseIndex + 1; }
    public String expectedPhrase() { return CORPUS.get(phraseIndex); }
    public String partialText() { return partialText; }
    public String rawFinalText() { return finalText; }
    public long currentLatencyMs() { return latencyMs; }
    public RecognizerMode currentMode() { return mode; }
    public Environment currentEnvironment() { return environment; }
    public int recordedCount() { return measurements.size(); }

    public void selectPhrase(int zeroBasedIndex) {
        if (zeroBasedIndex < 0 || zeroBasedIndex >= CORPUS.size()) {
            throw new IllegalArgumentException("phrase index out of range");
        }
        cancelAttempt();
        phraseIndex = zeroBasedIndex;
        clearResult();
    }

    public long beginAttempt(long nowMs, RecognizerMode requestedMode,
                             Environment measuredEnvironment) {
        generation++;
        active = true;
        startedAtMs = nowMs;
        speechEndedAtMs = -1L;
        mode = requestedMode;
        environment = measuredEnvironment;
        clearResult();
        return generation;
    }

    public boolean acceptPartial(long callbackGeneration, String text) {
        if (!active || callbackGeneration != generation) return false;
        partialText = safe(text);
        return true;
    }

    public boolean acceptFinal(long callbackGeneration, String text, long nowMs) {
        if (!active || callbackGeneration != generation) return false;
        active = false;
        finalText = safe(text);
        partialText = "";
        latencyMs = elapsedFromSpeechEndOrStart(nowMs);
        return true;
    }

    public boolean markSpeechEnded(long callbackGeneration, long nowMs) {
        if (!active || callbackGeneration != generation) return false;
        speechEndedAtMs = nowMs;
        return true;
    }

    public void cancelAttempt() {
        generation++;
        active = false;
        partialText = "";
        currentSuccessMeasurementIndex = -1;
    }

    public void failAttempt(long callbackGeneration) {
        if (active && callbackGeneration == generation) active = false;
    }

    public boolean recordFailure(long callbackGeneration, int errorCode,
                                 String errorName, long nowMs) {
        if (!active || callbackGeneration != generation) return false;
        active = false;
        partialText = "";
        finalText = "";
        latencyMs = elapsedFromSpeechEndOrStart(nowMs);
        currentSuccessMeasurementIndex = -1;
        measurements.add(new Measurement(
                phraseNumber(), mode, environment, latencyMs, -1,
                Double.NaN, false, errorCode, safe(errorName)));
        return true;
    }

    public Measurement recordImmediateFailure(RecognizerMode failedMode,
                                              Environment failedEnvironment,
                                              int errorCode, String errorName) {
        Measurement measurement = new Measurement(
                phraseNumber(), failedMode, failedEnvironment, -1L, -1,
                Double.NaN, false, errorCode, safe(errorName));
        measurements.add(measurement);
        return measurement;
    }

    public void clearResult() {
        partialText = "";
        finalText = "";
        latencyMs = -1L;
        currentSuccessMeasurementIndex = -1;
    }

    private long elapsedFromSpeechEndOrStart(long nowMs) {
        long latencyStart = speechEndedAtMs >= 0L ? speechEndedAtMs : startedAtMs;
        return Math.max(0L, nowMs - latencyStart);
    }

    public static int correctionDistance(String recognized, String corrected) {
        String left = safe(recognized);
        String right = safe(corrected);
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    public static double correctionRatePercent(String recognized, String corrected) {
        String reference = safe(corrected);
        int distance = correctionDistance(recognized, reference);
        if (reference.isEmpty()) return distance == 0 ? 0.0d : 100.0d;
        return 100.0d * distance / reference.length();
    }

    public Measurement recordCurrentCorrection(String correctedText) {
        int distance = correctionDistance(finalText, correctedText);
        double rate = correctionRatePercent(finalText, correctedText);
        Measurement measurement = new Measurement(
                phraseNumber(), mode, environment, latencyMs, distance, rate,
                !finalText.isEmpty(), 0, "");
        if (currentSuccessMeasurementIndex >= 0) {
            measurements.set(currentSuccessMeasurementIndex, measurement);
        } else {
            measurements.add(measurement);
            currentSuccessMeasurementIndex = measurements.size() - 1;
        }
        return measurement;
    }

    public Measurement recordBlankFinalFailure() {
        Measurement measurement = new Measurement(
                phraseNumber(), mode, environment, latencyMs, -1,
                Double.NaN, false, 1001, "EMPTY_FINAL");
        measurements.add(measurement);
        currentSuccessMeasurementIndex = -1;
        return measurement;
    }

    public String summaryText() {
        StringBuilder summary = new StringBuilder();
        for (Measurement measurement : measurements) {
            if (summary.length() > 0) summary.append('\n');
            summary.append(measurement.phraseNumber()).append('/').append(CORPUS.size())
                    .append(' ').append(measurement.mode())
                    .append(' ').append(measurement.environment())
                    .append(' ').append(measurement.latencyMs()).append("ms");
            if (measurement.success()) {
                summary.append(" corrections=").append(measurement.correctionDistance())
                        .append(" rate=")
                        .append(formatPercent(measurement.correctionRatePercent()));
            } else {
                summary.append(" error=").append(measurement.errorCode())
                        .append(':').append(measurement.errorName());
            }
            summary.append(" success=").append(measurement.success());
        }
        for (RecognizerMode recognizerMode : RecognizerMode.values()) {
            appendModeSummary(summary, recognizerMode);
        }
        return summary.toString();
    }

    private void appendModeSummary(StringBuilder summary, RecognizerMode recognizerMode) {
        List<Measurement> attempts = new ArrayList<>();
        List<Measurement> successes = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        List<Double> correctionRates = new ArrayList<>();
        for (Measurement measurement : measurements) {
            if (measurement.mode() != recognizerMode) continue;
            attempts.add(measurement);
            if (!measurement.success()) continue;
            successes.add(measurement);
            latencies.add(measurement.latencyMs());
            if (!Double.isNaN(measurement.correctionRatePercent())) {
                correctionRates.add(measurement.correctionRatePercent());
            }
        }
        if (attempts.isEmpty()) return;
        latencies.sort(Long::compareTo);
        correctionRates.sort(Double::compareTo);
        summary.append('\n').append(recognizerMode)
                .append(" attempts=").append(attempts.size())
                .append(" success=").append(successes.size())
                .append(" successRate=")
                .append(formatPercent(100.0d * successes.size() / attempts.size()))
                .append(" medianLatency=")
                .append(latencies.isEmpty() ? "n/a" : medianLong(latencies) + "ms")
                .append(" medianCorrection=")
                .append(correctionRates.isEmpty() ? "n/a" : formatPercent(medianDouble(correctionRates)));
    }

    private static long medianLong(List<Long> sorted) {
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) return sorted.get(middle);
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2L;
    }

    private static double medianDouble(List<Double> sorted) {
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) return sorted.get(middle);
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0d;
    }

    private static String formatPercent(double value) {
        return String.format(Locale.US, "%.1f%%", value);
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
