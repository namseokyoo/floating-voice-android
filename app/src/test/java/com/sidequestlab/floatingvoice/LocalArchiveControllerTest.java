package com.sidequestlab.floatingvoice;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LocalArchiveControllerTest {
    @Test public void verifiedCopyDeletesSourceOnlyAfterReadbackMatches() {
        FakeStorage storage = new FakeStorage("voice.ogg", bytes("opus-data"));

        LocalArchiveController.Result result = new LocalArchiveController().archive(storage);

        assertEquals(LocalArchiveController.Outcome.VERIFIED_SOURCE_DELETED, result.outcome());
        assertEquals("voice.ogg", result.targetName());
        assertTrue(storage.sourceDeleted);
        assertEquals("opus-data", text(storage.targets.get(result.targetId())));
    }

    @Test public void duplicateAndUnicodeNamesCreateUniqueDocument() {
        FakeStorage storage = new FakeStorage("한글 음성.ogg", bytes("audio"));
        storage.targets.put("existing", bytes("old"));
        storage.names.put("한글 음성.ogg", "existing");

        LocalArchiveController.Result result = new LocalArchiveController().archive(storage);

        assertEquals(LocalArchiveController.Outcome.VERIFIED_SOURCE_DELETED, result.outcome());
        assertEquals("한글 음성 (2).ogg", result.targetName());
    }

    @Test public void unsafeAndLongFilenameIsSanitizedAndBoundedWithoutLosingUnicode() {
        String longName = "한글/폴더\\이름\u0000" + "가".repeat(140) + ".ogg";
        FakeStorage storage = new FakeStorage(longName, bytes("audio"));

        LocalArchiveController.Result result = new LocalArchiveController().archive(storage);

        assertEquals(LocalArchiveController.Outcome.VERIFIED_SOURCE_DELETED, result.outcome());
        assertTrue(result.targetName().startsWith("한글_폴더_이름_"));
        assertTrue(result.targetName().endsWith(".ogg"));
        assertTrue(result.targetName().length() <= 120);
    }

    @Test public void emptyOrShortSourceNeverDeletesSourceAndCleansPartialTarget() {
        FakeStorage empty = new FakeStorage("empty.ogg", new byte[0]);
        assertEquals(LocalArchiveController.Outcome.FAILED_SOURCE_RETAINED,
                new LocalArchiveController().archive(empty).outcome());
        assertFalse(empty.sourceDeleted);
        assertTrue(empty.targets.isEmpty());

        FakeStorage shortCopy = new FakeStorage("short.ogg", bytes("123456"));
        shortCopy.reportedSourceSize = 9;
        LocalArchiveController.Result result = new LocalArchiveController().archive(shortCopy);
        assertEquals(LocalArchiveController.Outcome.FAILED_SOURCE_RETAINED, result.outcome());
        assertFalse(shortCopy.sourceDeleted);
        assertTrue(shortCopy.targets.isEmpty());
    }

    @Test public void createCopyAndReadbackFailuresRetainSourceAndCleanPartial() {
        for (Failure failure : new Failure[] {Failure.CREATE, Failure.WRITE, Failure.READ}) {
            FakeStorage storage = new FakeStorage("voice.ogg", bytes("data"));
            storage.failure = failure;
            LocalArchiveController.Result result = new LocalArchiveController().archive(storage);
            assertEquals(failure.name(), LocalArchiveController.Outcome.FAILED_SOURCE_RETAINED,
                    result.outcome());
            assertFalse(failure.name(), storage.sourceDeleted);
            assertTrue(failure.name(), storage.targets.isEmpty());
        }
    }

    @Test public void hashMismatchRetainsSourceAndDeletesTarget() {
        FakeStorage storage = new FakeStorage("voice.ogg", bytes("original"));
        storage.failure = Failure.HASH_MISMATCH;

        LocalArchiveController.Result result = new LocalArchiveController().archive(storage);

        assertEquals(LocalArchiveController.Outcome.FAILED_SOURCE_RETAINED, result.outcome());
        assertFalse(storage.sourceDeleted);
        assertTrue(storage.targets.isEmpty());
    }

    @Test public void unsupportedReadbackReportsUnverifiedAndRetainsBothFiles() {
        FakeStorage storage = new FakeStorage("voice.ogg", bytes("audio"));
        storage.failure = Failure.UNSUPPORTED_READBACK;

        LocalArchiveController.Result result = new LocalArchiveController().archive(storage);

        assertEquals(LocalArchiveController.Outcome.COPIED_UNVERIFIED_SOURCE_RETAINED,
                result.outcome());
        assertFalse(storage.sourceDeleted);
        assertEquals(1, storage.targets.size());
    }

    @Test public void sourceDeleteFailureReportsVerifiedTargetAndRetainedSource() {
        FakeStorage storage = new FakeStorage("voice.ogg", bytes("audio"));
        storage.failure = Failure.DELETE_SOURCE;

        LocalArchiveController.Result result = new LocalArchiveController().archive(storage);

        assertEquals(LocalArchiveController.Outcome.VERIFIED_SOURCE_RETAINED, result.outcome());
        assertFalse(storage.sourceDeleted);
        assertEquals(1, storage.targets.size());
    }

    @Test public void failedPartialCleanupIsReportedAndNeverDeletesSource() {
        FakeStorage storage = new FakeStorage("voice.ogg", bytes("original"));
        storage.failure = Failure.HASH_MISMATCH_AND_DELETE_TARGET;

        LocalArchiveController.Result result = new LocalArchiveController().archive(storage);

        assertEquals(LocalArchiveController.Outcome.FAILED_SOURCE_RETAINED, result.outcome());
        assertTrue(result.partialTargetRetained());
        assertFalse(storage.sourceDeleted);
        assertEquals(1, storage.targets.size());
    }

    private enum Failure {
        NONE, CREATE, WRITE, READ, HASH_MISMATCH, HASH_MISMATCH_AND_DELETE_TARGET,
        UNSUPPORTED_READBACK, DELETE_SOURCE
    }

    private static final class FakeStorage implements LocalArchiveController.StoragePort {
        final String sourceName;
        final byte[] source;
        final Map<String, byte[]> targets = new HashMap<>();
        final Map<String, String> names = new HashMap<>();
        long reportedSourceSize;
        boolean sourceDeleted;
        Failure failure = Failure.NONE;
        int nextId;

        FakeStorage(String sourceName, byte[] source) {
            this.sourceName = sourceName;
            this.source = source;
            this.reportedSourceSize = source.length;
        }
        @Override public boolean sourceExists() { return !sourceDeleted; }
        @Override public long sourceSize() { return reportedSourceSize; }
        @Override public String sourceName() { return sourceName; }
        @Override public InputStream openSource() { return new ByteArrayInputStream(source); }
        @Override public boolean targetExists(String displayName) { return names.containsKey(displayName); }
        @Override public String createTarget(String displayName) throws IOException {
            if (failure == Failure.CREATE) throw new IOException("disconnected");
            String id = "target-" + (++nextId);
            targets.put(id, new byte[0]);
            names.put(displayName, id);
            return id;
        }
        @Override public OutputStream openTargetOutput(String targetId) throws IOException {
            if (failure == Failure.WRITE) throw new IOException("folder full");
            return new ByteArrayOutputStream() {
                @Override public void close() throws IOException {
                    super.close();
                    targets.put(targetId, toByteArray());
                }
            };
        }
        @Override public InputStream openTargetInput(String targetId) throws IOException {
            if (failure == Failure.UNSUPPORTED_READBACK) {
                throw new LocalArchiveController.ReadbackUnsupportedException("unsupported");
            }
            if (failure == Failure.READ) throw new IOException("USB removed");
            byte[] value = targets.get(targetId);
            if (failure == Failure.HASH_MISMATCH
                    || failure == Failure.HASH_MISMATCH_AND_DELETE_TARGET) {
                value = bytes("corrupt!");
            }
            return new ByteArrayInputStream(value);
        }
        @Override public boolean deleteTarget(String targetId) {
            if (failure == Failure.HASH_MISMATCH_AND_DELETE_TARGET) {
                throw new IllegalStateException("provider refused partial cleanup");
            }
            String name = names.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(targetId))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (name != null) names.remove(name);
            return targets.remove(targetId) != null;
        }
        @Override public boolean deleteSource() {
            if (failure == Failure.DELETE_SOURCE) return false;
            sourceDeleted = true;
            return true;
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
}
