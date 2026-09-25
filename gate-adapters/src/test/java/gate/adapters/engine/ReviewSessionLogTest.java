package gate.adapters.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.domain.blob.BlobRef;
import gate.domain.review.Finding;
import gate.domain.review.Severity;
import gate.ports.store.BlobStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * {@link ReviewSessionLog} 的两条硬约束回归：
 * <ul>
 *   <li><b>并发写不丢条</b>：多组并发审查时 llmCall 从多个线程进入，条目一条都不能丢——
 *       丢掉的正是"逐请求留痕"的存在意义；</li>
 *   <li><b>跨尝试历史不丢</b>：续审复用链要求已完成组的发现跨尝试存活。此前每次 write 覆写整个
 *       文件，第二次失败会抹掉第一次成功组的发现，复用链在连续失败后断裂（复现测试见引擎
 *       BuiltinReviewEngineTest#resumeReuseSurvivesSecondFailure）。</li>
 * </ul>
 */
class ReviewSessionLogTest {

    private static final String TICKET = "T-LOG";
    private static final int ROUND = 1;

    @Test
    void concurrentAppendsDoNotLoseLines() throws Exception {
        MemBlobs blobs = new MemBlobs();
        int threads = 8;
        int perThread = 1000;
        ReviewSessionLog log = new ReviewSessionLog();
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            workers[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    log.llmCall("main", id, 1, "sys", "user", "resp", null, null, null, 1, null);
                }
            });
            workers[t].start();
        }
        for (Thread w : workers) {
            w.join();
        }
        log.write(blobs, TICKET, ROUND);

        long dataLines = readLines(blobs, TICKET, ROUND).stream()
                .filter(l -> l.contains("\"kind\":\"llm_call\"")).count();
        assertEquals(threads * perThread, dataLines, "并发写入的每条调用记录都必须落盘");
    }

    @Test
    void appendPreservesPriorAttemptsAndReaderSeesThem() {
        MemBlobs blobs = new MemBlobs();
        String fingerprint = ReviewSessionLog.sha256Hex("group-diff-content");
        List<Finding> firstAttemptFindings = List.of(new Finding(Severity.WARNING, "medium",
                "src/A.java", 10, 10, "f1", "msg", null, "code();"));

        // 尝试 1：g0 成功、终局失败
        ReviewSessionLog attempt1 = new ReviewSessionLog();
        attempt1.header(TICKET, ROUND, "tree", "p", "m");
        attempt1.groupResult(0, fingerprint, "completed", firstAttemptFindings, null);
        attempt1.terminalFailure(gate.domain.review.EngineFailure.FailureKind.TIMEOUT, "boom");
        attempt1.write(blobs, TICKET, ROUND);

        // 尝试 2：g0 复用、终局仍失败——不得抹掉尝试 1 的已完成组
        ReviewSessionLog attempt2 = new ReviewSessionLog();
        attempt2.header(TICKET, ROUND, "tree", "p", "m");
        attempt2.groupReused(0, fingerprint, firstAttemptFindings, "m");
        attempt2.terminalFailure(gate.domain.review.EngineFailure.FailureKind.UNPARSEABLE, "again");
        attempt2.write(blobs, TICKET, ROUND);

        String content = new String(blobs.store.get(logPath(TICKET, ROUND)), StandardCharsets.UTF_8);
        assertEquals(2, content.lines().filter(l -> l.contains("\"kind\":\"header\"")).count(),
                "两次尝试各有一条 header，历史共存不覆写");
        assertEquals(1, content.lines().filter(l -> l.contains("\"kind\":\"group_result\"")).count(),
                "尝试 1 的已完成组记录原样保留（复用链的来源）");

        ReviewSessionLog read = ReviewSessionLog.read(blobs, TICKET, ROUND);
        Map<String, List<Finding>> reusable = read.completedGroupsByFingerprint();
        assertTrue(reusable.containsKey(fingerprint), "复用的组在后续尝试中仍可作为复用来源");
        assertEquals(1, reusable.get(fingerprint).size());
        assertTrue(read.wasFailedAttempt(), "最后一次尝试失败 → 允许继续续审");
    }

    @Test
    void lastTerminalDecidesWhetherReuseIsAllowed() {
        MemBlobs blobs = new MemBlobs();
        String fingerprint = ReviewSessionLog.sha256Hex("d");

        ReviewSessionLog attempt1 = new ReviewSessionLog();
        attempt1.header(TICKET, ROUND, "tree", "p", "m");
        attempt1.terminalFailure(gate.domain.review.EngineFailure.FailureKind.CRASH, "first failed");
        attempt1.write(blobs, TICKET, ROUND);

        ReviewSessionLog attempt2 = new ReviewSessionLog();
        attempt2.header(TICKET, ROUND, "tree", "p", "m");
        attempt2.groupResult(0, fingerprint, "completed", List.of(), null);
        attempt2.terminalReport(0, 1, 0);
        attempt2.write(blobs, TICKET, ROUND);

        ReviewSessionLog read = ReviewSessionLog.read(blobs, TICKET, ROUND);
        assertFalse(read.wasFailedAttempt(), "最后一次尝试成功 → 不再复用（显式重审=全新审查）");
    }

    private static List<String> readLines(MemBlobs blobs, String ticket, int round) {
        String content = new String(blobs.store.get(logPath(ticket, round)), StandardCharsets.UTF_8);
        return content.lines().filter(l -> !l.isBlank()).toList();
    }

    private static String logPath(String ticket, int round) {
        return String.format(ReviewSessionLog.LOG_REL_PATH_FMT, ticket, round);
    }

    private static final class MemBlobs implements BlobStore {
        final Map<String, byte[]> store = new ConcurrentHashMap<>();

        @Override
        public BlobRef put(byte[] data, String relPath) {
            store.put(relPath, data.clone());
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest(data)) {
                    sb.append(String.format("%02x", b));
                }
                return new BlobRef(relPath, data.length, sb.toString());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public byte[] get(BlobRef ref) {
            byte[] d = store.get(ref.relPath());
            if (d == null) {
                throw new IllegalArgumentException("no blob at " + ref.relPath());
            }
            return d;
        }
    }
}
