package gate.adapters.approval;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.domain.publish.ApprovalGrant;
import gate.domain.publish.ApprovalId;
import gate.ports.store.ApprovalStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Filesystem approval store (架构落地执行文档 §6.2).
 *
 * <p>The record lives outside {@code auth.git}, and its directory path is baked into the generated
 * hook. Authorisation is <em>not</em> secrecy of the id — argv is world readable, so the id is
 * deliberately non-secret. It is: the record exists, it binds exactly this
 * {@code (ref, old, new, tree)}, and the hook consumes it with a single {@code mv}, which the OS
 * makes atomic so two concurrent pushes racing the same id cannot both win (B17).
 *
 * <p>Records are written LF-only: the hook matches lines with {@code grep -qx}, so a stray CR would
 * make every comparison miss and turn the gate into a permanent-reject.
 */
public final class FsApprovalStore implements ApprovalStore {

    private final Path approvalsDir;
    private final SecureRandom random = new SecureRandom();

    public FsApprovalStore(Path approvalsDir) {
        this.approvalsDir = approvalsDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.approvalsDir);
            Files.createDirectories(consumedDir());
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "cannot create approvals directory " + this.approvalsDir, e);
        }
    }

    @Override
    public ApprovalId allocate() {
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        return ApprovalId.of(HexFormat.of().formatHex(raw));
    }

    @Override
    public void issue(ApprovalId id, ApprovalGrant grant) {
        write(id, grant, false);
    }

    @Override
    public void reissue(ApprovalId id, ApprovalGrant grant) {
        if (isConsumed(id)) {
            // A consumed record means receive-pack already accepted a push under this id. Writing it
            // back would authorise a second ref transition with the same one-shot credential.
            throw new GateException(GateErrorCode.GATE_ERROR_IO,
                    "refusing to re-issue approval " + id + ": it is already consumed");
        }
        write(id, grant, true);
    }

    @Override
    public boolean isConsumed(ApprovalId id) {
        return Files.isRegularFile(consumedDir().resolve(id.value()));
    }

    @Override
    public boolean isLive(ApprovalId id) {
        return Files.isRegularFile(approvalsDir.resolve(id.value()));
    }

    private void write(ApprovalId id, ApprovalGrant grant, boolean overwrite) {
        Path target = approvalsDir.resolve(id.value());
        if (!target.getParent().equals(approvalsDir)) {
            throw new GateException(GateErrorCode.INTERNAL, "approval id escaped its directory: " + id);
        }
        byte[] bytes = grant.toRecordText().getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            if (b == '\r') {
                throw new GateException(GateErrorCode.INTERNAL,
                        "refusing to write an approval record containing CR; the hook greps with -qx");
            }
        }
        try {
            if (overwrite) {
                Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } else {
                Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
        } catch (IOException e) {
            throw new GateException(GateErrorCode.GATE_ERROR_IO, "cannot write approval record " + target, e);
        }
    }

    public Path approvalsDir() {
        return approvalsDir;
    }

    public Path consumedDir() {
        return approvalsDir.resolve("consumed");
    }
}
