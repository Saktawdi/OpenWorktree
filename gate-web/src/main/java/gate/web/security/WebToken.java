package gate.web.security;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.store.CredentialRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Bootstraps the HUMAN-domain Web token (执行文档-后端-web §3.2, ADR-10).
 *
 * <p>On startup, if {@code [web] human_token_file} does not exist or is empty, a fresh HUMAN token is
 * minted via {@link CredentialRepository#issueHumanToken} and written to that file (owner-only
 * permissions). If the file already holds a non-empty token, it is read back so the startup banner
 * can print it. The plaintext lives only in this file and the startup log line — only its SHA-256
 * hash is persisted in the credential table (§6.1 / ADR-9).
 *
 * <p>The token is never passed as argv. The Web browser sends it as {@code Authorization: Bearer}
 * (or, for SSE only, {@code ?token=}).
 */
public final class WebToken {

    private WebToken() {
    }

    /**
     * Ensures a HUMAN token exists on disk and returns its plaintext.
     *
     * @param tokenFile   the {@code [web] human_token_file} path (already resolved absolute)
     * @param credentials the credential store used to mint the token
     * @param now         issuance time
     * @return the plaintext token (freshly minted, or read back from an existing file)
     */
    public static String ensure(Path tokenFile, CredentialRepository credentials, Instant now) {
        try {
            if (Files.exists(tokenFile)) {
                String existing = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty()) {
                    return existing;
                }
            }
            String token = credentials.issueHumanToken(now);
            Path parent = tokenFile.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(tokenFile, token + System.lineSeparator(), StandardCharsets.UTF_8);
            restrictToOwner(tokenFile);
            return token;
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "cannot bootstrap web token file at " + tokenFile, e);
        }
    }

    /**
     * Best-effort tightening of the token file to owner-only. On POSIX filesystems this sets 0600; on
     * Windows the default ACL already limits to the current user, and POSIX view is unavailable, so
     * this is a no-op there. Failure to tighten is not fatal — the token's residual exposure is bound
     * to the local machine (§3.4.1 threat model).
     */
    private static void restrictToOwner(Path file) {
        try {
            var posix = Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
            if (posix != null) {
                Files.setPosixFilePermissions(file,
                        java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            }
        } catch (Exception ignored) {
            // Windows / non-POSIX: default ACL restricts to current user. Not fatal.
        }
    }
}
