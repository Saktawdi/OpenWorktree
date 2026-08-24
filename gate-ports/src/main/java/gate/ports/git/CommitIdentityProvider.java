package gate.ports.git;

import gate.domain.publish.CommitIdentity;

/**
 * 发布提交的身份来源：默认采用本机 git 作者，{@code [publish_identity]} 显式填写则覆盖。
 *
 * <p>时间为调用时刻的真实时间——确定性不变量不受影响：身份在创建 publish intent 时
 * 解析一次并随 intent 落库，{@code buildCommit} 始终从 intent 行钉环境变量，同一 intent
 * 重放仍得同一 SHA（I1/I5）。
 */
public interface CommitIdentityProvider {

    /** Author 与 committer 同值的发布身份；date 为 {@code <epochSeconds> +0000}。 */
    CommitIdentity forPublish();
}
