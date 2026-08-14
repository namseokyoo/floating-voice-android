package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import com.sidequestlab.floatingvoice.core.SpeechShareEvent;
import com.sidequestlab.floatingvoice.core.SpeechShareStateMachine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Main-thread owner of one explicit user-stopped speech-recognition session.
 * It chains fresh, short platform recognizers while retaining one audio lease and generation.
 * It never triggers a model download or shares transcript text automatically.
 */
public final class SystemSpeechRecognizerController {
    public enum StartResult {
        STARTED,
        OWNERSHIP_DENIED,
        UNAVAILABLE,
        KEYBOARD_REQUIRED,
        DESTROYED
    }

    public enum OutcomeType {
        OWNERSHIP_DENIED,
        UNAVAILABLE,
        KEYBOARD_REQUIRED,
        SUPPORT_CHANGED,
        LISTENING,
        PARTIAL_RESULT,
        PROCESSING,
        FINAL_RESULT,
        ERROR,
        CANCELED,
        DESTROYED,
        DESTROY_FAILED
    }

    public enum PlatformError {
        BUSY,
        NETWORK,
        NO_MATCH,
        TIMEOUT,
        PERMISSION,
        AUDIO,
        LANGUAGE,
        SERVER,
        CLIENT,
        OTHER
    }

    public enum ErrorKind {
        START_FAILED,
        BUSY,
        NETWORK,
        NO_MATCH,
        TIMEOUT,
        PERMISSION,
        AUDIO,
        LANGUAGE,
        SERVER,
        CLIENT,
        OTHER
    }

    public enum PlatformSupport {
        READY,
        DOWNLOAD_REQUIRED,
        UNSUPPORTED,
        ERROR
    }

    public record RecognitionRequest(
            String languageTag,
            boolean partialResults,
            boolean preferOffline,
            String callingPackage) {
        public RecognitionRequest {
            Objects.requireNonNull(languageTag, "languageTag");
            Objects.requireNonNull(callingPackage, "callingPackage");
        }
    }

    public record Outcome(
            OutcomeType type,
            long generation,
            String text,
            ErrorKind error,
            SpeechRecognitionSupport support) {
        public Outcome {
            Objects.requireNonNull(type, "type");
        }
    }

    public interface Listener {
        void onOutcome(Outcome outcome);
    }

    public interface Callback {
        void onPartialResult(String text);
        void onProcessing();
        void onFinalResult(String text);
        void onError(PlatformError error);
    }

    public interface SupportCallback {
        void onResult(PlatformSupport support);
    }

    public interface Recognizer {
        void startListening(RecognitionRequest request);
        void stopListening();
        void cancel();
        void destroy();
    }

    /** Fakeable boundary around API-level checks and physical SpeechRecognizer calls. */
    public interface Platform {
        void assertMainThread();
        int apiLevel();
        String callingPackage();
        boolean isRecognitionAvailable();
        boolean isOnDeviceRecognitionAvailable();
        Recognizer createStandardRecognizer(Callback callback);
        Recognizer createOnDeviceRecognizer(Callback callback);
        void checkRecognitionSupport(
                Recognizer recognizer, RecognitionRequest request, SupportCallback callback);
        void triggerModelDownload(Recognizer recognizer, RecognitionRequest request);
    }

    private static final String KOREAN_LANGUAGE = "ko-KR";

    private final AudioCaptureCoordinator coordinator;
    private final Platform platform;
    private final Listener listener;
    private Session active;
    private boolean destroyed;
    private boolean destroyedOutcomeSent;
    private long lastGeneration;

    public SystemSpeechRecognizerController(
            AudioCaptureCoordinator coordinator, Platform platform, Listener listener) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public static SystemSpeechRecognizerController create(
            Context context, AudioCaptureCoordinator coordinator, Listener listener) {
        return new SystemSpeechRecognizerController(
                coordinator, new AndroidPlatform(context.getApplicationContext()), listener);
    }

