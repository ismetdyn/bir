package io.github.ismetdyn.agentkb.server.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * A file git opens replaced by a FIFO no one writes, so every git that opens it blocks until
 * released: {@code .git/config} holds every git command, {@code .git/info/exclude} only those
 * reading the exclude rules ({@code ls-files --exclude-standard}), not {@code rev-parse HEAD}.
 */
final class GitFifo {

    private GitFifo() {}

    /** {@code file}, created or replaced, as a FIFO with no writer. */
    static Path make(Path file) throws IOException, InterruptedException {
        Files.createDirectories(file.getParent());
        Files.deleteIfExists(file);
        Process mkfifo = new ProcessBuilder("mkfifo", file.toString()).start();
        assertThat(mkfifo.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(mkfifo.exitValue()).as("mkfifo").isZero();
        return file;
    }

    /**
     * Opening the FIFO read-write never blocks on Linux and lets every git blocked opening it go
     * on; removing it before the close hands those end of file and lets a later git find no file
     * rather than block with no writer left.
     */
    static void release(Path fifo) throws IOException {
        try (RandomAccessFile both = new RandomAccessFile(fifo.toFile(), "rw")) {
            Files.delete(fifo);
            assertThat(both.getFD().valid()).isTrue();
        }
    }
}
