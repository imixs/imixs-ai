package org.imixs.ai.api;

/**
 * The ToolCallResult is used by the OpenAIAPIService to indicate a successful
 * tool call, processed by the method processToolCallResult.
 * <p>
 * Business result values (ImixsAIResultEvent) are dispatched directly within
 * processToolCallResult for each individual tool call. This class only signals
 * whether the agent loop may be terminated.
 */
public class ToolCallResult {
    private final boolean wasToolCall;
    private final boolean taskComplete;

    public ToolCallResult(boolean wasToolCall, boolean taskComplete) {
        this.wasToolCall = wasToolCall;
        this.taskComplete = taskComplete;
    }

    public boolean wasToolCall() {
        return wasToolCall;
    }

    public boolean isTaskComplete() {
        return taskComplete;
    }
}