    public synchronized StartResult start() {
        platform.assertMainThread();
        if (destroyed) return StartResult.DESTROYED;
        if (active != null) {
            emit(OutcomeType.OWNERSHIP_DENIED, active.generation, null, null, null);
            return StartResult.OWNERSHIP_DENIED;
        }

        Optional<SpeechShareStateMachine.Transition> attempt = beginAttempt();
        if (attempt.isEmpty()) {
            emit(OutcomeType.OWNERSHIP_DENIED, 0L, null, null, null);
            return StartResult.OWNERSHIP_DENIED;
        }
        long generation = coordinator.speechGeneration();
        lastGeneration = generation;

        if (!platform.isRecognitionAvailable()) {
            failWithoutRecognizer(generation, OutcomeType.UNAVAILABLE,
                    SpeechRecognitionSupport.unavailable(
                            SpeechRecognitionSupport.ModelState.UNSUPPORTED));
            return StartResult.UNAVAILABLE;
        }

        int api = platform.apiLevel();
        SpeechRecognitionSupport.Route route = SpeechRecognitionSupport.Route.STANDARD;
        SpeechRecognitionSupport.FallbackReason fallback =
                SpeechRecognitionSupport.FallbackReason.NONE;
        Session session = null;

        if (api >= 31) {
            if (platform.isOnDeviceRecognitionAvailable()) {
                try {
                    session = createSession(generation, SpeechRecognitionSupport.Route.ON_DEVICE,
                            fallback, true);
                    route = SpeechRecognitionSupport.Route.ON_DEVICE;
                } catch (RuntimeException onDeviceFailure) {
                    fallback = SpeechRecognitionSupport.FallbackReason.ON_DEVICE_CREATION_FAILED;
                }
            } else {
                fallback = SpeechRecognitionSupport.FallbackReason.ON_DEVICE_UNAVAILABLE;
            }
        }

        if (session == null) {
            try {
                session = createSession(generation, route, fallback, false);
            } catch (RuntimeException standardFailure) {
                failWithoutRecognizer(generation, OutcomeType.KEYBOARD_REQUIRED,
                        SpeechRecognitionSupport.keyboardRequired(
                                SpeechRecognitionSupport.FallbackReason.STANDARD_CREATION_FAILED));
                return StartResult.KEYBOARD_REQUIRED;
            }
        }

        active = session;
        if (api >= 33) {
            SpeechRecognitionSupport checking = SpeechRecognitionSupport.available(
                    session.route, SpeechRecognitionSupport.ModelState.CHECKING,
                    session.fallbackReason);
            emit(OutcomeType.SUPPORT_CHANGED, generation, null, null, checking);
            Cycle cycle = session.cycle;
            if (!isCurrent(session, cycle)) return StartResult.STARTED;
            Session captured = session;
            try {
                platform.checkRecognitionSupport(cycle.recognizer, session.request,
                        support -> handleSupportResult(captured, cycle, support));
            } catch (RuntimeException supportFailure) {
                handleSupportResult(captured, cycle, PlatformSupport.ERROR);
            }
            return StartResult.STARTED;
        }

        return beginListening(session, session.cycle)
                ? StartResult.STARTED : StartResult.UNAVAILABLE;
    }

    public synchronized void cancel() {
        platform.assertMainThread();
        Session session = active;
        if (session == null || session.terminal) return;
        Cycle cycle = session.cycle;
        if (cycle != null) cycle.terminal = true;
        try {
            if (cycle != null) cycle.recognizer.cancel();
        } catch (RuntimeException ignored) {
            // Destruction below is the authoritative release boundary.
        }
        terminate(session, SpeechShareEvent.cancel(session.generation),
                OutcomeType.CANCELED, null, null, false);
    }

