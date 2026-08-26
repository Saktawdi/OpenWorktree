package gate.adapters.engine;

import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.infra.KmsService;

/**
 * Resolves a provider's {@code api_key_ref} into a plaintext API key (设置中心 LLM 凭据的统一取值口径).
 *
 * <p>The only supported form is {@code kms:<ciphertext>} — the settings center encrypts the key the
 * user pastes through the {@link KmsService} port and persists the ciphertext in the provider row
 * (密文落库，明文只在解密瞬间存在). {@code none} / {@code unconfigured} / blank mean no credential.
 *
 * <p>The plaintext key is never logged and never lands in argv — callers inject it via environment
 * variables or HTTP headers only.
 */
public final class ApiKeyResolver {

    private ApiKeyResolver() {
    }

    /** True when the reference can possibly yield a key (used for the 已配置密钥 badge). */
    public static boolean isConfigured(String apiKeyRef) {
        if (apiKeyRef == null || apiKeyRef.isBlank()) {
            return false;
        }
        String lower = apiKeyRef.toLowerCase(java.util.Locale.ROOT);
        return !lower.equals("none") && !lower.equals("unconfigured");
    }

    /**
     * True for legacy bare values (neither {@code kms:} nor {@code none}/{@code unconfigured}) —
     * plaintext keys stored before the KMS credential flow existed. {@link #resolve} rejects them;
     * callers should migrate them via {@code kms:encrypt} (see GateRuntime startup migration and
     * the engine factory's lazy self-heal).
     */
    public static boolean isLegacyPlaintext(String apiKeyRef) {
        return isConfigured(apiKeyRef) && !apiKeyRef.regionMatches(true, 0, "kms:", 0, 4);
    }

    /** Safe, non-secret form for error messages — never echoes the stored value. */
    public static String describe(String apiKeyRef) {
        if (!isConfigured(apiKeyRef)) {
            return "未配置";
        }
        if (apiKeyRef.regionMatches(true, 0, "kms:", 0, 4)) {
            return "kms:***";
        }
        return "legacy-plaintext(未加密,将自动迁移)";
    }

    /**
     * @return the plaintext key, or {@code null} when the reference holds no credential.
     * @throws GateException when the ciphertext cannot be decrypted or no KMS is wired.
     */
    public static String resolve(String apiKeyRef, KmsService kms) {
        if (!isConfigured(apiKeyRef)) {
            return null;
        }
        if (!apiKeyRef.regionMatches(true, 0, "kms:", 0, 4)) {
            // Anything that is not a kms: ciphertext is treated as absent — fail-closed over
            // guessing that a legacy value is a literal key (明文绝不入库). Legacy rows are
            // migrated to kms: by GateRuntime at startup / the engine factory lazily.
            return null;
        }
        String ciphertext = apiKeyRef.substring(4).trim();
        if (ciphertext.isEmpty()) {
            return null;
        }
        if (kms == null) {
            throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                    "credential is kms-encrypted but no KMS service is wired");
        }
        String key = kms.decrypt(ciphertext);
        return key == null || key.isBlank() ? null : key.trim();
    }
}
