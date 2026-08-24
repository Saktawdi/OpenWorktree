package gate.adapters.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gate.adapters.process.ProcessRunnerImpl;
import gate.ports.infra.Clock;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 发布身份解析：覆盖值优先 → 本机 git 作者 → 固定回退；时间为真实时刻。
 * 读取本机 git config 的一步被包私有方法隔离，测试覆写注入假值，不依赖也不污染机器全局配置。
 */
class LocalGitCommitIdentityProviderTest {

    private static final class FixedClock implements Clock {
        private final Instant now = Instant.parse("2026-08-23T12:00:00Z");

        @Override
        public Instant now() {
            return now;
        }
    }

    /** 注入假 git config 视角的被测对象。 */
    private static final class StubbedProvider extends LocalGitCommitIdentityProvider {
        private final String localName;
        private final String localEmail;

        StubbedProvider(String overrideName, String overrideEmail, String localName, String localEmail) {
            super(new GitCli(new ProcessRunnerImpl(Path.of(".").toAbsolutePath())), new FixedClock(),
                    overrideName, overrideEmail);
            this.localName = localName;
            this.localEmail = localEmail;
        }

        @Override
        String localConfig(String key, String fallback) {
            if ("user.name".equals(key)) {
                return localName == null ? fallback : localName;
            }
            return localEmail == null ? fallback : localEmail;
        }
    }

    @Test
    void explicit_override_wins_over_local_git_config() {
        var identity = new StubbedProvider("Ops Bot", "ops@example.com", "Someone", "someone@example.com")
                .forPublish();
        assertEquals("Ops Bot", identity.name());
        assertEquals("ops@example.com", identity.email());
    }

    @Test
    void blank_override_falls_back_to_local_git_author() {
        var identity = new StubbedProvider("  ", "", "Someone", "someone@example.com").forPublish();
        assertEquals("Someone", identity.name());
        assertEquals("someone@example.com", identity.email());
    }

    @Test
    void missing_local_config_falls_back_to_fixed_gate_identity() {
        var identity = new StubbedProvider(null, null, null, null).forPublish();
        assertEquals("gate", identity.name());
        assertEquals("gate@localhost", identity.email());
    }

    @Test
    void date_is_real_now_epoch_seconds_utc() {
        var identity = new StubbedProvider(null, null, null, null).forPublish();
        assertEquals(Instant.parse("2026-08-23T12:00:00Z").getEpochSecond() + " +0000", identity.date());
    }

    @Test
    void real_git_config_path_resolves_without_throwing(@TempDir Path dir) {
        // 真实 GitCli 路径冒烟：无论机器是否配置 git 作者，都必须得到非空身份，日期格式合法。
        var provider = new LocalGitCommitIdentityProvider(
                new GitCli(new ProcessRunnerImpl(dir)), new FixedClock(), null, null);
        var identity = provider.forPublish();
        assertTrue(!identity.name().isBlank() && !identity.email().isBlank());
        assertTrue(identity.date().endsWith(" +0000"));
        assertNotEquals("1700000000 +0000", identity.date(), "date must not be the legacy pinned value");
    }
}