    /** Requests the current platform cycle to finish; its next terminal callback enters review. */
    public synchronized void stopListening() {
        platform.assertMainThread();
        Session session = active;
        if (session == null || session.terminal || session.stopRequested) return;
        session.stopRequested = true;
        Cycle cycle = session.cycle;
        if (cycle == null) {
            finishStoppedSession(session, null, ErrorKind.NO_MATCH);
            return;
        }
        try {
            cycle.recognizer.stopListening();
        } catch (RuntimeException stopFailure) {
            if (!isActive(session)) return;
            cycle.terminal = true;
            finishStoppedSession(session, cycle, ErrorKind.NO_MATCH);
        }
    }

    public synchronized void destroy() {
        platform.assertMainThread();
        if (destroyed) return;
        destroyed = true;
        Session session = active;
        if (session == null) {
            coordinator.completeSpeechInteraction(lastGeneration);
            emitDestroyedOnce(lastGeneration);
            return;
        }
        if (session.terminal) return;
        Cycle cycle = session.cycle;
        if (cycle != null) cycle.terminal = true;
        try {
            if (cycle != null) cycle.recognizer.cancel();
        } catch (RuntimeException ignored) {
            // Destruction below is the authoritative release boundary.
        }
        terminate(session, SpeechShareEvent.cancel(session.generation),
                OutcomeType.CANCELED, null, null, true);
    }

    private Optional<SpeechShareStateMachine.Transition> beginAttempt() {
        SpeechShareStateMachine.State state = coordinator.speechState();
        if (state == SpeechShareStateMachine.State.IDLE) return coordinator.startSpeech();
        if (state == SpeechShareStateMachine.State.STT_FAILED
                || state == SpeechShareStateMachine.State.STT_CANCELED
                || state == SpeechShareStateMachine.State.STT_REVIEW) {
            return coordinator.retrySpeech();
        }
        return Optional.empty();
    }

    private Session createSession(
            long generation,
            SpeechRecognitionSupport.Route route,
            SpeechRecognitionSupport.FallbackReason fallback,
            boolean onDevice) {
        Session session = new Session(generation, route, fallback,
                new RecognitionRequest(KOREAN_LANGUAGE, true, true, platform.callingPackage()));
        session.cycle = createCycle(session, onDevice);
        return session;
    }

    private Cycle createCycle(Session session, boolean onDevice) {
        Cycle cycle = new Cycle();
        Callback callback = new Callback() {
            @Override public void onPartialResult(String text) {
                handlePartial(session, cycle, text);
            }

            @Override public void onProcessing() {
                handleProcessing(session, cycle);
            }

            @Override public void onFinalResult(String text) {
                handleFinal(session, cycle, text);
            }

            @Override public void onError(PlatformError error) {
                handleError(session, cycle, error);
            }
        };
        cycle.recognizer = onDevice
                ? platform.createOnDeviceRecognizer(callback)
                : platform.createStandardRecognizer(callback);
        if (cycle.recognizer == null) throw new IllegalStateException("null recognizer");
        return cycle;
    }

