package org.imixs.ai.api;

import java.io.StringReader;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;

/**
 * Represents a set of LLM options (e.g. temperature, max_tokens, top_p) as a
 * generic, opaque JSON structure. Supports additive merging across the
 * configuration layers (endpoint defaults, BPMN event overrides, prompt-level
 * overrides).
 * <p>
 * The class does not interpret option keys or values - it forwards them as-is
 * to the LLM endpoint. This keeps the workflow engine provider-neutral and
 * allows new LLM parameters to be configured without code changes.
 */
public class LLMOptions {

    private JsonObject options;

    /** Creates an empty options object. */
    public LLMOptions() {
        this.options = Json.createObjectBuilder().build();
    }

    /**
     * Creates an options object from a parsed JsonObject. Null is treated as empty.
     */
    public LLMOptions(JsonObject options) {
        this.options = (options != null) ? options : Json.createObjectBuilder().build();
    }

    /**
     * Creates an options object from a JSON string. Null or blank is treated as
     * empty.
     */
    public LLMOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            this.options = Json.createObjectBuilder().build();
            return;
        }
        try (JsonReader reader = Json.createReader(new StringReader(optionsJson))) {
            this.options = reader.readObject();
        }
    }

    /**
     * Merges another options layer into this one. Keys from 'other' override
     * matching keys in this instance. Returns this for fluent chaining.
     */
    public LLMOptions merge(LLMOptions other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        JsonObjectBuilder builder = Json.createObjectBuilder(this.options);
        // JsonObjectBuilder.add() has replace semantics for existing keys -
        // values from 'other' override matching keys in this.options.
        for (String key : other.options.keySet()) {
            builder.add(key, other.options.get(key));
        }
        this.options = builder.build();
        return this;
    }

    /** Convenience: merge directly from a JSON string. */
    public LLMOptions merge(String optionsJson) {
        return merge(new LLMOptions(optionsJson));
    }

    public boolean isEmpty() {
        return options.isEmpty();
    }

    /**
     * Returns the underlying JsonObject for serialization into the request body.
     */
    public JsonObject toJson() {
        return options;
    }

    @Override
    public String toString() {
        return options.toString();
    }
}