package com.taxagent.harness.demo;

import com.taxagent.harness.runtime.*;
import com.taxagent.harness.tool.FileService;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class DemoConfiguration {
    /**
     * "default" is LLM_ENDPOINT / LLM_API_KEY / LLM_MODEL / LLM_PROTOCOL. A named profile X (T30) uses
     * LLM_X_ENDPOINT, LLM_X_API_KEY, LLM_X_MODEL, LLM_X_PROTOCOL. Missing values never fall back to another profile.
     */
    @Bean ModelProfiles modelProfiles(Environment env) {
        var cache = new ConcurrentHashMap<String, LlmClient>();
        return new ModelProfiles() {
            public LlmClient profile(String name) {
                if (name == null || !name.matches("default|[A-Za-z0-9_]{1,30}")) return NOT_CONFIGURED;
                return cache.computeIfAbsent(name, n -> client(env, prefix(n)));
            }
            public List<Profile> describe() {
                var names = new TreeSet<String>();
                for (var key : System.getenv().keySet())
                    if (key.startsWith("LLM_") && key.endsWith("_ENDPOINT") && key.length() > "LLM__ENDPOINT".length())
                        names.add(key.substring(4, key.length() - "_ENDPOINT".length()));
                var out = new ArrayList<Profile>();
                out.add(profileOf(env, "default"));
                for (var n : names) if (n.matches("[A-Za-z0-9_]{1,30}")) out.add(profileOf(env, n));
                return List.copyOf(out);
            }
        };
    }
    /**
     * T48: scanned-invoice reading uses its own profile LLM_VISION_ENDPOINT / _API_KEY / _MODEL / _PROTOCOL, which
     * must name a model that reads images. Unconfigured, scans fall back to manual entry; never to another profile.
     */
    @Bean FileService.ScanReader scanReader(Environment env) {
        var p = prefix("VISION");
        if (!configured(env, p)) return VisionClient.NOT_CONFIGURED;
        String endpoint = env.getProperty(p + "ENDPOINT"), key = env.getProperty(p + "API_KEY"), model = env.getProperty(p + "MODEL");
        var anthropic = "anthropic".equalsIgnoreCase(env.getProperty(p + "PROTOCOL")) || URI.create(endpoint).getPath().contains("/anthropic");
        return new VisionClient(URI.create(endpoint), key, model, anthropic, Duration.ofSeconds(60));
    }
    private static String prefix(String name) { return "default".equals(name) ? "LLM_" : "LLM_" + name + "_"; }
    private static ModelProfiles.Profile profileOf(Environment env, String name) {
        var p = prefix(name);
        return new ModelProfiles.Profile(name, configured(env, p), env.getProperty(p + "MODEL"));
    }
    private static boolean configured(Environment env, String p) {
        return List.of("ENDPOINT", "API_KEY", "MODEL").stream().allMatch(k -> { var v = env.getProperty(p + k); return v != null && !v.isBlank(); });
    }
    private static LlmClient client(Environment env, String p) {
        if (!configured(env, p)) return ModelProfiles.NOT_CONFIGURED;
        String endpoint = env.getProperty(p + "ENDPOINT"), key = env.getProperty(p + "API_KEY"), model = env.getProperty(p + "MODEL");
        // Construction performs no network request. Never log configuration or exception payloads.
        var protocol = env.getProperty(p + "PROTOCOL");
        var path = URI.create(endpoint).getPath();
        var anthropic = "anthropic".equalsIgnoreCase(protocol) || path.contains("/anthropic");
        var timeout = Duration.ofSeconds(60);
        if (anthropic) return new AnthropicMessagesClient(URI.create(endpoint), key, model, timeout);
        return new OpenAiCompatibleClient(URI.create(endpoint), key, model, timeout);
    }
}
