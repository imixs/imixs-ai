package org.imixs.ai.agent.handler;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.imixs.ai.agent.AIAgentCache;
import org.imixs.ai.tools.ImixsAIToolCallEvent;
import org.imixs.ai.tools.ImixsAIToolRegistrationEvent;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.WorkflowKernel;

import jakarta.annotation.Priority;
import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RunAs;
import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

/**
 * Handles the "create_workitem" tool call. Creates a new workitem in the
 * in-memory WorkitemCache only — no database write, no workflow processing. The
 * workitem is identified by a generated unique ID returned to the LLM. The
 * workitem is stored under the agent workitem ID so the AIAgentOperator can
 * check for pending workitems without relying on persisted workitem refs.
 */
@DeclareRoles({ "org.imixs.ACCESSLEVEL.MANAGERACCESS" })
@RunAs("org.imixs.ACCESSLEVEL.MANAGERACCESS")
@Stateless
@LocalBean
public class ToolCallHandlerCreateWorkitem {

    public static final String TOOL_CREATE_WORKITEM = "create_workitem";

    private static final Logger logger = Logger.getLogger(ToolCallHandlerCreateWorkitem.class.getName());

    @Inject
    AIAgentCache aiAgentCache;

    /**
     * Registers the create_workitem function definition during the agent tool
     * registration phase.
     */
    public void onToolRegistration(
            @Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) ImixsAIToolRegistrationEvent event) {
        event.addFunction(
                TOOL_CREATE_WORKITEM,
                "Creates a new workflow workitem in memory. Returns a $uniqueid for subsequent update and submit calls.",
                """
                        {
                            "type": "object",
                            "properties": {
                                "$modelversion": {
                                    "type": "string",
                                    "description": "The model version of the process"
                                },
                                "$taskid": {
                                    "type": "integer",
                                    "description": "The task ID of the initial task"
                                },
                                "process.ref": {
                                    "type": "string",
                                    "description": "The unique ID of the process document"
                                }
                            },
                            "required": ["$modelversion", "$taskid", "process.ref"]
                        }
                        """);
    }

    /**
     * Handles the "create_workitem" tool call by storing a new workitem in the
     * in-memory cache under the agent workitem ID. No database write occurs at this
     * stage.
     */
    public void onToolCall(@Observes ImixsAIToolCallEvent event) {

        if (!TOOL_CREATE_WORKITEM.equals(event.getToolName())) {
            return;
        }

        String modelVersion = event.getArguments().getString("$modelversion");
        int taskId = event.getArguments().getInt("$taskid");
        String processRef = event.getArguments().getString("process.ref");

        logger.info("├── ToolCallHandlerCreateWorkitem: create_workitem"
                + " model=" + modelVersion
                + " taskId=" + taskId
                + " processRef=" + processRef);

        try {
            // Resolve the agent workitem ID — all cached workitems are grouped under it
            String agentWorkitemId = event.getContextHandler().getWorkItem().getUniqueID();

            // Build the workitem in memory only — no DB write yet
            ItemCollection workitem = new ItemCollection();
            workitem.setItemValue(WorkflowKernel.UNIQUEID, WorkflowKernel.generateUniqueID());
            workitem.model(modelVersion).task(taskId);
            workitem.setItemValue("$processRef", processRef);

            aiAgentCache.putOperatorWorkitem(event.getContextHandler().getWorkItem(), workitem);

            logger.info("│   └── ✅ Workitem created in cache: " + workitem.getUniqueID()
                    + " (agent=" + agentWorkitemId + ")");
            event.setResultValue(workitem.getUniqueID());
            event.setToolMessage("Workitem created: " + workitem.getUniqueID());

        } catch (Exception e) {
            logger.log(Level.WARNING, "│   └── ⚠️ create_workitem failed: " + e.getMessage());
            event.setError(e.getMessage());
        }
    }
}