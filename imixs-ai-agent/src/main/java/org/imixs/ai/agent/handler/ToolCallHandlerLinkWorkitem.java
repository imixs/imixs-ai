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
 * Handles the "link_workitem" tool call.
 *
 * Searches for workitems by a set of index field/value criteria (same mechanism
 * as find_workitem) and links every match directly to the current workitem by
 * appending its $uniqueid to the given reference field. This is the tool-call
 * equivalent of the "link workitem" UX component a user would use manually in a
 * form.
 * <p>
 * The MATCHED workitem's uniqueid never has to be reproduced by the LLM - the
 * search and the linking both happen server-side in a single call, avoiding
 * transcription errors when passing a uniqueid from a prior find_workitem
 * result into a separate tool call. Additionally, a criteria VALUE can itself
 * reference a field of the CURRENT workitem via {@code <item>itemname</item>}
 * instead of being retyped by the LLM - see {@link WorkitemSearchService} for
 * the resolution mechanism. Between the two, no uniqueid - neither the current
 * workitem's nor a matched one's - ever has to pass through the LLM as
 * free-form text.
 * <p>
 * An optional {@code filter} further narrows the matches for conditions that
 * cannot be expressed as an indexed search criterion (e.g. a business field
 * that is not part of the search index). It is applied per raw match via
 * {@link WorkitemHelper#matches(ItemCollection, String)}, after the search and
 * before linking - a non-matching item is simply never linked.
 * <p>
 * The reference field can hold more than one uniqueid - if the search returns
 * several matches, all of them are linked, and the UI's link list component
 * lets a manager review and adjust the list afterwards.
 */
@Named
public class ToolCallHandlerLinkWorkitem implements ToolCallHandler, Serializable {

    private static final long serialVersionUID = 1L;
    public static final String TOOL_LINK_WORKITEM = "link_workitem";
    public static final String DEFAULT_REF_FIELD = "$workitemref";

    // Maximum number of matches that will be linked in a single call
    private static final int MAX_LINK_COUNT = 10;

    private static final Logger logger = Logger.getLogger(ToolCallHandlerLinkWorkitem.class.getName());

    @Inject
    WorkitemSearchService workitemSearchService;

    @Override
    public String getToolName() {
        return TOOL_LINK_WORKITEM;
    }

    @Override
    public void register(ImixsAIContextHandler contextHandler) {
        contextHandler.addFunction(
                TOOL_LINK_WORKITEM,
                "Searches for workitems by a set of index field/value criteria (same mechanism as "
                        + "find_workitem) and links every match directly to the current workitem "
                        + "(max " + MAX_LINK_COUNT + " matches). Use this instead of find_workitem when the "
                        + "goal is to actually create the link, not just look at candidates. Each criteria "
                        + "value can be a literal string, or can reference a field of the CURRENT workitem "
                        + "using <item>itemname</item> syntax instead of retyping its value - e.g. <item>$uniqueid</item> or "
                        + "<item>fine.plate.number</item>, optionally combined with surrounding literal text such as "
                        + "\"INV-<item>project.id</item>\". Always prefer <item>itemname</item> over retyping a value yourself "
                        + "whenever that value already exists as a field on the current workitem - this is "
                        + "especially important for long or unstructured values such as $uniqueid, where "
                        + "retyping risks a transcription mistake that would silently produce a wrong result "
                        + "set. An optional 'filter' can further narrow down the matches for special cases "
                        + "not covered by criteria - only use it if the task instructions explicitly give you "
                        + "a filter expression, and pass it through exactly as given; never invent one "
                        + "yourself. Which index field names and filter expressions are available is "
                        + "described in the current task instructions.",
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
                                },
                                "refField": {
                                    "type": "string",
                                    "description": "Optional name of an additional workitem field to store the link(s) in. The link is always also stored in '$workitemref' regardless of this parameter."
                                }
                            },
                            "required": ["criteria"]
                        }
                        """);
    }

    @Override
    public void handle(ToolCallFunction _function) {

        JsonObject criteria = _function.getArguments().getJsonObject("criteria");
        if (criteria == null || criteria.isEmpty()) {
            _function.setError("Missing or empty 'criteria' argument!");
            return;
        }

        String filter = _function.getArguments().containsKey("filter")
                ? _function.getArguments().getString("filter")
                : null;
        String refField = _function.getArguments().containsKey("refField")
                ? _function.getArguments().getString("refField")
                : null;

        logger.info("├── ToolCallHandlerLinkWorkitem: link_workitem refField='"
                + (refField != null ? refField : "(none)") + "'");

        try {
            List<ItemCollection> matches = workitemSearchService.findWorkitems(criteria,
                    _function.getContextHandler().getWorkItem(), MAX_LINK_COUNT, 0);

            ImixsAIContextHandler contextHandler = _function.getContextHandler();
            ItemCollection workitem = contextHandler.getWorkItem();

            JsonArrayBuilder matchesArrayBuilder = Json.createArrayBuilder();
            int linkedCount = 0;
            for (ItemCollection match : matches) {
                // Filter is applied per raw match, after the search and before
                // linking - a non-matching item is simply never linked.
                if (!WorkitemHelper.matches(match, filter)) {
                    continue;
                }
                linkedCount++;

                // Always link via the default reference field
                workitem.appendItemValueUnique(DEFAULT_REF_FIELD, match.getUniqueID());

                // Additionally link via the custom field, if given and different
                if (refField != null && !refField.isBlank() && !DEFAULT_REF_FIELD.equals(refField)) {
                    workitem.appendItemValueUnique(refField, match.getUniqueID());
                }

                JsonObjectBuilder entry = Json.createObjectBuilder()
                        .add("uniqueid", match.getUniqueID())
                        .add("workflowsummary", match.getItemValueString("$workflowsummary"));
                matchesArrayBuilder.add(entry);
            }

            String resultJson = Json.createObjectBuilder()
                    .add("linkedCount", linkedCount)
                    .add("matches", matchesArrayBuilder)
                    .build().toString();

            logger.info("│   └── ✅ link_workitem: linked " + linkedCount + " workitem(s) to '"
                    + DEFAULT_REF_FIELD + "'"
                    + (refField != null && !DEFAULT_REF_FIELD.equals(refField) ? " and '" + refField + "'" : "")
                    + (linkedCount != matches.size() ? " (" + matches.size() + " before filter)" : ""));

            _function.setToolMessage(resultJson);

        } catch (QueryException e) {
            logger.log(Level.WARNING, "│   └── ⚠️ link_workitem failed: " + e.getMessage());
            _function.setError(e.getMessage());
        }
    }
}