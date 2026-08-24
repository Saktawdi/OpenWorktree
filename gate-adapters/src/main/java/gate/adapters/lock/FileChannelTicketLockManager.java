package gate.adapters.lock;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.infra.TicketLockManager;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Ticket-level serial lock (执行文档-后端-web §7.2/§7.4).
 *
 * <p>Mirrors {@link FileChannelLockManager}: in-process {@link ReentrantLock} first, then a
 * cross-process {@code FileLock} on a per-ticket lock file. {@code tryAcquire} is used by presubmit
 * to fail fast with 422 when a session is touching the clone.
 */
public final class FileChannelTicketLockManager implements TicketLockManager {

    private final Path locksDir;
    private final Map<String, ReentrantLock> jvmLocks = new ConcurrentHashMap<>();

    public FileChannelTicketLockManager(Path locksDir) {
        this.locksDir = locksDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.locksDir);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot create ticket locks dir " + this.locksDir, e);
        }
    }

    @Override
    public AutoCloseable acquire(String ticketNo) {
        return acquireInternal(ticketNo, false)
                .orElseThrow(() -> new IllegalStateException("unreachable: blocking acquire returned empty"));
    }

    @Override
    public Optional<AutoCloseable> tryAcquire(String ticketNo) {
        return acquireInternal(ticketNo, true);
    }

    private Optional<AutoCloseable> acquireInternal(String ticketNo, boolean tryOnly) {
        Path lockPath = locksDir.resolve(sanitize(ticketNo) + ".lock");
        ReentrantLock jvmLock = jvmLocks.computeIfAbsent(ticketNo, k -> new ReentrantLock());
        if (tryOnly) {
            if (!jvmLock.tryLock()) {
                return Optional.empty();
            }
        } else {
            jvmLock.lock();
        }
        FileChannel channel = null;
        FileLock fileLock = null;
        try {
            channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            fileLock = channel.tryLock();
            if (fileLock == null) {
                closeQuietly(channel);
                if (tryOnly) {
                    jvmLock.unlock();
                    return Optional.empty();
                }
                throw new GateException(GateErrorCode.GATE_ERROR_IO,
                        "ticket lock is busy: " + ticketNo);
            }
            writeOwnerHint(lockPath, ticketNo);
            return Optional.of(new Handle(jvmLock, channel, fileLock));
        } catch (IOException e) {
            closeQuietly(channel);
            jvmLock.unlock();
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot acquire ticket lock " + lockPath, e);
        } catch (RuntimeException e) {
            releaseQuietly(fileLock);
            closeQuietly(channel);
            jvmLock.unlock();
            throw e;
        }
    }

    private static String sanitize(String key) {
        return key.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void writeOwnerHint(Path lockPath, String ticketNo) {
        Path hint = Path.of(lockPath + ".owner");
        try {
            Files.writeString(hint, "pid=" + ProcessHandle.current().pid()
                            + " ticket=" + ticketNo
                            + " at=" + Instant.now() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException ignored) {
            // diagnostics only
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // nothing actionable
            }
        }
    }

    private static void releaseQuietly(FileLock lock) {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException ignored) {
                // nothing actionable
            }
        }
    }

    private static final class Handle implements AutoCloseable {

        private final ReentrantLock jvmLock;
        private final FileChannel channel;
        private final FileLock fileLock;

        private Handle(ReentrantLock jvmLock, FileChannel channel, FileLock fileLock) {
            this.jvmLock = jvmLock;
            this.channel = channel;
            this.fileLock = fileLock;
        }

        @Override
        public void close() {
            try {
                releaseQuietly(fileLock);
                closeQuietly(channel);
            } finally {
                jvmLock.unlock();
            }
        }
    }
}
