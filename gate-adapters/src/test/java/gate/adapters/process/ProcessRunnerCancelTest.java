package gate.adapters.process;

import static org.junit.jupiter.api.Assertions.*;

import gate.ports.infra.ProcessRunner.ProcRun;
import gate.ports.infra.ProcessRunner.StreamSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * T-120：取消信号必须真的终止进程，而不是"等它自己跑完"。
 *
 * <p>这里刻意用真实子进程（本机 JVM 跑一个长睡的程序，先打印自己的 pid）而不是桩：
 * 要验证的正是 {@code ProcessRunnerImpl} 的等待循环会不会在收到信号后杀掉进程树并立刻返回。
 */
class ProcessRunnerCancelTest {

    // ---- 取消：立即返回 + 进程真的死掉 + 不算超时 ----
    @Test
    void cancel_kills_the_child_process_instead_of_waiting_for_the_timeout() throws Exception {
        Path root = Files.createTempDirectory("gate-proc-cancel-");
        try {
            ProcessRunnerImpl runner = new ProcessRunnerImpl(root.resolve("proc"));
            AtomicBoolean cancel = new AtomicBoolean();
            AtomicLong childPid = new AtomicLong();
            CompletableFuture<ProcRun> future = CompletableFuture.supplyAsync(() -> runner.runStreaming(
                    sleepyChildArgv(), root, Map.of(), Duration.ofMinutes(5),
                    new StreamSpec(null, cancel::get),
                    line -> {
                        if (line.startsWith("pid=")) {
                            childPid.set(Long.parseLong(line.substring("pid=".length()).trim()));
                        }
                    },
                    null));

            awaitTrue(() -> childPid.get() != 0, 20, "子进程未在预期内报告 pid");
            assertTrue(isAlive(childPid.get()), "子进程本应在运行中");

            long startedAt = System.nanoTime();
            cancel.set(true);
            ProcRun run = future.get(20, TimeUnit.SECONDS);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            assertFalse(run.timedOut(), "取消不是超时：timedOut 必须为 false");
            assertTrue(elapsedMs < 5_000, "收到取消信号后应立即返回，实际耗时 " + elapsedMs + "ms");
            awaitTrue(() -> !isAlive(childPid.get()), 5, "被取消的进程必须真的被杀掉，pid=" + childPid.get());
        } finally {
            gate.adapters.io.FsUtil.deleteRecursively(root);
        }
    }

    // ---- 对照：超时路径不受改造影响 ----
    @Test
    void timeout_still_reports_timed_out_and_reaps_the_child() throws Exception {
        Path root = Files.createTempDirectory("gate-proc-timeout-");
        try {
            ProcessRunnerImpl runner = new ProcessRunnerImpl(root.resolve("proc"));
            AtomicLong childPid = new AtomicLong();
            AtomicBoolean cancel = new AtomicBoolean();

            long startedAt = System.nanoTime();
            ProcRun run = runner.runStreaming(sleepyChildArgv(), root, Map.of(), Duration.ofSeconds(1),
                    new StreamSpec(null, cancel::get),
                    line -> {
                        if (line.startsWith("pid=")) {
                            childPid.set(Long.parseLong(line.substring("pid=".length()).trim()));
                        }
                    },
                    null);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            assertTrue(run.timedOut(), "超过 timeout 的运行必须报 timedOut");
            assertTrue(elapsedMs < 10_000, "超时后不应继续等下去，实际耗时 " + elapsedMs + "ms");
            assertTrue(childPid.get() != 0, "子进程应已报告过 pid");
            awaitTrue(() -> !isAlive(childPid.get()), 5, "超时被打死的进程必须真的消失");
        } finally {
            gate.adapters.io.FsUtil.deleteRecursively(root);
        }
    }

    /** 用当前 JVM 自己去跑 {@link SleepyChild}，避免依赖任何平台特有的长睡命令。 */
    private static List<String> sleepyChildArgv() {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        return List.of(javaBin, "-cp", System.getProperty("java.class.path"), SleepyChild.class.getName());
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void awaitTrue(BooleanSupplier condition, int secs, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(secs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail(message);
    }

    /** 长睡的子进程：先亮出自己的 pid，好让父进程核对它是否真的被杀掉。 */
    public static final class SleepyChild {

        public static void main(String[] args) throws Exception {
            System.out.println("pid=" + ProcessHandle.current().pid());
            System.out.flush();
            Thread.sleep(TimeUnit.MINUTES.toMillis(5));
        }
    }
}
