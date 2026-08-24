package gate.adapters.git;

import gate.domain.publish.CommitIdentity;
import gate.ports.infra.Clock;
import gate.ports.git.CommitIdentityProvider;
import gate.ports.infra.ProcessRunner;

/**
 * 默认身份解析：本机 git 配置（{@code git config --get user.name/user.email}，global+system
 * 合成视角）为发布作者；{@code [publish_identity]} 显式填写则优先；两者皆空回退固定
 * {@code gate <gate@localhost>}。时间为当前真实时刻（UTC）。
 *
 * <p>回退链必须以固定值收尾：CI 与无 git 配置的机器上发布不能因身份缺失而失败。
 */
public class LocalGitCommitIdentityProvider implements CommitIdentityProvider {

    private static final String FALLBACK_NAME = "gate";
    private static final String FALLBACK_EMAIL = "gate@localhost";

    private final GitCli git;
    private final Clock clock;
    private final String overrideName;
    private final String overrideEmail;

    public LocalGitCommitIdentityProvider(GitCli git, Clock clock,
                                          String overrideName, String overrideEmail) {
        this.git = git;
        this.clock = clock;
        this.overrideName = trimToNull(overrideName);
        this.overrideEmail = trimToNull(overrideEmail);
    }

    @Override
    public CommitIdentity forPublish() {
        String name = overrideName != null ? overrideName : localConfig("user.name", FALLBACK_NAME);
        String email = overrideEmail != null ? overrideEmail : localConfig("user.email", FALLBACK_EMAIL);
        return new CommitIdentity(name, email, clock.now().getEpochSecond() + " +0000");
    }

    /** 可覆写：单测注入假配置，避免依赖/污染机器全局 git config。 */
    String localConfig(String key, String fallback) {
        try {
            ProcessRunner.ProcRun run = git.run(java.nio.file.Path.of(System.getProperty("user.home")),
                    java.util.Map.of(), "config", "--get", key);
            String value = run.ok() ? run.stdout().trim() : "";
            return value.isBlank() ? fallback : value;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
