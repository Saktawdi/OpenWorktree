package gate.adapters.hook;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.git.RepoRef;
import gate.ports.git.HookInstaller;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Generates and installs {@code hooks/pre-receive} from the bundled template
 * (架构落地执行文档 §6.3, N2).
 *
 * <p>Write mechanics are part of the contract, not an implementation detail:
 * <ul>
 *   <li>the template is normalised to <b>LF</b> and written with {@link Files#write} on raw UTF-8
 *       bytes — anything that writes CRLF or a BOM produces a hook {@code sh} refuses to run;</li>
 *   <li>a dead hook and a healthy hook are externally indistinguishable — both reject every push —
 *       so this class exists to make the bytes deterministic and the digest checkable, and the
 *       preflight self-check exists to prove the script actually runs (spike-结论 §2.4).</li>
 * </ul>
 *
 * <p>The approvals directory and the ref whitelist are baked in at generation time. The hook
 * therefore reads no configuration at run time, so there is nothing agent-controllable for it to
 * consult (§6.3).
 */
public final class FileHookInstaller implements HookInstaller {

    private static final String TEMPLATE_RESOURCE = "/gate/hooks/pre-receive.tmpl";

    @Override
    public String install(RepoRef authRepo, List<String> targetRefWhitelist, Path approvalsDir) {
        String body = render(targetRefWhitelist, approvalsDir);
        Path hooksDir = authRepo.path().resolve("hooks");
        Path hookPath = hooksDir.resolve("pre-receive");
        try {
            Files.createDirectories(hooksDir);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            assertLfNoBom(bytes);
            Files.write(hookPath, bytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            makeExecutable(hookPath);
            return sha256(bytes);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG, "cannot install hook at " + hookPath, e);
        }
    }

    @Override
    public String render(List<String> targetRefWhitelist, Path approvalsDir) {
        if (targetRefWhitelist == null || targetRefWhitelist.isEmpty()) {
            throw new IllegalArgumentException("target ref whitelist must not be empty");
        }
        String template = loadTemplate();
        String approvals = toShellPath(approvalsDir);
        if (approvals.contains("'")) {
            // The path is embedded in a single-quoted sh literal; a quote would break out of it.
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "approvals path must not contain a single quote: " + approvals);
        }
        String caseArms = String.join("|", targetRefWhitelist);
        for (String ref : targetRefWhitelist) {
            if (!ref.matches("refs/heads/[A-Za-z0-9._/-]+")) {
                throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                        "target ref is not safe to embed in the hook: " + ref);
            }
        }
        return template
                .replace("@APPROVALS@", approvals)
                .replace("@TARGET_REF@", caseArms)
                .replace("\r\n", "\n");
    }

    @Override
    public String installedDigest(RepoRef authRepo) {
        Path hookPath = authRepo.path().resolve("hooks").resolve("pre-receive");
        try {
            if (!Files.isRegularFile(hookPath)) {
                return "";
            }
            return sha256(Files.readAllBytes(hookPath));
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG, "cannot read hook at " + hookPath, e);
        }
    }

    @Override
    public String expectedDigest(List<String> targetRefWhitelist, Path approvalsDir) {
        return sha256(render(targetRefWhitelist, approvalsDir).getBytes(StandardCharsets.UTF_8));
    }

    private String loadTemplate() {
        try (InputStream in = FileHookInstaller.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new GateException(GateErrorCode.INTERNAL, "hook template missing: " + TEMPLATE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GateException(GateErrorCode.INTERNAL, "cannot read hook template", e);
        }
    }

    /**
     * Windows paths reach a {@code sh} script running under git's bundled MSYS shell. Backslashes
     * would be interpreted as escapes, so they are converted to forward slashes, which MSYS accepts
     * for drive-letter paths ({@code C:/Users/...}).
     */
    private static String toShellPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static void assertLfNoBom(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            throw new GateException(GateErrorCode.INTERNAL, "refusing to write a hook with a UTF-8 BOM");
        }
        for (byte b : bytes) {
            if (b == '\r') {
                throw new GateException(GateErrorCode.INTERNAL, "refusing to write a hook containing CR");
            }
        }
    }

    /**
     * Best effort: on NTFS the POSIX permission view is unavailable, and git for Windows runs hooks
     * regardless of an execute bit. Failing here would block installation on the target platform for
     * no gain, so the outcome is checked by the preflight self-check actually running the hook.
     */
    private static void makeExecutable(Path hookPath) {
        try {
            hookPath.toFile().setExecutable(true, false);
        } catch (RuntimeException ignored) {
            // Verified functionally by the preflight self-check instead.
        }
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new GateException(GateErrorCode.INTERNAL, "SHA-256 unavailable", e);
        }
    }
}
