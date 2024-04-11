package org.imixs.ai;

import java.io.Writer;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

/**
 * The Imxis-ai Prompt Builder is a helper class to build a JSON string for an
 * Open-AI Rest API Prompt
 */
public class PromptBuilder {

    private String prompt;
    private int predict = 128;

    private JsonObject jsonObject = null;

    public String getPrompt() {
        return prompt;
    }

    public PromptBuilder setPrompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    public int getPredict() {
        return predict;
    }

    public PromptBuilder setPredict(int predict) {
        this.predict = predict;
        return this;
    }

    public JsonObject getJsonObject() {
        return jsonObject;
    }

    /**
     * Returns the JsonObject representing the given Data
     * 
     * @return
     */
    public JsonObject build() {
        JsonObjectBuilder objectBuilder = Json.createObjectBuilder()
                .add("prompt", this.getPrompt())
                .add("n_predict", this.getPredict());

        jsonObject = objectBuilder.build();

        return jsonObject;
    }

    /**
     * Returns the Json String representing the given Data
     */
    public String buildString() {
        jsonObject = this.build();
        try {
            String jsonString;
            try (Writer writer = new java.io.StringWriter()) {
                Json.createWriter(writer).write(jsonObject);
                jsonString = writer.toString();
            }
            return jsonString;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

}
