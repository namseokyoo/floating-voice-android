package com.sidequestlab.floatingvoice;

import java.io.File;
import java.util.Objects;

final class RecordingCancelOperation {
    interface RecorderPort {
        void stop();
        void release();
    }

    interface FilePort {
        boolean exists(File file);
        boolean delete(File file);
    }

    enum Outcome {
        CANCELED,
        RETAINED_STOP_FAILURE,
        RETAINED_RELEASE_FAILURE,
        RETAINED_DELETE_FAILURE
    }

    record Result(Outcome outcome, File recording) {
        boolean canceled() {
            return outcome == Outcome.CANCELED;
        }

        boolean releaseFailed() {
            return outcome == Outcome.RETAINED_RELEASE_FAILURE;
        }
    }

    Result execute(RecorderPort recorder, File recording, FilePort files) {
        Objects.requireNonNull(files, "files");
        boolean stopped = false;
        boolean released = recorder != null;
        try {
            if (recorder == null) throw new IllegalStateException("Recorder is not active");
            recorder.stop();
            stopped = true;
        } catch (RuntimeException ignored) {
            // The caller retains the file when stop cannot complete safely.
        } finally {
            if (recorder != null) {
                try {
                    recorder.release();
                } catch (RuntimeException ignored) {
                    released = false;
                }
            }
        }
        if (!released) return new Result(Outcome.RETAINED_RELEASE_FAILURE, recording);
        if (!stopped) return new Result(Outcome.RETAINED_STOP_FAILURE, recording);
        if (recording == null || !files.exists(recording) || files.delete(recording)) {
            return new Result(Outcome.CANCELED, recording);
        }
        return new Result(Outcome.RETAINED_DELETE_FAILURE, recording);
    }
}
