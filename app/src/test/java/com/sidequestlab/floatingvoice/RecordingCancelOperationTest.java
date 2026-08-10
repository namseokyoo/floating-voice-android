package com.sidequestlab.floatingvoice;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class RecordingCancelOperationTest {
    private final RecordingCancelOperation operation = new RecordingCancelOperation();
    private final File recording = new File("recording.ogg");

    @Test public void successStopsReleasesThenDeletes() {
        List<String> calls = new ArrayList<>();
        RecordingCancelOperation.Result result = operation.execute(
                recorder(calls, false, false), recording,
                files(calls, true, true));

        assertEquals(RecordingCancelOperation.Outcome.CANCELED, result.outcome());
        assertEquals(List.of("stop", "release", "exists", "delete"), calls);
    }

    @Test public void stopFailureStillReleasesAndNeverDeletes() {
        List<String> calls = new ArrayList<>();
        RecordingCancelOperation.Result result = operation.execute(
                recorder(calls, true, false), recording,
                files(calls, true, true));

        assertEquals(RecordingCancelOperation.Outcome.RETAINED_STOP_FAILURE, result.outcome());
        assertEquals(List.of("stop", "release"), calls);
    }

    @Test public void releaseFailureRetainsAndNeverDeletes() {
        List<String> calls = new ArrayList<>();
        RecordingCancelOperation.Result result = operation.execute(
                recorder(calls, false, true), recording,
                files(calls, true, true));

        assertEquals(RecordingCancelOperation.Outcome.RETAINED_RELEASE_FAILURE, result.outcome());
        assertEquals(List.of("stop", "release"), calls);
    }

    @Test public void deleteFailureRetainsTheCanceledFile() {
        List<String> calls = new ArrayList<>();
        RecordingCancelOperation.Result result = operation.execute(
                recorder(calls, false, false), recording,
                files(calls, true, false));

        assertEquals(RecordingCancelOperation.Outcome.RETAINED_DELETE_FAILURE, result.outcome());
        assertEquals(recording, result.recording());
    }

    @Test public void absentFileCountsAsCanceledWithoutDelete() {
        List<String> calls = new ArrayList<>();
        RecordingCancelOperation.Result result = operation.execute(
                recorder(calls, false, false), recording,
                files(calls, false, false));

        assertEquals(RecordingCancelOperation.Outcome.CANCELED, result.outcome());
        assertEquals(List.of("stop", "release", "exists"), calls);
    }

    private static RecordingCancelOperation.RecorderPort recorder(
            List<String> calls, boolean stopFails, boolean releaseFails) {
        return new RecordingCancelOperation.RecorderPort() {
            @Override public void stop() {
                calls.add("stop");
                if (stopFails) throw new IllegalStateException("stop");
            }
            @Override public void release() {
                calls.add("release");
                if (releaseFails) throw new IllegalStateException("release");
            }
        };
    }

    private static RecordingCancelOperation.FilePort files(
            List<String> calls, boolean exists, boolean deletes) {
        return new RecordingCancelOperation.FilePort() {
            @Override public boolean exists(File file) {
                calls.add("exists");
                return exists;
            }
            @Override public boolean delete(File file) {
                calls.add("delete");
                return deletes;
            }
        };
    }
}
