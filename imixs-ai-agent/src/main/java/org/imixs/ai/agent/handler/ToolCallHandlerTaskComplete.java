package org.imixs.ai.agent.handler;

import java.io.Serializable;
import java.util.logging.Logger;

import org.imixs.ai.tools.ImixsAIToolCallEvent;
import org.imixs.ai.tools.ImixsAIToolRegistrationEvent;
import org.imixs.ai.tools.ToolCallHandler;

import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Named;
import jakarta.interceptor.Interceptor;

/**
 * Handles the "task_complete" tool call. Signals the AIAgentOperator that the
 * agent has finished its task and no further user input is required. The
 * operator evaluates this flag after the loop to decide whether to trigger
 * successEvent instead of nextEvent.
 */
@Named
public class ToolCallHandlerTaskComplete implements ToolCallHandler, Serializable {

    private static final long serialVersionUID = 1L;
    public static final String TOOL_TASK_COMPLETE = "task_complete";

    private static final Logger logger = Logger.getLogger(ToolCallHandlerTaskComplete.class.getName());

    @Override
    public String getToolName() {
        return TOOL_TASK_COMPLETE;
    }

    /**
     * Registers the task_complete function definition during the agent tool
     * registration phase.
     */
    public void onToolRegistration(
            @Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) ImixsAIToolRegistrationEvent event) {
        event.addFunction(
                TOOL_TASK_COMPLETE,
                "Call this when your task is fully completed and no more user input is needed. "
                        + "Do NOT call this if you still have open questions for the user.",
                """
                        {
                            "type": "object",
                            "properties": {
                                "result": {
                                    "type": "string",
                                    "description": "The final result or summary of the completed task"
                                }
                            },
                            "required": ["result"]
                        }
                        """);
    }

    /**
     * Handles the "task_complete" tool call by setting a flag on the agent
     * workitem. The AIAgentOperator evaluates this flag after the loop to trigger
     * successEvent instead of nextEvent.
     */
    @Override
    public void handle(@Observes ImixsAIToolCallEvent event) {

        if (!TOOL_TASK_COMPLETE.equals(event.getToolName())) {
            return;
        }

        String result = event.getArguments().getString("result");
        logger.info("├── ToolCallHandlerTaskComplete: task_complete - result=" + result);

        // Set the completion flag
        logger.info("│   └── ✅ task_complete flag set for agent: "
                + event.getContextHandler().getWorkItem().getUniqueID());

        event.setTaskCompleted(true);
        event.setResultValue(result);
        event.setToolMessage("Task completed");

    }
}