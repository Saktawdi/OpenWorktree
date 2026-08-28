package gate.application.basesync;

/**
 * Base-sync use case input (T-118).
 *
 * @param ticketNo   ticket whose clone/branch is re-synced onto the base branch tip
 * @param allowDirty replay uncommitted worktree changes (manual button); {@code false} skips a
 *                   dirty clone untouched (session-start auto-sync)
 * @param trigger    audit discriminator: {@code auto} (session start) or {@code manual} (API)
 */
public record SyncBaseCommand(String ticketNo, boolean allowDirty, String trigger) {
}
