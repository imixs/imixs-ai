package org.imixs.ai.tools;

import org.imixs.ai.ImixsAIContextHandler;

import jakarta.json.JsonObject;

/**
 * ImixsAIToolCallEvent is fired when the LLM responds with a tool call.
 * Observers can handle the tool call and set the result.
 *
 * The result will be sent back to the LLM as a tool message. The contextHandler
 * provides access to the current conversation context and the associated
 * workitem.
 */
public class ImixsAIToolCallEvent {

    private final String toolName;
    private final JsonObject arguments;
    private final String toolCallId;
    private final ImixsAIContextHandler contextHandler;
    private String error;
    // Tool message sent back to the LLM as tool-role message
    private String toolMessage = null;
    // Business result value for the application layer
    private String resultValue = null;
    private boolean taskCompleted = false; // indicates that a task is marked as completed.

    /**
     * Constructor with contextHandler. Provides observers with access to the
     * current conversation context and the associated workitem.
     */
    public ImixsAIToolCallEvent(String toolName, JsonObject arguments, String toolCallId,
            ImixsAIContextHandler contextHandler) {
        this.toolName = toolName;
        this.arguments = arguments;
        this.toolCallId = toolCallId;
        this.contextHandler = contextHandler;
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

    /**
     * Returns the context handler for this tool call, or null if not available.
     */
    public ImixsAIContextHandler getContextHandler() {
        return contextHandler;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public boolean hasError() {
        return error != null && !error.isBlank();
    }

    public void setToolMessage(String toolMessage) {
        this.toolMessage = toolMessage;
    }

    public String getToolMessage() {
        return toolMessage;
    }

    public void setResultValue(String resultValue) {
        this.resultValue = resultValue;
    }

    public String getResultValue() {
        return resultValue;
    }

    public boolean isTaskCompleted() {
        return taskCompleted;
    }

    public void setTaskCompleted(boolean taskCompleted) {
        this.taskCompleted = taskCompleted;
    }

}