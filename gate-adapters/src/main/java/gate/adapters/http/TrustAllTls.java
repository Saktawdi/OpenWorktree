package gate.adapters.http;

import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * 信任所有证书的上游 TLS 上下文（本地直连场景）。
 *
 * <p>上游网关（newapi 中转、本地代理、自建服务）经常挂在自签证书或企业 MITM 代理链后面，
 * JDK 默认信任库（cacerts）校验会以 {@code PKIX path building failed} 直接拒绝——这不是上游
 * 配置错误，而是"本机 Java 不认它的证书链"。gate 本身是本地单人工具（web 控制台强制回环
 * 绑定、fail-closed），因此对<strong>只读的上游模型列表调用</strong>（设置中心拉取、
 * {@code gate provider pull}）放宽 TLS：证书链与主机名都不再校验。
 *
 * <p>放宽的代价是中间人可窥探/篡改模型列表响应；API key 仍只经 Authorization 头传输，
 * 凭据管理（设置中心 KMS 加密落库）不受影响。核心的审查/发布链路不经过
 * 本类——prism 引擎的上游调用由子进程自己的 TLS 策略负责。
 *
 * <p>主机名校验一并通过"自定义 TrustManager"取消：JSSE 的 endpoint identification 检查
 * 由默认 {@code X509TrustManagerImpl} 在信任管理器内部执行，替换为全放行的
 * {@link X509ExtendedTrustManager} 后即不再发生（JDK HttpClient 未暴露独立的
 * HostnameVerifier）。
 */
public final class TrustAllTls {

    private static final SSLContext CONTEXT = buildContext();

    private TrustAllTls() {
    }

    /** 在既有 builder 上接入信任所有证书的 TLS（证书链 + 主机名校验都不再做）。 */
    public static HttpClient.Builder apply(HttpClient.Builder builder) {
        return builder.sslContext(CONTEXT);
    }

    private static SSLContext buildContext() {
        TrustManager[] trustAll = new TrustManager[]{new X509ExtendedTrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType,
                    java.net.Socket socket) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType,
                    java.net.Socket socket) {
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType,
                    javax.net.ssl.SSLEngine engine) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType,
                    javax.net.ssl.SSLEngine engine) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot build trust-all SSL context", e);
        }
    }
}
