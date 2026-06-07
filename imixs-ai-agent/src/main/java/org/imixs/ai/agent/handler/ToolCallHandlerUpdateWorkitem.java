package org.imixs.ai.agent.handler;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.imixs.ai.agent.AIAgentCache;
import org.imixs.ai.tools.ImixsAIToolCallEvent;
import org.imixs.ai.tools.ImixsAIToolRegistrationEvent;
import org.imixs.workflow.ItemCollection;

import jakarta.annotation.Priority;
import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RunAs;
import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * Handles the "update_workitem" tool call. Writes field values into a cached
 * workitem. No database write or workflow processing occurs at this stage.
 */
@DeclareRoles({ "org.imixs.ACCESSLEVEL.MANAGERACCESS" })
@RunAs("org.imixs.ACCESSLEVEL.MANAGERACCESS")
@Stateless
@LocalBean
public class ToolCallHandlerUpdateWorkitem {

    public static final String TOOL_UPDATE_WORKITEM = "update_workitem";

    private static final Logger logger = Logger.getLogger(ToolCallHandlerUpdateWorkitem.class.getName());

    @Inject
    AIAgentCache aiAgentCache;

    /**
     * Registers the update_workitem function definition during the agent tool
     * registration phase.
     */
    public void onToolRegistration(
            @Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) ImixsAIToolRegistrationEvent event) {
        event.addFunction(
                TOOL_UPDATE_WORKITEM,
                "Writes field values into a cached workitem. Call this after create_workitem and before submit_workitem.",
                """
                        {
                            "type": "object",
                            "properties": {
                                "$uniqueid": {
                                    "type": "string",
                                    "description": "The unique ID returned by create_workitem"
                                },
                                "data": {
                                    "type": "object",
                                    "description": "Key-value pairs of field names and their values",
                                    "additionalProperties": {
                                        "type": "string"
                                    }
                                }
                            },
                            "required": ["$uniqueid", "data"]
                        }
                        """);
    }

    /**
     * Handles the "update_workitem" tool call by writing the provided field values
     * into the cached workitem. No DB write occurs.
     */
    public void onToolCall(@Observes ImixsAIToolCallEvent event) {

        if (!TOOL_UPDATE_WORKITEM.equals(event.getToolName())) {
            return;
        }

        String uniqueId = event.getArguments().getString("$uniqueid");
        JsonObject data = event.getArguments().getJsonObject("data");

        logger.info("├── ToolCallHandlerUpdateWorkitem: update_workitem uniqueId=" + uniqueId);

        try {
            // Resolve the agent workitem ID — used as the cache bucket key
            String agentWorkitemId = event.getContextHandler().getWorkItem().getUniqueID();

            ItemCollection workitem = aiAgentCache.getOperatorWorkitem(agentWorkitemId, uniqueId);
            if (workitem == null) {
                event.setError("Workitem not found in cache: " + uniqueId);
                return;
            }

            // Write all provided field values into the cached workitem
            if (data != null) {
                for (String fieldName : data.keySet()) {
                    JsonValue jsonValue = data.get(fieldName);
                    String value = (jsonValue instanceof JsonString)
                            ? ((JsonString) jsonValue).getString()
                            : jsonValue.toString();
                    workitem.setItemValue(fieldName, value);
                    logger.log(Level.INFO, "│   ├── set {0}={1}", new Object[] { fieldName, value });
                }
            }

            logger.info("│   └── ✅ Workitem updated in cache: " + uniqueId
                    + " (agent=" + agentWorkitemId + ")");
            event.setResult("Workitem updated: " + uniqueId);

        } catch (Exception e) {
            logger.log(Level.WARNING, "│   └── ⚠️ update_workitem failed: " + e.getMessage());
            event.setError(e.getMessage());
        }
    }
}