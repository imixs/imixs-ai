/****************************************************************************
 * Copyright (c) 2022-2025 Imixs Software Solutions GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
 ****************************************************************************/
package org.imixs.ai.agent.handler;

import java.io.Serializable;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.imixs.ai.ImixsAIContextHandler;
import org.imixs.ai.tools.ImixsAIToolCallEvent;
import org.imixs.ai.tools.ToolCallHandler;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.QueryException;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

/**
 * Handles the "find_workitem" tool call.
 *
 * Searches for workitems by a generic set of index field/value criteria and
 * returns a list of matches with their $uniqueid and $workflowsummary. This is
 * a read-only lookup - it does not modify the current workitem. Use
 * "link_workitem" instead if the goal is to link a match to the current
 * workitem.
 */
@Named
public class ToolCallHandlerFindWorkitem implements ToolCallHandler, Serializable {

    private static final long serialVersionUID = 1L;
    public static final String TOOL_FIND_WORKITEM = "find_workitem";

    private static final int MAX_RESULT_COUNT = 20;
    private static final Logger logger = Logger.getLogger(ToolCallHandlerFindWorkitem.class.getName());

    @Inject
    WorkitemSearchService workitemSearchService;

    @Override
    public String getToolName() {
        return TOOL_FIND_WORKITEM;
    }

    /**
     * This method registers the ToolCall handler
     * 
     * @param event
     */
    @Override
    public void register(ImixsAIContextHandler contextHandler) {
        contextHandler.addFunction(
                TOOL_FIND_WORKITEM,
                "Searches for workitems by a set of index field/value criteria, combined with AND. "
                        + "Returns a list of matching workitems with their $uniqueid and $workflowsummary "
                        + "(max " + MAX_RESULT_COUNT + " results). This is a read-only lookup - it does not "
                        + "modify the current workitem. Which index field names are available and what they "
                        + "mean is described in the current task instructions. Use link_workitem instead if "
                        + "the goal is to link a match to the current workitem.",
                """
                        {
                            "type": "object",
                            "properties": {
                                "criteria": {
                                    "type": "object",
                                    "description": "Map of index field name to search value, combined with AND. Example: {\\"$workflowgroup\\": \\"contract\\", \\"id\\": \\"M-AH-4524\\"}",
                                    "additionalProperties": {
                                        "type": "string"
                                    }
                                }
                            },
                            "required": ["criteria"]
                        }
                        """);
    }

    @Override
    public void handle(ImixsAIToolCallEvent event) {
        if (!TOOL_FIND_WORKITEM.equals(event.getToolName())) {
            return;
        }

        JsonObject criteria = event.getArguments().getJsonObject("criteria");
        if (criteria == null || criteria.isEmpty()) {
            event.setError("Missing or empty 'criteria' argument!");
            return;
        }

        try {
            List<ItemCollection> result = workitemSearchService.findWorkitems(criteria, MAX_RESULT_COUNT);

            JsonArrayBuilder arrayBuilder = Json.createArrayBuilder();
            for (ItemCollection workitem : result) {
                JsonObjectBuilder entry = Json.createObjectBuilder()
                        .add("uniqueid", workitem.getUniqueID())
                        .add("workflowsummary", workitem.getItemValueString("$workflowsummary"));
                arrayBuilder.add(entry);
            }
            String resultJson = arrayBuilder.build().toString();

            logger.info("│   └── ✅ find_workitem returned " + result.size() + " workitem(s)");
            event.setResultValue(resultJson);
            event.setToolMessage(resultJson);

        } catch (QueryException e) {
            logger.log(Level.WARNING, "│   └── ⚠️ find_workitem failed: " + e.getMessage());
            event.setError(e.getMessage());
        }
    }
}