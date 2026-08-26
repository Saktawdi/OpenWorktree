package gate.cli.cmd;
import gate.cli.BaseCommand;
import gate.cli.GateComponents;
import gate.cli.util.JsonOut;


import gate.adapters.engine.ApiKeyResolver;
import gate.application.util.MiniJson;
import gate.domain.error.GateErrorCode;
import gate.domain.error.GateException;
import gate.ports.store.ProviderRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import picocli.CommandLine;

/**
 * {@code gate provider add|pull|list}: LLM provider management (架构落地执行文档 §10.1.1).
 *
 * <p>The interaction model mirrors opencode's: configure a provider first, then pull its model list,
 * expose {@code (provider_id, model_name)} outward. {@code base_url} and the credential live in the
 * {@code provider} table — the API key is stored KMS-encrypted via the web console (设置中心) and is
 * injected into child processes via environment — never argv, never plaintext in the DB, never the
 * audit log (ADR-9, §6.1).
 */
@CommandLine.Command(name = "provider",
        description = "Manage LLM providers (add / pull models / list)",
        subcommands = {
                ProviderCommand.Add.class,
                ProviderCommand.Pull.class,
                ProviderCommand.ListAll.class
        })
public final class ProviderCommand implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.err);
    }

    /** {@code gate provider add <id> --name ... --base-url ... [--type openai-compatible]} */
    @CommandLine.Command(name = "add", description = "Add or update a provider row")
    static final class Add extends BaseCommand {

        @CommandLine.Parameters(index = "0", description = "Provider id (stable, e.g. newapi)")
        String id;

        @CommandLine.Option(names = "--name", required = true, description = "Human-readable name")
        String name;

        @CommandLine.Option(names = "--base-url", required = true,
                description = "OpenAI-compatible base URL, e.g. https://newapi.sakta.top/v1")
        String baseUrl;

        @CommandLine.Option(names = "--type", defaultValue = "openai-compatible",
                description = "Provider type (default: openai-compatible)")
        String type;

        @Override
        public void run() {
            GateComponents c = components();
            ProviderRepository providers = c.providerRepository();
            providers.upsert(new ProviderRepository.ProviderRow(
                    id, name, baseUrl, "unconfigured", type, c.clock().now()), c.clock().now());

            JsonOut.emit(System.out, "provider.add", Map.of(
                    "provider_id", id,
                    "base_url", baseUrl,
                    "api_key_ref", "unconfigured",
                    "note", "set the API key in the web console (设置中心 → LLM Providers); it is stored KMS-encrypted"));
        }
    }

    /** {@code gate provider pull <id>}: GET {base_url}/models using the stored credential, cache into model table. */
    @CommandLine.Command(name = "pull", description = "Pull the model list from a provider")
    static final class Pull extends BaseCommand {

        @CommandLine.Parameters(index = "0", description = "Provider id")
        String id;

        @Override
        public void run() {
            GateComponents c = components();
            ProviderRepository providers = c.providerRepository();
            ProviderRepository.ProviderRow provider = providers.find(id).orElseThrow(
                    () -> new GateException(GateErrorCode.USAGE, "no provider row for id=" + id));

            String key = ApiKeyResolver.resolve(provider.apiKeyRef(), c.kmsService());
            if (key == null || key.isBlank()) {
                throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                        "provider \"" + id + "\" has no usable API credential (credential="
                                + ApiKeyResolver.describe(provider.apiKeyRef()) + ")"
                                + " — set the API key in the web console (设置中心 → LLM Providers)");
            }

            List<String> models = fetchModels(provider.baseUrl(), key);
            providers.replaceModels(id, models, c.clock().now());

            JsonOut.emit(System.out, "provider.pull", Map.of(
                    "provider_id", id,
                    "models", models,
                    "count", models.size()));
        }

        @SuppressWarnings("unchecked")
        private static List<String> fetchModels(String baseUrl, String apiKey) {
            String url = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
            try {
                HttpClient client = gate.adapters.http.TrustAllTls.apply(HttpClient.newBuilder())
                        .connectTimeout(Duration.ofSeconds(15)).build();
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + apiKey)
                        .GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() / 100 != 2) {
                    throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                            "GET " + url + " returned " + resp.statusCode()
                                    + "; first 200 chars=" + truncate(resp.body(), 200));
                }
                Object parsed = MiniJson.parse(resp.body());
                if (!(parsed instanceof Map)) {
                    throw new GateException(GateErrorCode.GATE_ERROR_CONFIG, "models response is not a JSON object");
                }
                Object data = ((Map<String, Object>) parsed).get("data");
                if (!(data instanceof List<?> list)) {
                    throw new GateException(GateErrorCode.GATE_ERROR_CONFIG, "models response has no 'data' array");
                }
                List<String> out = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m && m.get("id") instanceof String modelId) {
                        out.add(modelId);
                    }
                }
                out.sort(String::compareTo);
                return out;
            } catch (GateException e) {
                throw e;
            } catch (Exception e) {
                throw new GateException(GateErrorCode.GATE_ERROR_CONFIG,
                        "cannot reach " + url + ": " + e.getMessage(), e);
            }
        }
    }

    /** {@code gate provider list}: list configured providers and their cached models. */
    @CommandLine.Command(name = "list", description = "List configured providers and cached models")
    static final class ListAll extends BaseCommand {

        @Override
        public void run() {
            GateComponents c = components();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ProviderRepository.ProviderRow p : c.providerRepository().findAll()) {
                List<String> models = c.providerRepository().models(p.id());
                rows.add(Map.of(
                        "id", p.id(),
                        "name", p.name(),
                        "base_url", p.baseUrl(),
                        "type", p.type(),
                        "model_count", models.size(),
                        "models", models));
            }
            JsonOut.emit(System.out, "provider.list", Map.of("providers", rows));
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
