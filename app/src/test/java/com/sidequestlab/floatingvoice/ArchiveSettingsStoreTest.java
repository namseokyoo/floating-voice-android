package com.sidequestlab.floatingvoice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ArchiveSettingsStoreTest {
    @Test public void selectionPersistsAcrossStoreRecreationWhenPermissionRemains() {
        FakePort port = new FakePort();
        port.permission = true;
        ArchiveSettingsStore first = new ArchiveSettingsStore(port);
        first.saveSelection("content://tree/archive", "Voice archive");

        ArchiveSettingsStore.Selection selection = new ArchiveSettingsStore(port).selection();

        assertEquals(ArchiveSettingsStore.Status.READY, selection.status());
        assertEquals("content://tree/archive", selection.treeUri());
        assertEquals("Voice archive", selection.label());
    }

    @Test public void revokedPersistablePermissionRequiresReselectionWithoutFallback() {
        FakePort port = new FakePort();
        port.permission = true;
        ArchiveSettingsStore store = new ArchiveSettingsStore(port);
        store.saveSelection("content://tree/usb", "USB");
        port.permission = false;

        ArchiveSettingsStore.Selection selection = store.selection();

        assertEquals(ArchiveSettingsStore.Status.PERMISSION_LOST, selection.status());
        assertEquals("content://tree/usb", selection.treeUri());
        assertTrue(selection.requiresReselection());
    }

    @Test public void pickerCancelChangesNothing() {
        FakePort port = new FakePort();
        port.permission = true;
        ArchiveSettingsStore store = new ArchiveSettingsStore(port);
        store.saveSelection("content://tree/original", "Original");
        port.permission = true;

        store.onPickerCancelled();

        assertEquals("content://tree/original", store.selection().treeUri());
        assertEquals("Original", store.selection().label());
    }

    @Test public void noSelectionIsExplicitlyNotSelected() {
        ArchiveSettingsStore.Selection selection =
                new ArchiveSettingsStore(new FakePort()).selection();
        assertEquals(ArchiveSettingsStore.Status.NOT_SELECTED, selection.status());
        assertTrue(selection.requiresReselection());
    }

    @Test public void providerUnavailableIsDistinctFromRevokedPermission() {
        FakePort port = new FakePort();
        port.permission = true;
        port.providerAvailable = true;
        ArchiveSettingsStore store = new ArchiveSettingsStore(port);
        assertTrue(store.saveSelection("content://tree/usb", "USB"));

        port.providerAvailable = false;

        assertEquals(ArchiveSettingsStore.Status.PROVIDER_UNAVAILABLE,
                store.selection().status());
        assertEquals("content://tree/usb", store.selection().treeUri());
    }

    @Test public void failedCommitAndReadbackRestorePreviousSelection() {
        FakePort port = new FakePort();
        port.permission = true;
        ArchiveSettingsStore store = new ArchiveSettingsStore(port);
        assertTrue(store.saveSelection("content://tree/original", "Original"));
        port.failNextWriteAfterMutation = true;

        assertTrue(!store.saveSelection("content://tree/new", "New"));

        assertEquals("content://tree/original", store.selection().treeUri());
        assertEquals("Original", store.selection().label());
    }

    private static final class FakePort implements ArchiveSettingsStore.Port {
        String uri;
        String label;
        boolean permission;
        boolean providerAvailable = true;
        boolean failNextWriteAfterMutation;
        @Override public String readTreeUri() { return uri; }
        @Override public String readLabel() { return label; }
        @Override public boolean write(String treeUri, String displayLabel) {
            uri = treeUri;
            label = displayLabel;
            if (failNextWriteAfterMutation) {
                failNextWriteAfterMutation = false;
                return false;
            }
            return true;
        }
        @Override public boolean hasPersistedReadWritePermission(String treeUri) {
            return permission;
        }
        @Override public boolean providerAvailable(String treeUri) { return providerAvailable; }
    }
}
