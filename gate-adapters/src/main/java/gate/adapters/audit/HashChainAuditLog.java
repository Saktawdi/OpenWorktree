package gate.adapters.audit;

import gate.domain.audit.AuditEvent;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.store.AuditLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hash-chained, append-only JSONL audit log (架构落地执行文档 §10.2).
 *
 * <p>Each line carries {@code prev_hash} and {@code hash}, where
 * {@code hash = sha256(prev_hash + canonical_payload)}. A same-privilege agent can still edit the
 * file — §1.3 concedes that outright — but it cannot do so without breaking the chain, which turns
 * tampering and truncation into <em>detectable</em> events. Detection, not prevention, is the claim.
 *
 * <p>The application never rotates this file.
 */
public final class HashChainAuditLog implements AuditLog {

    private static final String GENESIS = "0".repeat(64);

    private final Path path;

    public HashChainAuditLog(Path path) {
        this.path = path.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.path.getParent());
            if (!Files.exists(this.path)) {
                Files.write(this.path, new byte[0], StandardOpenOption.CREATE);
            }
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot create audit log " + this.path, e);
        }
    }

    @Override
    public synchronized void append(AuditEvent event) {
        String prev = lastHash();
        String payload = canonicalPayload(event);
        String hash = sha256(prev + payload);
        String line = "{\"prev_hash\":\"" + prev + "\",\"hash\":\"" + hash + "\"," + payload.substring(1) + "\n";
        try {
            Files.write(path, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot append to audit log " + path, e);
        }
    }

    /** Verifies chain continuity from genesis. Used by tests and by {@code gate audit verify}. */
    public boolean verifyChain() {
        return verifyChainReport().ok();
    }

    /** Result of {@link #verifyChainReport()}. */
    public record ChainReport(boolean ok, int totalLines, int brokenAtLine) {
        public static final ChainReport EMPTY = new ChainReport(true, 0, -1);
    }

    /**
     * Chain verification with a precise first-break position, for the evidence UI: {@code
     * brokenAtLine} is the 1-based line number whose {@code prev_hash} first fails to match the
     * previous record's hash (or whose own hash fails to recompute); {@code -1} when the whole
     * chain is intact. Detection, not prevention — the report only locates the break.
     */
    public ChainReport verifyChainReport() {
        List<String> lines = readLines();
        String expectedPrev = GENESIS;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String prev = extract(line, "prev_hash");
            String hash = extract(line, "hash");
            boolean broken = !expectedPrev.equals(prev);
            if (!broken) {
                int payloadStart = line.indexOf("\"at\":");
                if (payloadStart < 0) {
                    broken = true;
                } else {
                    String payload = "{" + line.substring(payloadStart);
                    broken = !sha256(prev + payload).equals(hash);
                }
            }
            if (broken) {
                return new ChainReport(false, lines.size(), i + 1);
            }
            expectedPrev = hash;
        }
        return new ChainReport(true, lines.size(), -1);
    }

    public List<String> readLines() {
        try {
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    out.add(line);
                }
            }
            return out;
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot read audit log " + path, e);
        }
    }

    private String lastHash() {
        List<String> lines = readLines();
        if (lines.isEmpty()) {
            return GENESIS;
        }
        return extract(lines.get(lines.size() - 1), "hash");
    }

    private static String extract(String line, String key) {
        String needle = "\"" + key + "\":\"";
        int start = line.indexOf(needle);
        if (start < 0) {
            return "";
        }
        int from = start + needle.length();
        int end = line.indexOf('"', from);
        return end < 0 ? "" : line.substring(from, end);
    }

    /** Deterministic JSON: keys sorted, so the digest does not depend on map iteration order. */
    private static String canonicalPayload(AuditEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"at\":\"").append(event.at().toString()).append('"');
        sb.append(",\"kind\":\"").append(escape(event.kind())).append('"');
        sb.append(",\"ticket_no\":").append(event.ticketNo() == null ? "null" : "\"" + escape(event.ticketNo()) + "\"");
        sb.append(",\"review_round\":").append(event.reviewRound() == null ? "null" : event.reviewRound());
        sb.append(",\"fields\":{");
        Map<String, String> sorted = new TreeMap<>(event.fields());
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":\"")
                    .append(escape(e.getValue() == null ? "" : e.getValue())).append('"');
        }
        sb.append("}}");
        return sb.toString();
    }

    private static String escape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new GateException(GateErrorCode.INTERNAL, "SHA-256 unavailable", e);
        }
    }

    public Path path() {
        return path;
    }
}
