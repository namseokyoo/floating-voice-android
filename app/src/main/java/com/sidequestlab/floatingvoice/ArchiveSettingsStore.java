package com.sidequestlab.floatingvoice;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;

import java.util.Objects;

/** Persisted SAF archive folder plus live persistable-permission validation. */
public final class ArchiveSettingsStore {
    private static final String PREFS = "local_archive_settings";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_LABEL = "tree_label";

    public enum Status { NOT_SELECTED, READY, PERMISSION_LOST, PROVIDER_UNAVAILABLE }

    public record Selection(Status status, String treeUri, String label) {
        public boolean requiresReselection() { return status != Status.READY; }
    }

    public interface Port {
        String readTreeUri();
        String readLabel();
        boolean write(String treeUri, String displayLabel);
        boolean hasPersistedReadWritePermission(String treeUri);
        boolean providerAvailable(String treeUri);
    }

    private final Port port;

    public ArchiveSettingsStore(Context context) {
        this(new AndroidPort(Objects.requireNonNull(context).getApplicationContext()));
    }

    ArchiveSettingsStore(Port port) { this.port = Objects.requireNonNull(port); }

    public Selection selection() {
        String uri = port.readTreeUri();
        String label = port.readLabel();
        if (uri == null || uri.isBlank()) {
            return new Selection(Status.NOT_SELECTED, null, null);
        }
        Status status = !port.hasPersistedReadWritePermission(uri)
                ? Status.PERMISSION_LOST
                : port.providerAvailable(uri) ? Status.READY : Status.PROVIDER_UNAVAILABLE;
        return new Selection(status, uri,
                label == null || label.isBlank() ? uri : label);
    }

    public boolean saveSelection(String treeUri, String displayLabel) {
        if (treeUri == null || treeUri.isBlank()) {
            throw new IllegalArgumentException("treeUri is required");
        }
        String previousUri = port.readTreeUri();
        String previousLabel = port.readLabel();
        boolean saved = port.write(treeUri, displayLabel == null ? treeUri : displayLabel)
                && treeUri.equals(port.readTreeUri())
                && port.hasPersistedReadWritePermission(treeUri)
                && port.providerAvailable(treeUri);
        if (!saved) port.write(previousUri, previousLabel);
        return saved;
    }

    /** Deliberately a no-op: picker cancellation must preserve the prior selection. */
    public void onPickerCancelled() { }

    private static final class AndroidPort implements Port {
        private final Context context;
        private final SharedPreferences preferences;

        AndroidPort(Context context) {
            this.context = context;
            preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        @Override public String readTreeUri() { return preferences.getString(KEY_TREE_URI, null); }
        @Override public String readLabel() { return preferences.getString(KEY_LABEL, null); }
        @Override public boolean write(String treeUri, String displayLabel) {
            return preferences.edit().putString(KEY_TREE_URI, treeUri)
                    .putString(KEY_LABEL, displayLabel).commit();
        }
        @Override public boolean hasPersistedReadWritePermission(String treeUri) {
            Uri expected = Uri.parse(treeUri);
            for (UriPermission permission : context.getContentResolver()
                    .getPersistedUriPermissions()) {
                if (expected.equals(permission.getUri())
                        && permission.isReadPermission() && permission.isWritePermission()) {
                    return true;
                }
            }
            return false;
        }
        @Override public boolean providerAvailable(String treeUri) {
            Uri tree = Uri.parse(treeUri);
            Uri document = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    tree, android.provider.DocumentsContract.getTreeDocumentId(tree));
            try (android.database.Cursor cursor = context.getContentResolver().query(
                    document, new String[] {
                            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                    null, null, null)) {
                return cursor != null && cursor.moveToFirst();
            } catch (RuntimeException unavailable) {
                return false;
            }
        }
    }
}
