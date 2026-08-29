package io.nexum.order;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The journal split across dated segments, plus the checkpoint that says how far
 * back replay has to go.
 *
 * <p>A single growing file is fine until it is not: at a hundred thousand orders
 * a day it reaches tens of gigabytes within a year, and every restart reads all
 * of it to rebuild a handful of live orders. Segments bound both — the day's
 * writes go to today's file, and replay starts at the oldest segment still
 * holding an order that has not reached a terminal state.
 *
 * <pre>
 *   orders/
 *     orders-2026-08-22.journal    older than the checkpoint: archivable
 *     orders-2026-08-23.journal    checkpoint points here — replay starts
 *     orders-2026-08-24.journal    today; being written
 *     checkpoint                   oldest segment with unfinished orders
 * </pre>
 */
public final class JournalSegments {

    private static final String PREFIX = "orders-";
    private static final String SUFFIX = ".journal";
    private static final String CHECKPOINT = "checkpoint";

    private final Path directory;

    public JournalSegments(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot create journal directory " + directory, failure);
        }
    }

    public Path directory() {
        return directory;
    }

    /** The segment a write on this date belongs in. */
    public Path segmentFor(LocalDate date) {
        return directory.resolve(PREFIX + date + SUFFIX);
    }

    public Path currentSegment() {
        return segmentFor(LocalDate.now(ZoneOffset.UTC));
    }

    /** Every segment present, oldest first. */
    public List<Path> allSegments() {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(JournalSegments::isSegment)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot list journal segments", failure);
        }
    }

    /**
     * Segments replay must read: the checkpoint and everything after it.
     *
     * <p>Without a checkpoint every segment is read, which is correct but slow —
     * and correct-but-slow is the right default when the checkpoint is missing
     * or unreadable.
     */
    public List<Path> segmentsToReplay() {
        List<Path> all = allSegments();
        LocalDate checkpoint = readCheckpoint();
        if (checkpoint == null) {
            return all;
        }
        String oldest = PREFIX + checkpoint + SUFFIX;
        List<Path> needed = new ArrayList<>();
        for (Path segment : all) {
            if (segment.getFileName().toString().compareTo(oldest) >= 0) {
                needed.add(segment);
            }
        }
        return needed;
    }

    /**
     * Record the oldest date still holding unfinished work.
     *
     * <p>Written after a successful replay, not during: a checkpoint advanced
     * ahead of the orders it claims to cover would silently drop them on the
     * next restart.
     */
    public void writeCheckpoint(LocalDate oldestUnfinished) {
        try {
            Files.writeString(
                    directory.resolve(CHECKPOINT),
                    oldestUnfinished.toString(),
                    StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot write checkpoint", failure);
        }
    }

    public LocalDate readCheckpoint() {
        Path path = directory.resolve(CHECKPOINT);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return LocalDate.parse(Files.readString(path, StandardCharsets.UTF_8).trim());
        } catch (IOException | RuntimeException unreadable) {
            // An unreadable checkpoint means replaying more than necessary,
            // which is slower and still correct.
            return null;
        }
    }

    /**
     * Segments entirely before the checkpoint. Nothing in them is needed to
     * rebuild state, so they can be compressed or moved to cold storage — but
     * they are the audit record of orders that existed, so this reports them
     * rather than deleting them.
     */
    public List<Path> archivableSegments() {
        LocalDate checkpoint = readCheckpoint();
        if (checkpoint == null) {
            return List.of();
        }
        String oldest = PREFIX + checkpoint + SUFFIX;
        List<Path> archivable = new ArrayList<>();
        for (Path segment : allSegments()) {
            if (segment.getFileName().toString().compareTo(oldest) < 0) {
                archivable.add(segment);
            }
        }
        return archivable;
    }

    /** The date a segment holds, or null when the name does not encode one. */
    public static LocalDate dateOf(Path segment) {
        String name = segment.getFileName().toString();
        if (!isSegment(segment)) {
            return null;
        }
        try {
            return LocalDate.parse(
                    name.substring(PREFIX.length(), name.length() - SUFFIX.length()));
        } catch (RuntimeException notADate) {
            return null;
        }
    }

    private static boolean isSegment(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(PREFIX) && name.endsWith(SUFFIX);
    }
}
