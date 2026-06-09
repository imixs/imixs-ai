package org.imixs.ai.agent.handler;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.Config;
import org.imixs.ai.agent.AIAgentCache;
import org.imixs.ai.agent.AIAgentOperator;
import org.imixs.ai.tools.ImixsAIToolCallEvent;
import org.imixs.ai.tools.ImixsAIToolRegistrationEvent;
import org.imixs.archive.core.SnapshotService;
import org.imixs.workflow.FileData;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.WorkflowService;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RunAs;
import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Handles the "submit_workitem" tool call. Loads the workitem from the
 * in-memory cache, sets the workflow event and triggers
 * workflowService.processWorkItem(). The single DB write in the whole flow.
 * Removes the entry from the cache after successful processing.
 * <p>
 * The handler also hand over the file data from the agent workitem to the
 * submitted workitem, if available.
 * 
 */
@DeclareRoles({ "org.imixs.ACCESSLEVEL.MANAGERACCESS" })
@RunAs("org.imixs.ACCESSLEVEL.MANAGERACCESS")
@Stateless
@LocalBean
public class ToolCallHandlerSubmitWorkitem {

    public static final String TOOL_SUBMIT_WORKITEM = "submit_workitem";

    private static final Logger logger = Logger.getLogger(ToolCallHandlerSubmitWorkitem.class.getName());

    @Inject
    AIAgentCache aiAgentCache;

    @Inject
    WorkflowService workflowService;

    @Inject
    SnapshotService snapshotService;

    @Inject
    private Config config;

    /**
     * Registers the submit_workitem function definition during the agent tool
     * registration phase.
     */
    public void onToolRegistration(@Observes ImixsAIToolRegistrationEvent event) {
        event.addFunction(
                TOOL_SUBMIT_WORKITEM,
                "Submits a cached workitem into the workflow engine. Call this only when all required fields are filled.",
                """
                        {
                            "type": "object",
                            "properties": {
                                "$uniqueid": {
                                    "type": "string",
                                    "description": "The unique ID returned by create_workitem"
                                },
                                "$eventid": {
                                    "type": "integer",
                                    "description": "The workflow event ID to trigger (e.g. the Submit event)"
                                }
                            },
                            "required": ["$uniqueid", "$eventid"]
                        }
                        """);
    }

    /**
     * Handles the "submit_workitem" tool call. This is the only point where
     * workflowService.processWorkItem() is called — triggering the actual DB write
     * and workflow state transition.
     */
    public void onToolCall(@Observes ImixsAIToolCallEvent event) {

        if (!TOOL_SUBMIT_WORKITEM.equals(event.getToolName())) {
            return;
        }

        String uniqueId = event.getArguments().getString("$uniqueid");
        int eventId = event.getArguments().getInt("$eventid");

        logger.info("├── ToolCallHandlerSubmitWorkitem: submit_workitem"
                + " uniqueId=" + uniqueId
                + " eventId=" + eventId);

        try {
            // Resolve the agent workitem ID — used as the cache bucket key
            ItemCollection agentWorkitem = event.getContextHandler().getWorkItem();

            ItemCollection workitem = aiAgentCache.getOperatorWorkitem(agentWorkitem.getUniqueID(), uniqueId);
            if (workitem == null) {
                event.setError("Workitem not found in cache: " + uniqueId);
                return;
            }

            // hand over fileData...
            List<FileData> fileDataSet = event.getContextHandler().getWorkItem().getFileData();
            ItemCollection snapshotData = snapshotService.findSnapshot(event.getContextHandler().getWorkItem());
            if (snapshotData != null) {
                // fetch fileData form snapshot...
                fileDataSet = snapshotData.getFileData();
            }
            for (FileData fd : fileDataSet) {
                workitem.addFileData(fd);
            }

            // Set the workflow event and trigger the single DB write
            workitem.event(eventId);
            workitem.setItemValue("$workitemref", agentWorkitem.getUniqueID());
            workitem = workflowService.processWorkItem(workitem);

            String persistedId = workitem.getUniqueID();

            // Remove from cache — the workitem now lives in the DB
            aiAgentCache.removeOperatorWorkitem(event.getContextHandler().getWorkItem(), workitem.getUniqueID());

            // Set ref on the agent workitem for UI linking
            agentWorkitem.appendItemValueUnique(AIAgentOperator.ITEM_AGENT_WORKITEM_REF, workitem.getUniqueID());

            // Build the tool result including the full workitem URL so the LLM
            // can embed it as a Markdown link in its confirmation message.
            String applicationURL = config.getValue("application.url", String.class);
            String result = "Workitem submitted: " + persistedId
                    + "\nWorkitem URL: " + applicationURL
                    + "pages/workitems/workitem.xhtml?id=" + persistedId;

            logger.info("│   └── ✅ Workitem submitted: " + persistedId
                    + " (agent=" + agentWorkitem.getUniqueID() + ")");
            event.setResult(result);

        } catch (Exception e) {
            logger.log(Level.WARNING, "│   └── ⚠️ submit_workitem failed: " + e.getMessage());
            event.setError(e.getMessage());
        }
    }
}