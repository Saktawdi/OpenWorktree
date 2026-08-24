package gate.ports.git;

import gate.domain.git.RepoRef;
import java.nio.file.Path;

/**
 * Generates and installs the {@code pre-receive} hook (架构落地执行文档 §6.3).
 *
 * <p>The hook is generated, never hand-maintained, and written with LF line endings and no BOM
 * (N2) — {@code sh} fails outright on CRLF, and the failure looks exactly like a healthy gate
 * because both present as "everything is rejected" (spike-结论 §2.4).
 */
public interface HookInstaller {

    /** @return sha256 of the exact bytes written */
    String install(RepoRef authRepo, java.util.List<String> targetRefWhitelist, Path approvalsDir);

    /** Renders the hook text without touching disk. Used by the sandbox self-check and by tests. */
    String render(java.util.List<String> targetRefWhitelist, Path approvalsDir);

    /** sha256 of the bytes currently on disk, so drift is detectable before every publish (§10.3). */
    String installedDigest(RepoRef authRepo);

    /** sha256 of the bytes {@link #render} would write, for comparison against the installed copy. */
    String expectedDigest(java.util.List<String> targetRefWhitelist, Path approvalsDir);
}
