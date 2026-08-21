package com.sidequestlab.floatingvoice;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Owns pending and retained OGG files for explicit Android Sharesheet handoff. */
public final class RetainedAudioShareStore {
    public record Entry(
            String id, String name, long bytes, long lastModified,
            boolean completed, boolean active) { }

    private static final String PENDING = "pending";
    private static final String RETAINED = "retained";
    private final File pendingRoot;
    private final File retainedRoot;
    private final Set<String> activeCanonicalPaths = new HashSet<>();

    public RetainedAudioShareStore(File root) {
        Objects.requireNonNull(root, "root");
        pendingRoot = new File(root, PENDING);
        retainedRoot = new File(root, RETAINED);
    }

    public synchronized File createPending(long nowMillis) {
        if (!ensureDirectory(pendingRoot)) return null;
        for (int suffix = 0; suffix < 10_000; suffix++) {
            String extra = suffix == 0 ? "" : "-" + suffix;
            File candidate = new File(pendingRoot,
                    "shared-voice-" + nowMillis + extra + ".part");
            if (candidate.exists()) continue;
            activeCanonicalPaths.add(canonicalPath(candidate));
            return candidate;
        }
        return null;
    }

    /** Publishes a completed recording into the only FileProvider-exposed directory. */
    public synchronized File promoteCompleted(File pending) {
        String activePath = canonicalPath(pending);
        if (!activeCanonicalPaths.contains(activePath) || pending == null
                || !pending.isFile() || pending.length() <= 0L
                || !isDirectChild(pendingRoot, pending) || !ensureDirectory(retainedRoot)) {
            activeCanonicalPaths.remove(activePath);
            return null;
        }
        String stem = pending.getName().endsWith(".part")
                ? pending.getName().substring(0, pending.getName().length() - 5)
                : pending.getName();
        for (int suffix = 0; suffix < 10_000; suffix++) {
            String extra = suffix == 0 ? "" : "-" + suffix;
            File target = new File(retainedRoot, stem + extra + ".ogg");
            if (target.exists()) continue;
            boolean moved = pending.renameTo(target);
            activeCanonicalPaths.remove(activePath);
            return moved ? target : null;
        }
        activeCanonicalPaths.remove(activePath);
        return null;
    }

    public synchronized void releaseActive(File pending) {
        activeCanonicalPaths.remove(canonicalPath(pending));
    }

    public synchronized List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        collect(entries, pendingRoot, false, ".part");
        collect(entries, retainedRoot, true, ".ogg");
        entries.sort(Comparator.comparingLong(Entry::lastModified).reversed()
                .thenComparing(Entry::id));
        return List.copyOf(entries);
    }

    public synchronized boolean delete(String id) {
        ParsedId parsed = parseId(id);
        if (parsed == null) return false;
        File root = parsed.completed ? retainedRoot : pendingRoot;
        File candidate = new File(root, parsed.name);
        String canonical = canonicalPath(candidate);
        if (activeCanonicalPaths.contains(canonical) || !isDirectChild(root, candidate)) return false;
        return candidate.isFile() && candidate.delete();
    }

    private void collect(List<Entry> entries, File root, boolean completed, String extension) {
        File[] files = root.listFiles(file -> file.isFile()
                && ownsName(file.getName(), extension) && isDirectChild(root, file));
        if (files == null) return;
        for (File file : files) {
            entries.add(new Entry((completed ? RETAINED : PENDING) + "/" + file.getName(),
                    file.getName(), file.length(), file.lastModified(), completed,
                    activeCanonicalPaths.contains(canonicalPath(file))));
        }
    }

    private static boolean ensureDirectory(File directory) {
        return directory.mkdirs() || directory.isDirectory();
    }

    private static boolean isDirectChild(File root, File candidate) {
        try {
            return root.getCanonicalFile().equals(candidate.getCanonicalFile().getParentFile());
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String canonicalPath(File file) {
        if (file == null) return "";
        try { return file.getCanonicalPath(); }
        catch (IOException ignored) { return file.getAbsolutePath(); }
    }

    private static boolean ownsName(String name, String extension) {
        return name != null && name.startsWith("shared-voice-") && name.endsWith(extension)
                && !name.contains("..") && !name.contains("/") && !name.contains("\\");
    }

    private static ParsedId parseId(String id) {
        if (id == null || id.contains("..") || id.contains("\\")) return null;
        int separator = id.indexOf('/');
        if (separator <= 0 || separator != id.lastIndexOf('/')) return null;
        String bucket = id.substring(0, separator);
        String name = id.substring(separator + 1);
        if (RETAINED.equals(bucket) && ownsName(name, ".ogg")) {
            return new ParsedId(name, true);
        }
        if (PENDING.equals(bucket) && ownsName(name, ".part")) {
            return new ParsedId(name, false);
        }
        return null;
    }

    private record ParsedId(String name, boolean completed) { }
}