    private void handleSupportResult(
            Session session, Cycle cycle, PlatformSupport platformSupport) {
        synchronized (this) {
            platform.assertMainThread();
            if (!isCurrent(session, cycle) || session.supportResolved) return;
            session.supportResolved = true;
            SpeechRecognitionSupport.ModelState modelState = switch (platformSupport) {
                case READY -> SpeechRecognitionSupport.ModelState.READY;
                case DOWNLOAD_REQUIRED -> SpeechRecognitionSupport.ModelState.DOWNLOAD_REQUIRED;
                case UNSUPPORTED -> SpeechRecognitionSupport.ModelState.UNSUPPORTED;
                case ERROR -> SpeechRecognitionSupport.ModelState.ERROR;
            };
            SpeechRecognitionSupport support = platformSupport == PlatformSupport.READY
                    ? SpeechRecognitionSupport.available(
                            session.route, modelState, session.fallbackReason)
                    : new SpeechRecognitionSupport(
                            SpeechRecognitionSupport.Availability.UNAVAILABLE,
                            session.route, modelState, session.fallbackReason, true);
            emit(OutcomeType.SUPPORT_CHANGED, session.generation, null, null, support);
            if (!isCurrent(session, cycle)) return;
            if (platformSupport == PlatformSupport.READY) {
                beginListening(session, cycle);
            } else if (session.route == SpeechRecognitionSupport.Route.ON_DEVICE) {
                fallbackToStandard(session, cycle, switch (platformSupport) {
                    case DOWNLOAD_REQUIRED ->
                            SpeechRecognitionSupport.FallbackReason
                                    .ON_DEVICE_MODEL_DOWNLOAD_REQUIRED;
                    case UNSUPPORTED ->
                            SpeechRecognitionSupport.FallbackReason.ON_DEVICE_UNSUPPORTED;
                    case ERROR ->
                            SpeechRecognitionSupport.FallbackReason.ON_DEVICE_SUPPORT_ERROR;
                    case READY -> throw new IllegalStateException("ready already handled");
                });
            } else {
                terminate(session, SpeechShareEvent.supportUnavailable(session.generation),
                        OutcomeType.KEYBOARD_REQUIRED, null, null, false);
            }
        }
    }

    private void fallbackToStandard(
            Session session,
            Cycle onDeviceCycle,
            SpeechRecognitionSupport.FallbackReason fallbackReason) {
        if (!isCurrent(session, onDeviceCycle)) return;
        onDeviceCycle.terminal = true;
        if (!destroyCycleOnce(session, onDeviceCycle)) {
            session.terminal = true;
            return;
        }

        try {
            session.route = SpeechRecognitionSupport.Route.STANDARD;
            session.fallbackReason = fallbackReason;
            session.supportResolved = false;
            session.cycle = createCycle(session, false);
        } catch (RuntimeException standardFailure) {
            terminate(session, SpeechShareEvent.supportUnavailable(session.generation),
                    OutcomeType.KEYBOARD_REQUIRED, null, null, false);
            return;
        }

        Cycle standardCycle = session.cycle;
        SpeechRecognitionSupport checking = SpeechRecognitionSupport.available(
                session.route,
                SpeechRecognitionSupport.ModelState.CHECKING,
                session.fallbackReason);
        if (platform.apiLevel() >= 33) {
            emit(OutcomeType.SUPPORT_CHANGED, session.generation, null, null, checking);
            if (!isCurrent(session, standardCycle)) return;
            try {
                platform.checkRecognitionSupport(
                        standardCycle.recognizer,
                        session.request,
                        support -> handleSupportResult(session, standardCycle, support));
            } catch (RuntimeException supportFailure) {
                handleSupportResult(session, standardCycle, PlatformSupport.ERROR);
            }
        } else {
            emit(OutcomeType.SUPPORT_CHANGED, session.generation, null, null,
                    SpeechRecognitionSupport.available(
                            session.route,
                            SpeechRecognitionSupport.ModelState.NOT_APPLICABLE,
                            session.fallbackReason));
            if (!isCurrent(session, standardCycle)) return;
            beginListening(session, standardCycle);
        }
    }

