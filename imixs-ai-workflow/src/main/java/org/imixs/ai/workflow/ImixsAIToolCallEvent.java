package org.imixs.ai.workflow;

import jakarta.json.JsonObject;

/**
 * ImixsAIToolCallEvent is fired when the LLM responds with a tool call.
 * Observers can handle the tool call and set the result.
 * 
 * The result will be sent back to the LLM as a tool message.
 */
public class ImixsAIToolCallEvent {

    private final String toolName;
    private final JsonObject arguments;
    private final String toolCallId;
    private String result = null;

    public ImixsAIToolCallEvent(String toolName, JsonObject arguments, String toolCallId) {
        this.toolName = toolName;
        this.arguments = arguments;
        this.toolCallId = toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public JsonObject getArguments() {
        return arguments;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    /**
     * Returns true if an observer has handled the tool call
     */
    public boolean isHandled() {
        return result != null;
    }
}