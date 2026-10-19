package org.imixs.ai.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import jakarta.json.JsonObject;

public class TestLLMOptions {

    @Test
    public void testEmptyConstructor() {
        LLMOptions options = new LLMOptions();
        assertTrue(options.isEmpty());
        assertEquals("{}", options.toString());
    }

    @Test
    public void testJsonStringConstructor() {
        LLMOptions options = new LLMOptions("{\"temperature\": 0.2}");
        assertFalse(options.isEmpty());
        assertEquals(0.2, options.toJson().getJsonNumber("temperature").doubleValue());
    }

    @Test
    public void testNullStringTreatedAsEmpty() {
        LLMOptions options = new LLMOptions((String) null);
        assertTrue(options.isEmpty());
    }

    @Test
    public void testBlankStringTreatedAsEmpty() {
        LLMOptions options = new LLMOptions("   ");
        assertTrue(options.isEmpty());
    }

    /**
     * Critical test: verifies merge has REPLACE semantics for matching keys and ADD
     * semantics for new keys. This is the core layering behavior.
     */
    @Test
    public void testMergeOverridesMatchingKeysAndAddsNewKeys() {
        LLMOptions base = new LLMOptions(
                "{\"temperature\": 0.2, \"max_tokens\": 1024}");
        LLMOptions override = new LLMOptions(
                "{\"temperature\": 0.7, \"top_p\": 0.9}");

        base.merge(override);

        JsonObject result = base.toJson();
        // temperature was overridden
        assertEquals(0.7, result.getJsonNumber("temperature").doubleValue());
        // max_tokens was preserved
        assertEquals(1024, result.getJsonNumber("max_tokens").intValue());
        // top_p was added
        assertEquals(0.9, result.getJsonNumber("top_p").doubleValue());
    }

    @Test
    public void testMergeWithEmptyIsNoOp() {
        LLMOptions base = new LLMOptions("{\"temperature\": 0.2}");
        base.merge(new LLMOptions());

        assertEquals(0.2, base.toJson().getJsonNumber("temperature").doubleValue());
    }

    @Test
    public void testMergeWithNullIsNoOp() {
        LLMOptions base = new LLMOptions("{\"temperature\": 0.2}");
        base.merge((LLMOptions) null);

        assertEquals(0.2, base.toJson().getJsonNumber("temperature").doubleValue());
    }

    @Test
    public void testMergeFromJsonString() {
        LLMOptions base = new LLMOptions("{\"temperature\": 0.2}");
        base.merge("{\"temperature\": 0.7}");

        assertEquals(0.7, base.toJson().getJsonNumber("temperature").doubleValue());
    }

    /**
     * Three-layer merge as it happens in the adapter: endpoint defaults -> BPMN
     * override -> prompt override.
     */
    @Test
    public void testThreeLayerMerge() {
        LLMOptions options = new LLMOptions(
                "{\"model\": \"llama-3\", \"temperature\": 0.2, \"max_tokens\": 1024}");
        options.merge("{\"temperature\": 0.5}");      // BPMN override
        options.merge("{\"temperature\": 0.9, \"top_p\": 0.95}"); // Prompt override

        JsonObject result = options.toJson();
        assertEquals("llama-3", result.getString("model"));        // from L1
        assertEquals(1024, result.getJsonNumber("max_tokens").intValue()); // from L1
        assertEquals(0.9, result.getJsonNumber("temperature").doubleValue()); // L3 wins
        assertEquals(0.95, result.getJsonNumber("top_p").doubleValue());   // from L3
    }
}