    private boolean beginListening(Session session, Cycle cycle) {
        if (!isCurrent(session, cycle) || cycle.listeningStarted) return false;
        cycle.listeningStarted = true;
        SpeechRecognitionSupport.ModelState modelState = platform.apiLevel() >= 33
                ? SpeechRecognitionSupport.ModelState.READY
                : SpeechRecognitionSupport.ModelState.NOT_APPLICABLE;
        if (platform.apiLevel() < 33 && !session.captureStarted) {
            emit(OutcomeType.SUPPORT_CHANGED, session.generation, null, null,
                    SpeechRecognitionSupport.available(
                            session.route, modelState, session.fallbackReason));
            if (!isCurrent(session, cycle)) return false;
        }
        if (!session.captureStarted) {
            coordinator.acceptSpeech(SpeechShareEvent.supportAvailable(session.generation));
            session.captureStarted = true;
        }
        try {
            cycle.inStart = true;
            cycle.recognizer.startListening(session.request);
            cycle.inStart = false;
            if (!isCurrent(session, cycle)) return false;
            coordinator.acceptSpeech(SpeechShareEvent.listeningCycleStarted(session.generation));
            emit(OutcomeType.LISTENING, session.generation, null, null, null);
            return true;
        } catch (RuntimeException startFailure) {
            cycle.inStart = false;
            terminate(session, SpeechShareEvent.error(session.generation), OutcomeType.ERROR,
                    ErrorKind.START_FAILED, null, false);
            return false;
        }
    }

    private synchronized void handlePartial(Session session, Cycle cycle, String text) {
        platform.assertMainThread();
        if (!isCurrent(session, cycle)) return;
        String normalized = normalize(text);
        cycle.latestPartial = normalized;
        String visible = joinSegments(session.accumulated, normalized);
        SpeechShareStateMachine.Transition transition = coordinator.acceptSpeech(
                SpeechShareEvent.partialResult(session.generation, visible));
        if (!transition.effects().isEmpty() && visible != null) {
            emit(OutcomeType.PARTIAL_RESULT, session.generation, visible, null, null);
        }
    }

    private synchronized void handleProcessing(Session session, Cycle cycle) {
        platform.assertMainThread();
        if (!isCurrent(session, cycle)) return;
        SpeechShareStateMachine.Transition transition = coordinator.acceptSpeech(
                SpeechShareEvent.processing(session.generation));
        if (transition.nextState() == SpeechShareStateMachine.State.STT_PROCESSING) {
            emit(OutcomeType.PROCESSING, session.generation, null, null, null);
        }
    }

    private synchronized void handleFinal(Session session, Cycle cycle, String text) {
        platform.assertMainThread();
        if (!isCurrent(session, cycle)) return;
        String normalized = normalize(text);
        session.accumulated = joinSegments(session.accumulated, normalized);
        if (normalized != null) cycle.latestPartial = null;
        cycle.terminal = true;

        if (session.stopRequested) {
            finishStoppedSession(session, cycle, ErrorKind.NO_MATCH);
            return;
        }

        emitAccumulatedPreview(session);
        if (!isActive(session)) return;
        if (cycle.inStart) {
            terminate(session, SpeechShareEvent.error(session.generation), OutcomeType.ERROR,
                    ErrorKind.START_FAILED, null, false);
            return;
        }
        continueWithFreshCycle(session, cycle);
    }

    private synchronized void handleError(Session session, Cycle cycle, PlatformError error) {
        platform.assertMainThread();
        if (!isCurrent(session, cycle)) return;
        if (session.stopRequested) {
            cycle.terminal = true;
            finishStoppedSession(session, cycle, mapError(error));
            return;
        }
        if (error == PlatformError.LANGUAGE
                && session.route == SpeechRecognitionSupport.Route.ON_DEVICE) {
            fallbackToStandard(
                    session, cycle,
                    SpeechRecognitionSupport.FallbackReason.ON_DEVICE_LANGUAGE_ERROR);
            return;
        }
        if (error == PlatformError.NO_MATCH || error == PlatformError.TIMEOUT) {
            cycle.terminal = true;
            if (cycle.inStart) {
                terminate(session, SpeechShareEvent.error(session.generation), OutcomeType.ERROR,
                        ErrorKind.START_FAILED, null, false);
            } else {
                continueWithFreshCycle(session, cycle);
            }
            return;
        }
        terminate(session, SpeechShareEvent.error(session.generation), OutcomeType.ERROR,
                mapError(error), null, false);
    }

