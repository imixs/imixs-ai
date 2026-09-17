package org.imixs.ai.api;

/**
 * The ToolCallResult is used by the OpenAIAPIService to indicate a successful
 * tool call, processed by the method processToolCallResult.
 * <p>
 * Business result values (ImixsAIResultEvent) are still dispatched directly
 * within processToolCallResult for each individual tool call, independent of
 * this class - that dispatch only happens when an agent.result.type is
 * configured. This class additionally carries the raw resultValue set by any
 * tool call in this completion (if any), regardless of resultType, so the
 * calling AIAgentOperator can use it directly - e.g. to write a task_complete
 * summary into a comment field - without depending on the ImixsAIResultEvent
 * mechanism being configured at all. If several tool calls in the same
 * completion set a resultValue, this carries the last one set, in tool call
 * order.
 * <p>
 * This class only signals whether the agent loop may be terminated, plus
 * this one additional piece of data - it does not replace or duplicate the
 * ImixsAIResultEvent dispatch.
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

    /**
     * The resultValue set by the last tool call in this completion that set one
     * (via event.setResultValue(...)), or {@code null} if no tool call in this
     * completion set one. Independent of whether agent.result.type is configured
     * - this is the raw value regardless of ImixsAIResultEvent dispatch.
     */
    public String getResultValue() {
        return resultValue;
    }
}