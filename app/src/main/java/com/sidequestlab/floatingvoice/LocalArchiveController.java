package com.sidequestlab.floatingvoice;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/** Loss-averse copy/read-back/delete transaction for a temporary OGG recording. */
public final class LocalArchiveController {
    public enum Outcome {
        VERIFIED_SOURCE_DELETED,
        VERIFIED_SOURCE_RETAINED,
        COPIED_UNVERIFIED_SOURCE_RETAINED,
        FAILED_SOURCE_RETAINED
    }

    public record Result(Outcome outcome, String targetName, String targetId, String detail,
                         boolean partialTargetRetained) {
        public boolean verified() {
            return outcome == Outcome.VERIFIED_SOURCE_DELETED
                    || outcome == Outcome.VERIFIED_SOURCE_RETAINED;
        }
    }

    public interface StoragePort {
        boolean sourceExists();
        long sourceSize();
        String sourceName();
        InputStream openSource() throws IOException;
        boolean targetExists(String displayName) throws IOException;
        String createTarget(String displayName) throws IOException;
        OutputStream openTargetOutput(String targetId) throws IOException;
        InputStream openTargetInput(String targetId) throws IOException;
        boolean deleteTarget(String targetId);
        boolean deleteSource();
    }

    public static final class ReadbackUnsupportedException extends IOException {
        public ReadbackUnsupportedException(String message) { super(message); }
        public ReadbackUnsupportedException(String message, Throwable cause) { super(message, cause); }
    }

    public Result archive(StoragePort storage) {
        Objects.requireNonNull(storage, "storage");
        String targetId = null;
        String targetName = null;
        try {
            long expectedSize = storage.sourceSize();
            if (!storage.sourceExists() || expectedSize <= 0L) {
                return failed(null, null, "Source OGG is missing or empty");
            }
            targetName = uniqueName(storage, storage.sourceName());
            targetId = storage.createTarget(targetName);
            if (targetId == null || targetId.isBlank()) {
                return failed(targetName, null, "Provider did not create a target document");
            }

            MessageDigest sourceDigest = sha256();
            long copied;
            try (InputStream input = storage.openSource();
                 OutputStream output = storage.openTargetOutput(targetId)) {
                copied = copy(input, output, sourceDigest);
            }
            if (copied != expectedSize) {
                boolean retained = !cleanup(storage, targetId);
                return failed(targetName, targetId,
                        "Copied byte count differs from source size", retained);
            }

            MessageDigest targetDigest = sha256();
            long readBack;
            try (InputStream input = storage.openTargetInput(targetId)) {
                readBack = digest(input, targetDigest);
            } catch (ReadbackUnsupportedException unsupported) {
                return new Result(Outcome.COPIED_UNVERIFIED_SOURCE_RETAINED,
                        targetName, targetId, unsupported.getMessage(), false);
            }
            if (readBack != expectedSize
                    || !Arrays.equals(sourceDigest.digest(), targetDigest.digest())) {
                boolean retained = !cleanup(storage, targetId);
                return failed(targetName, targetId,
                        "Target read-back verification failed", retained);
            }

            if (storage.deleteSource()) {
                return new Result(Outcome.VERIFIED_SOURCE_DELETED,
                        targetName, targetId, "Verified; temporary source deleted", false);
            }
            return new Result(Outcome.VERIFIED_SOURCE_RETAINED,
                    targetName, targetId, "Verified; temporary source could not be deleted", false);
        } catch (IOException | RuntimeException failure) {
            boolean retained = targetId != null && !cleanup(storage, targetId);
            return failed(targetName, targetId,
                    failure.getMessage() == null ? failure.getClass().getSimpleName()
                            : failure.getMessage(), retained);
        }
    }

    private static Result failed(String name, String id, String detail) {
        return failed(name, id, detail, false);
    }

    private static Result failed(String name, String id, String detail, boolean retained) {
        return new Result(Outcome.FAILED_SOURCE_RETAINED, name, id, detail, retained);
    }