    private void emitAccumulatedPreview(Session session) {
        if (session.accumulated == null) return;
        SpeechShareStateMachine.Transition transition = coordinator.acceptSpeech(
                SpeechShareEvent.partialResult(session.generation, session.accumulated));
        if (!transition.effects().isEmpty()) {
            emit(OutcomeType.PARTIAL_RESULT, session.generation,
                    session.accumulated, null, null);
        }
    }

    private void continueWithFreshCycle(Session session, Cycle completedCycle) {
        if (!isActive(session) || session.stopRequested) return;
        if (!destroyCycleOnce(session, completedCycle)) {
            session.terminal = true;
            return;
        }
        if (!isActive(session) || session.stopRequested) return;
        Cycle next;
        try {
            next = createCycle(session,
                    session.route == SpeechRecognitionSupport.Route.ON_DEVICE);
        } catch (RuntimeException creationFailure) {
            terminate(session, SpeechShareEvent.error(session.generation), OutcomeType.ERROR,
                    ErrorKind.START_FAILED, null, false);
            return;
        }
        session.cycle = next;
        beginListening(session, next);
    }

    private void finishStoppedSession(
            Session session, Cycle cycle, ErrorKind emptyError) {
        if (!isActive(session)) return;
        String finalText = joinSegments(
                session.accumulated, cycle == null ? null : cycle.latestPartial);
        if (finalText == null) {
            terminate(session, SpeechShareEvent.error(session.generation), OutcomeType.ERROR,
                    emptyError, null, false);
        } else {
            terminate(session,
                    SpeechShareEvent.finalResult(session.generation, finalText),
                    OutcomeType.FINAL_RESULT, null, finalText, false);
        }
    }

    private void failWithoutRecognizer(
            long generation, OutcomeType type, SpeechRecognitionSupport support) {
        coordinator.acceptSpeech(SpeechShareEvent.supportUnavailable(generation));
        coordinator.finishSpeechCapture(generation);
        emit(type, generation, null, null, support);
    }

    private void terminate(
            Session session,
            SpeechShareEvent event,
            OutcomeType outcomeType,
            ErrorKind error,
            String text,
            boolean completeInteraction) {
        if (!isActive(session)) return;
        session.terminal = true;
        if (session.cycle != null) session.cycle.terminal = true;
        coordinator.acceptSpeech(event);
        emit(outcomeType, session.generation, text, error, null);

        boolean physicallyDestroyed = session.cycle == null
                || destroyCycleOnce(session, session.cycle);
        if (!physicallyDestroyed) return;
        active = null;
        coordinator.finishSpeechCapture(session.generation);
        if (completeInteraction) {
            coordinator.completeSpeechInteraction(session.generation);
            emitDestroyedOnce(session.generation);
        }
    }

    private boolean destroyCycleOnce(Session session, Cycle cycle) {
        if (cycle == null) return true;
        if (cycle.destroyAttempted) return cycle.destroySucceeded;
        cycle.destroyAttempted = true;
        try {
            cycle.recognizer.destroy();
            cycle.destroySucceeded = true;
            return true;
        } catch (RuntimeException destroyFailure) {
            emit(OutcomeType.DESTROY_FAILED, session.generation, null, null, null);
            return false;
        }
    }

    private boolean isActive(Session session) {
        return session != null && session == active && !session.terminal;
    }

    private boolean isCurrent(Session session, Cycle cycle) {
        return isActive(session) && cycle != null && cycle == session.cycle && !cycle.terminal;
    }

    private void emitDestroyedOnce(long generation) {
        if (destroyedOutcomeSent) return;
        destroyedOutcomeSent = true;
        emit(OutcomeType.DESTROYED, generation, null, null, null);
    }

    private void emit(
            OutcomeType type,
            long generation,
            String text,
            ErrorKind error,
            SpeechRecognitionSupport support) {
        listener.onOutcome(new Outcome(type, generation, text, error, support));
    }

