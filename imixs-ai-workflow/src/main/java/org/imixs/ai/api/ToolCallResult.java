package org.imixs.ai.api;

/**
 * The ToolCallResult is used by the OpenAIAPIService to indicate a successful
 * tool call, processed by the method processToolCallResult. The ToolCallResult
 * gives the client detailed processing information including the flag
 * 'isCompleted' which can be triggered by any ImixsAIToolCallEvent handler.
 */
public class ToolCallResult {
    private final boolean wasToolCall;
    private final boolean taskComplete;
    private final String resultValue;

    public ToolCallResult(boolean wasToolCall, boolean taskComplete, String resultValue) {
        this.wasToolCall = wasToolCall;
        this.taskComplete = taskComplete;
        this.resultValue = resultValue;
    }

    public boolean wasToolCall() {
        return wasToolCall;
    }

    public boolean isTaskComplete() {
        return taskComplete;
    }

    public String getResultValue() {
        return resultValue;
    }
}
