package com.sidequestlab.floatingvoice;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RetainedAudioShareStoreTest {
    @Test
    public void activePendingCannotBeDeletedAndCompletedFileSurvivesRestart() throws Exception {
        File root = Files.createTempDirectory("retained-audio-share").toFile();
        RetainedAudioShareStore store = new RetainedAudioShareStore(root);
        File pending = store.createPending(123L);
        assertNotNull(pending);
        Files.write(pending.toPath(), "ogg".getBytes(StandardCharsets.UTF_8));

        RetainedAudioShareStore.Entry active = store.list().get(0);
        assertTrue(active.active());
        assertFalse(active.completed());
        assertFalse(store.delete(active.id()));

        File completed = store.promoteCompleted(pending);
        assertNotNull(completed);
        RetainedAudioShareStore afterRestart = new RetainedAudioShareStore(root);
        assertEquals(1, afterRestart.list().size());
        RetainedAudioShareStore.Entry retained = afterRestart.list().get(0);
        assertTrue(retained.completed());
        assertFalse(retained.active());
        assertEquals("shared-voice-123.ogg", retained.name());

        assertTrue(afterRestart.delete(retained.id()));
        assertFalse(completed.exists());
        assertEquals(0, new RetainedAudioShareStore(root).list().size());
    }

    @Test
    public void interruptedPendingIsListedAfterRestartAndCanBeExplicitlyDeleted() throws Exception {
        File root = Files.createTempDirectory("retained-audio-share").toFile();
        RetainedAudioShareStore store = new RetainedAudioShareStore(root);
        File pending = store.createPending(456L);
        Files.write(pending.toPath(), "partial".getBytes(StandardCharsets.UTF_8));
        store.releaseActive(pending);

        RetainedAudioShareStore afterRestart = new RetainedAudioShareStore(root);
        RetainedAudioShareStore.Entry interrupted = afterRestart.list().get(0);
        assertFalse(interrupted.completed());
        assertFalse(interrupted.active());
        assertTrue(afterRestart.delete(interrupted.id()));
    }

    @Test
    public void deleteRejectsTraversalAndUnownedFiles() throws Exception {
        File root = Files.createTempDirectory("retained-audio-share").toFile();
        RetainedAudioShareStore store = new RetainedAudioShareStore(root);

        assertFalse(store.delete("../outside.ogg"));
        assertFalse(store.delete("retained/not-a-share.txt"));
        assertFalse(store.delete("retained/../../shared-voice-1.ogg"));
    }

    @Test
    public void symlinkOutsideOwnedRootIsNeitherListedNorDeleted() throws Exception {
        File root = Files.createTempDirectory("retained-audio-share").toFile();
        File retained = new File(root, "retained");
        assertTrue(retained.mkdirs());
        File outside = File.createTempFile("outside-audio", ".ogg");
        Files.write(outside.toPath(), "outside".getBytes(StandardCharsets.UTF_8));
        File link = new File(retained, "shared-voice-link.ogg");
        Files.createSymbolicLink(link.toPath(), outside.toPath());

        RetainedAudioShareStore store = new RetainedAudioShareStore(root);
        assertEquals(0, store.list().size());
        assertFalse(store.delete("retained/shared-voice-link.ogg"));
        assertTrue(outside.exists());
    }
}