    private static ErrorKind mapError(PlatformError error) {
        if (error == null) return ErrorKind.OTHER;
        return switch (error) {
            case BUSY -> ErrorKind.BUSY;
            case NETWORK -> ErrorKind.NETWORK;
            case NO_MATCH -> ErrorKind.NO_MATCH;
            case TIMEOUT -> ErrorKind.TIMEOUT;
            case PERMISSION -> ErrorKind.PERMISSION;
            case AUDIO -> ErrorKind.AUDIO;
            case LANGUAGE -> ErrorKind.LANGUAGE;
            case SERVER -> ErrorKind.SERVER;
            case CLIENT -> ErrorKind.CLIENT;
            case OTHER -> ErrorKind.OTHER;
        };
    }

    private static String normalize(String text) {
        if (text == null) return null;
        String normalized = text.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /** Joins accepted cycle text only; lexical overlap is intentionally preserved verbatim. */
    private static String joinSegments(String accepted, String next) {
        String normalizedNext = normalize(next);
        if (normalizedNext == null) return accepted;
        return accepted == null ? normalizedNext : accepted + " " + normalizedNext;
    }

    private static final class Session {
        final long generation;
        SpeechRecognitionSupport.Route route;
        SpeechRecognitionSupport.FallbackReason fallbackReason;
        final RecognitionRequest request;
        Cycle cycle;
        String accumulated;
        boolean terminal;
        boolean stopRequested;
        boolean supportResolved;
        boolean captureStarted;

        Session(
                long generation,
                SpeechRecognitionSupport.Route route,
                SpeechRecognitionSupport.FallbackReason fallbackReason,
                RecognitionRequest request) {
            this.generation = generation;
            this.route = route;
            this.fallbackReason = fallbackReason;
            this.request = request;
        }
    }

    private static final class Cycle {
        Recognizer recognizer;
        boolean terminal;
        boolean listeningStarted;
        boolean inStart;
        String latestPartial;
        boolean destroyAttempted;
        boolean destroySucceeded;
    }

    private static final class AndroidPlatform implements Platform {
        private final Context context;

        AndroidPlatform(Context context) {
            this.context = context;
        }

        @Override public void assertMainThread() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                throw new IllegalStateException("SpeechRecognizer calls must run on the main thread");
            }
        }

        @Override public int apiLevel() {
            return Build.VERSION.SDK_INT;
        }

        @Override public String callingPackage() {
            return context.getPackageName();
        }

        @Override public boolean isRecognitionAvailable() {
            return SpeechRecognizer.isRecognitionAvailable(context);
        }

        @Override public boolean isOnDeviceRecognitionAvailable() {
            return Build.VERSION.SDK_INT >= 31
                    && SpeechRecognizer.isOnDeviceRecognitionAvailable(context);
        }

        @Override public Recognizer createStandardRecognizer(Callback callback) {
            return wrap(SpeechRecognizer.createSpeechRecognizer(context), callback, false);
        }

        @Override public Recognizer createOnDeviceRecognizer(Callback callback) {
            if (Build.VERSION.SDK_INT < 31) throw new UnsupportedOperationException();
            return wrap(SpeechRecognizer.createOnDeviceSpeechRecognizer(context), callback, true);
        }

