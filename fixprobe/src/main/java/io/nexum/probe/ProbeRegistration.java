package io.nexum.probe;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A running probe's presence on this machine, as a file another process can
 * read.
 *
 * <p>A probe tests whatever FIX engine it was pointed at, which is why nothing
 * here asks the system under test about it: a probe that never connected, or
 * one whose counterparty is somebody else's engine entirely, is as much a
 * running probe as any other and has to be discoverable on the same terms.
 *
 * <p>The directory is the contract. A registration is a file named for the
 * port, so two probes cannot claim one entry and a reader needs no index:
 * whatever files are there are the probes that got as far as writing one.
 *
 * <p>A file outliving its process is the expected failure, not an exceptional
 * one — a probe killed outright runs no shutdown hook. Every record therefore
 * carries the pid that wrote it, and {@link #live(Path)} drops the entries
 * whose process is gone rather than trusting the directory.
 */
public final class ProbeRegistration implements AutoCloseable {

    /** Where registrations live, under the user's home so no two users collide. */
    public static Path defaultDirectory() {
        return Path.of(System.getProperty("user.home"), ".nexum", "probes");
    }

    /** One probe, as a reader sees it. */
    public record Record(int port, long pid, long startedAt) {}

    private final Path file;

    /**
     * Announce a probe on a port, replacing any stale file for it.
     *
     * @param directory where registrations live
     * @param port the port this probe's HTTP endpoint is bound to
     */
    public ProbeRegistration(Path directory, int port) {
        this.file = directory.resolve(port + ".json");
        long pid = ProcessHandle.current().pid();
        long startedAt = System.currentTimeMillis();
        try {
            Files.createDirectories(directory);
            // Written whole and moved into place: a reader that catches the
            // file mid-write would see a truncated object and call it a probe
            // that failed to start, which is a different thing to report.
            Path partial = directory.resolve(port + ".json.partial");
            Files.writeString(partial, """
                    {"port":%d,"pid":%d,"startedAt":%d}
                    """.formatted(port, pid, startedAt), StandardCharsets.UTF_8);
            Files.move(partial, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "cannot register the probe at " + file, failure);
        }
    }

    /**
     * The probes registered here whose process is still running.
     *
     * <p>An unreadable or malformed file is skipped rather than failing the
     * listing: one probe that wrote a bad record should not hide the others.
     */
    public static List<Record> live(Path directory) {
        List<Record> records = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return records;
        }
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (!name.endsWith(".json")) {
                    continue;
                }
                Record record = read(entry);
                if (record == null) {
                    continue;
                }
                if (ProcessHandle.of(record.pid()).filter(ProcessHandle::isAlive).isEmpty()) {
                    // The process is gone and its file is not evidence of a
                    // probe; leaving it would have every later listing report
                    // one that cannot be reached.
                    try {
                        Files.deleteIfExists(entry);
                    } catch (IOException notOurs) {
                        // Another reader got there first, or it is not ours to
                        // remove. Either way it is not in the listing.
                    }
                    continue;
                }
                records.add(record);
            }
        } catch (IOException unreadable) {
            return records;
        }
        records.sort((left, right) -> Integer.compare(left.port(), right.port()));
        return records;
    }

    private static Record read(Path entry) {
        try {
            String text = Files.readString(entry, StandardCharsets.UTF_8);
            return new Record(
                    (int) number(text, "port"),
                    number(text, "pid"),
                    number(text, "startedAt"));
        } catch (IOException | IllegalArgumentException malformed) {
            return null;
        }
    }

    /** The one number this file format has, rather than a parser for a format with one shape. */
    private static long number(String text, String field) {
        String needle = '"' + field + "\":";
        int at = text.indexOf(needle);
        if (at < 0) {
            throw new IllegalArgumentException("no " + field + " in " + text);
        }
        int from = at + needle.length();
        int to = from;
        while (to < text.length() && (Character.isDigit(text.charAt(to)) || text.charAt(to) == '-')) {
            to++;
        }
        return Long.parseLong(text.substring(from, to));
    }

    /** Withdraw this probe's registration. */
    @Override
    public void close() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            // Exiting is not the moment to fail over a file a later listing
            // will drop anyway once it sees the pid is gone.
        }
    }
}
