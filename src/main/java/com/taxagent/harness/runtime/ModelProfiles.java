package com.taxagent.harness.runtime;

import java.util.List;

/** Named model configurations from the environment (T30). Agents reference a name; keys never leave the environment. */
@FunctionalInterface
public interface ModelProfiles {
    /** Never null: an unknown or unconfigured profile answers MODEL_NOT_CONFIGURED, it never falls back to another. */
    LlmClient profile(String name);
    /** Non-secret description for the configuration page: name, whether configured, model name. */
    default List<Profile> describe() { return List.of(new Profile("default", true, null)); }
    record Profile(String name, boolean configured, String model) {}

    LlmClient NOT_CONFIGURED = request -> { throw new OpenAiCompatibleClient.ModelException("MODEL_NOT_CONFIGURED"); };
    /** One client as the default profile; used by tests and by callers without named profiles. */
    static ModelProfiles single(LlmClient model) { return name -> "default".equals(name) ? model : NOT_CONFIGURED; }
}
