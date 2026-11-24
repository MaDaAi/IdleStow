package io.github.madaai.idlestow.core.engine;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Single-instance coordination via an OS file lock in the data directory. Only the lock holder may
 * run the engine (index, jobs, autonomy), so two windows or background processes never optimize the
 * same files. A second launch can ask the running instance to show its window by touching a signal file.
 */
public final class InstanceLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static Optional<InstanceLock> tryAcquire(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        FileChannel ch = FileChannel.open(dataDir.resolve("idlestow.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        try {
            FileLock l = ch.tryLock();
            if (l == null) {
                ch.close();
                return Optional.empty();
            }
            return Optional.of(new InstanceLock(ch, l));
        } catch (OverlappingFileLockException e) {
            ch.close();
            return Optional.empty();
        }
    }

    /** Path a second instance touches to ask the running one to bring its window to the front. */
    public static Path activationSignal(Path dataDir) {
        return dataDir.resolve("activate.signal");
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