        @Override public void checkRecognitionSupport(
                Recognizer recognizer, RecognitionRequest request, SupportCallback callback) {
            if (Build.VERSION.SDK_INT < 33) throw new UnsupportedOperationException();
            AndroidRecognizer androidRecognizer = requireAndroidRecognizer(recognizer);
            androidRecognizer.delegate.checkRecognitionSupport(toIntent(request),
                    context.getMainExecutor(), new RecognitionSupportCallback() {
                        @Override public void onSupportResult(
                                android.speech.RecognitionSupport support) {
                            List<String> installed = support.getInstalledOnDeviceLanguages();
                            List<String> pending = support.getPendingOnDeviceLanguages();
                            List<String> supported = support.getSupportedOnDeviceLanguages();
                            List<String> online = support.getOnlineLanguages();
                            if (containsLanguage(installed, request.languageTag())
                                    || containsLanguage(online, request.languageTag())) {
                                callback.onResult(PlatformSupport.READY);
                            } else if (containsLanguage(pending, request.languageTag())
                                    || containsLanguage(supported, request.languageTag())) {
                                callback.onResult(PlatformSupport.DOWNLOAD_REQUIRED);
                            } else {
                                callback.onResult(PlatformSupport.UNSUPPORTED);
                            }
                        }

                        @Override public void onError(int error) {
                            callback.onResult(PlatformSupport.ERROR);
                        }
                    });
        }

        @Override public void triggerModelDownload(
                Recognizer recognizer, RecognitionRequest request) {
            if (Build.VERSION.SDK_INT < 33) throw new UnsupportedOperationException();
            requireAndroidRecognizer(recognizer).delegate.triggerModelDownload(toIntent(request));
        }

        private AndroidRecognizer wrap(
                SpeechRecognizer recognizer, Callback callback, boolean onDevice) {
            AndroidRecognizer wrapped = new AndroidRecognizer(recognizer, onDevice);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { callback.onProcessing(); }
                @Override public void onError(int error) { callback.onError(mapPlatformError(error)); }
                @Override public void onResults(Bundle results) {
                    callback.onFinalResult(firstResult(results));
                }
                @Override public void onPartialResults(Bundle partialResults) {
                    callback.onPartialResult(firstResult(partialResults));
                }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            return wrapped;
        }

        private AndroidRecognizer requireAndroidRecognizer(Recognizer recognizer) {
            if (!(recognizer instanceof AndroidRecognizer androidRecognizer)) {
                throw new IllegalArgumentException("recognizer was not created by this platform");
            }
            return androidRecognizer;
        }

        private static Intent toIntent(RecognitionRequest request) {
            return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, request.languageTag())
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, request.partialResults())
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, request.preferOffline())
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, request.callingPackage());
        }

        private static String firstResult(Bundle bundle) {
            if (bundle == null) return null;
            ArrayList<String> results = bundle.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION);
            return results == null || results.isEmpty() ? null : results.get(0);
        }

        private static boolean containsLanguage(List<String> languages, String languageTag) {
            if (languages == null) return false;
            Locale expected = Locale.forLanguageTag(languageTag);
            for (String language : languages) {
                if (expected.equals(Locale.forLanguageTag(language))) return true;
            }
            return false;
        }

        private static PlatformError mapPlatformError(int error) {
            return switch (error) {
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> PlatformError.BUSY;
                case SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> PlatformError.NETWORK;
                case SpeechRecognizer.ERROR_NO_MATCH -> PlatformError.NO_MATCH;
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> PlatformError.TIMEOUT;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> PlatformError.PERMISSION;
                case SpeechRecognizer.ERROR_AUDIO -> PlatformError.AUDIO;
                case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> PlatformError.LANGUAGE;
                case SpeechRecognizer.ERROR_SERVER,
                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> PlatformError.SERVER;
                case SpeechRecognizer.ERROR_CLIENT -> PlatformError.CLIENT;
                default -> PlatformError.OTHER;
            };
        }
    }

    private static final class AndroidRecognizer implements Recognizer {
        final SpeechRecognizer delegate;
        final boolean onDevice;

        AndroidRecognizer(SpeechRecognizer delegate, boolean onDevice) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.onDevice = onDevice;
        }

        @Override public void startListening(RecognitionRequest request) {
            delegate.startListening(AndroidPlatform.toIntent(request));
        }

        @Override public void stopListening() {
            delegate.stopListening();
        }

        @Override public void cancel() {
            delegate.cancel();
        }

        @Override public void destroy() {
            delegate.destroy();
        }
    }
}
