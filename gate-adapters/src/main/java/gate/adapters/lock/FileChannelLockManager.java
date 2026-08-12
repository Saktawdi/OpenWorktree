package gate.adapters.lock;

import gate.domain.error.GateBusyException;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.LockManager;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-{@code (project, targetRef)} serial lock (架构落地执行文档 §9.2/§9.3).
 *
 * <p>Three properties are deliberate:
 * <ul>
 *   <li><b>The lock file is never deleted.</b> On Windows {@code FileLock} is an OS-level mandatory
 *       lock released automatically when the owning process dies, so there is no stale lock to
 *       reclaim — no PID checks, no heartbeats, no timeout sweeper. Deleting the file would instead
 *       create a race where two processes lock two different inodes with the same name.</li>
 *   <li><b>The in-process lock wraps the {@code FileLock}.</b> Two threads in one JVM locking the
 *       same file get {@code OverlappingFileLockException} rather than blocking, so the JVM-level
 *       {@link ReentrantLock} must be acquired first (R-LOCK order: in-process → FileLock → SQLite).</li>
 *   <li><b>Never spin.</b> {@code tryLock} returning null means busy, and busy is reported
 *       immediately as {@link GateBusyException} (exit 21) so an orchestrator can retry deliberately.</li>
 * </ul>
 *
 * <p>The {@code .owner} sidecar is diagnostic text for error messages only. No decision is ever made
 * from its contents.
 */
public final class FileChannelLockManager implements LockManager {

    private final Path locksDir;
    private final Map<String, ReentrantLock> jvmLocks = new ConcurrentHashMap<>();

    public FileChannelLockManager(Path locksDir) {
        this.locksDir = locksDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.locksDir);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot create locks dir " + this.locksDir, e);
        }
    }

    @Override
    public AutoCloseable acquire(String project, String targetRef) {
        String key = project + "|" + targetRef;
        Path lockPath = locksDir.resolve(sanitize(key) + ".lock");
        ReentrantLock jvmLock = jvmLocks.computeIfAbsent(key, k -> new ReentrantLock());

        jvmLock.lock();
        FileChannel channel = null;
        FileLock fileLock = null;
        try {
            channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            fileLock = channel.tryLock();
            if (fileLock == null) {
                throw new GateBusyException(key, readOwnerHint(lockPath));
            }
            writeOwnerHint(lockPath, key);
            return new Handle(jvmLock, channel, fileLock);
        } catch (GateBusyException e) {
            closeQuietly(channel);
            jvmLock.unlock();
            throw e;
        } catch (IOException e) {
            closeQuietly(channel);
            jvmLock.unlock();
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot acquire lock " + lockPath, e);
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

    private static void writeOwnerHint(Path lockPath, String key) {
        Path hint = Path.of(lockPath + ".owner");
        String text = "pid=" + ProcessHandle.current().pid()
                + " key=" + key
                + " at=" + Instant.now()
                + System.lineSeparator();
        try {
            Files.writeString(hint, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException ignored) {
            // Diagnostics only: failing to write a hint must never fail the operation.
        }
    }

    private static String readOwnerHint(Path lockPath) {
        try {
            Path hint = Path.of(lockPath + ".owner");
            return Files.isRegularFile(hint) ? Files.readString(hint, StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
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
