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
import org.imixs.ai.tools.ToolCallFunction;
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
 * <p>
 * Criteria values may reference a field of the current workitem via
 * {@code <item>itemname</item>} instead of being retyped by the LLM - see
 * {@link WorkitemSearchService} for the resolution mechanism and the rationale
 * (avoiding transcription mistakes for values such as $uniqueid that already
 * exist on the current workitem).
 * <p>
 * An optional {@code filter} further narrows the matches for conditions that
 * cannot be expressed as an indexed search criterion (e.g. a business field
 * that is not part of the search index). It is applied per match via
 * {@link WorkitemHelper#matches(ItemCollection, String)}, after the search - a
 * non-matching item is simply left out of the returned list.
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
                        + "Each criteria value can be a literal string, or can reference a field of the "
                        + "CURRENT workitem using <item>itemname</item> syntax instead of retyping its value - e.g. "
                        + "<item>$uniqueid</item> or <item>fine.plate.number</item>. The actual value is then read directly "
                        + "from the current workitem before the search runs. A <item>itemname</item> reference can "
                        + "also be combined with surrounding literal text, e.g. \"INV-<item>project.id</item>\". "
                        + "Always prefer <item>itemname</item> over retyping a value yourself whenever that value "
                        + "already exists as a field on the current workitem - this is especially important "
                        + "for long or unstructured values such as $uniqueid, where retyping risks a "
                        + "transcription mistake that would silently produce a wrong result set. An optional "
                        + "'filter' can further narrow down the matches for special cases not covered by "
                        + "criteria - only use it if the task instructions explicitly give you a filter "
                        + "expression, and pass it through exactly as given; never invent one yourself. "
                        + "Returns a list of matching workitems with their $uniqueid and $workflowsummary "
                        + "(max " + MAX_RESULT_COUNT + " results). This is a read-only lookup - it does not "
                        + "modify the current workitem. Which index field names and filter expressions are "
                        + "available is described in the current task instructions. Use link_workitem "
                        + "instead if the goal is to link a match to the current workitem.",
                """
                        {
                            "type": "object",
                            "properties": {
                                "criteria": {
                                    "type": "object",
                                    "description": "Map of index field name to search value, combined with AND. A value is either a literal, or contains <item>itemname</item> to reference a field of the current workitem instead of retyping its value - always prefer this for identifiers already present on the current workitem, e.g. <item>$uniqueid</item>. Example: {\\"$workflowgroup\\": \\"contract\\", \\"id\\": \\"M-AH-4524\\"} or {\\"$workflowgroup\\": \\"Efforts\\", \\"$workitemref\\": \\"<item>$uniqueid</item>\\"}",
                                    "additionalProperties": {
                                        "type": "string"
                                    }
                                },
                                "filter": {
                                    "type": "string",
                                    "description": "Optional additional condition to narrow down the selected workitems further, applied after criteria. Only use this if the task instructions explicitly provide a filter expression - copy it exactly as given, do not construct or modify it yourself. Example: \\"(service.billable:true)\\""
                                }
                            },
                            "required": ["criteria"]
                        }
                        """);
    }

    @Override
    public void handle(ToolCallFunction _function) {
        if (!TOOL_FIND_WORKITEM.equals(_function.getToolName())) {
            return;
        }

        JsonObject criteria = _function.getArguments().getJsonObject("criteria");
        if (criteria == null || criteria.isEmpty()) {
            _function.setError("Missing or empty 'criteria' argument!");
            return;
        }

        String filter = _function.getArguments().containsKey("filter")
                ? _function.getArguments().getString("filter")
                : null;

        try {
            List<ItemCollection> result = workitemSearchService.findWorkitems(criteria,
                    _function.getContextHandler().getWorkItem(), MAX_RESULT_COUNT, 0);

            JsonArrayBuilder arrayBuilder = Json.createArrayBuilder();
            int matchedCount = 0;
            for (ItemCollection workitem : result) {
                // Filter is applied per raw match, after the search - a
                // non-matching item is simply left out of the returned list.
                if (!WorkitemHelper.matches(workitem, filter)) {
                    continue;
                }
                matchedCount++;
                JsonObjectBuilder entry = Json.createObjectBuilder()
                        .add("uniqueid", workitem.getUniqueID())
                        .add("workflowsummary", workitem.getItemValueString("$workflowsummary"));
                arrayBuilder.add(entry);
            }
            String resultJson = arrayBuilder.build().toString();

            logger.info("│   └── ✅ find_workitem returned " + matchedCount + " workitem(s)"
                    + (matchedCount != result.size() ? " (" + result.size() + " before filter)" : ""));
            _function.setResultValue(resultJson);
            _function.setToolMessage(resultJson);

        } catch (QueryException e) {
            logger.log(Level.WARNING, "│   └── ⚠️ find_workitem failed: " + e.getMessage());
            _function.setError(e.getMessage());
        }
    }
}