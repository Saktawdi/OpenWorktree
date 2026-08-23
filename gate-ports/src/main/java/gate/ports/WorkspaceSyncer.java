package gate.ports;

import gate.domain.git.ObjectId;
import gate.domain.git.RepoRef;

/**
 * 发布收敛到权威库后，将提交尽力回写到用户工作区。
 *
 * <p>实现必须只允许快进；{@link SyncOutcome.Status#DEFERRED} 表示工作区尚未同步，永不视为
 * 发布失败。实现不得 force、reset、修改用户 remote，或覆盖已检出分支上的未提交变更。
 */
public interface WorkspaceSyncer {

    SyncOutcome syncWorkspace(RepoRef workspaceRepo, RepoRef authRepo, String targetRef, ObjectId commit);

    record SyncOutcome(Status status, String note) {
        public enum Status { SYNCED, ALREADY, DEFERRED }
    }
}