    private static boolean cleanup(StoragePort storage, String targetId) {
        try { return storage.deleteTarget(targetId); }
        catch (RuntimeException ignored) { return false; }
    }

    private static String uniqueName(StoragePort storage, String requested) throws IOException {
        String safe = requested == null ? "" : requested
                .replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
        if (safe.isBlank()) safe = "voice.ogg";
        int dot = safe.lastIndexOf('.');
        String extension = dot > 0 ? safe.substring(dot) : "";
        String base = dot > 0 ? safe.substring(0, dot) : safe;
        int maxBaseLength = Math.max(1, 120 - extension.length());
        if (base.length() > maxBaseLength) base = base.substring(0, maxBaseLength);
        String candidate = base + extension;
        for (int duplicate = 2; storage.targetExists(candidate); duplicate++) {
            if (duplicate > 10_000) throw new IOException("Too many duplicate filenames");
            String suffix = " (" + duplicate + ")";
            int duplicateBaseLength = Math.max(1, 120 - extension.length() - suffix.length());
            candidate = base.substring(0, Math.min(base.length(), duplicateBaseLength))
                    + suffix + extension;
        }
        return candidate;
    }

    private static long copy(InputStream input, OutputStream output, MessageDigest digest)
            throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long count = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) continue;
            output.write(buffer, 0, read);
            digest.update(buffer, 0, read);
            count += read;
        }
        output.flush();
        return count;
    }

    private static long digest(InputStream input, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long count = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) continue;
            digest.update(buffer, 0, read);
            count += read;
        }
        return count;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    /** Android SAF adapter. The controller itself remains deterministic and JVM-testable. */
    public static final class SafStoragePort implements StoragePort {
        private final ContentResolver resolver;
        private final Uri treeUri;
        private final File source;

        public SafStoragePort(ContentResolver resolver, Uri treeUri, File source) {
            this.resolver = Objects.requireNonNull(resolver);
            this.treeUri = Objects.requireNonNull(treeUri);
            this.source = Objects.requireNonNull(source);
        }

        @Override public boolean sourceExists() { return source.isFile(); }
        @Override public long sourceSize() { return source.length(); }
        @Override public String sourceName() { return source.getName(); }
        @Override public InputStream openSource() throws IOException {
            return new FileInputStream(source);
        }
        @Override public boolean targetExists(String displayName) throws IOException {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            try (Cursor cursor = resolver.query(children,
                    new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (cursor == null) return false;
                while (cursor.moveToNext()) {
                    if (displayName.equals(cursor.getString(0))) return true;
                }
                return false;
            } catch (RuntimeException failure) {
                throw new IOException("Cannot list archive folder", failure);
            }
        }
        @Override public String createTarget(String displayName) throws IOException {
            try {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, DocumentsContract.getTreeDocumentId(treeUri));
                Uri created = DocumentsContract.createDocument(resolver, parent,
                        "audio/ogg", displayName);
                return created == null ? null : created.toString();
            } catch (FileNotFoundException | RuntimeException failure) {
                throw new IOException("Cannot create archive document", failure);
            }
        }
        @Override public OutputStream openTargetOutput(String targetId) throws IOException {
            OutputStream output = resolver.openOutputStream(Uri.parse(targetId), "w");
            if (output == null) throw new IOException("Provider returned no output stream");
            return output;
        }
        @Override public InputStream openTargetInput(String targetId) throws IOException {
            try {
                InputStream input = resolver.openInputStream(Uri.parse(targetId));
                if (input == null) {
                    throw new ReadbackUnsupportedException("Provider does not support read-back");
                }
                return input;
            } catch (UnsupportedOperationException unsupported) {
                throw new ReadbackUnsupportedException(
                        "Provider does not support read-back", unsupported);
            }
        }
        @Override public boolean deleteTarget(String targetId) {
            try { return DocumentsContract.deleteDocument(resolver, Uri.parse(targetId)); }
            catch (Exception ignored) { return false; }
        }
        @Override public boolean deleteSource() { return source.delete(); }
    }
}
