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
import org.imixs.ai.tools.ImixsAIToolRegistrationEvent;
import org.imixs.ai.tools.ToolCallHandler;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.QueryException;

import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.interceptor.Interceptor;
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
 * The uniqueid never has to be reproduced by the LLM - the search and the
 * linking both happen server-side in a single call, avoiding transcription
 * errors when passing a uniqueid from a prior find_workitem result into a
 * separate tool call.
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

    public void onToolRegistration(
            @Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) ImixsAIToolRegistrationEvent event) {
        event.addFunction(
                TOOL_LINK_WORKITEM,
                "Searches for workitems by a set of index field/value criteria (same mechanism as "
                        + "find_workitem) and links every match directly to the current workitem "
                        + "(max " + MAX_LINK_COUNT + " matches). Use this instead of find_workitem when the "
                        + "goal is to actually create the link, not just look at candidates. Which index "
                        + "field names are available and what they mean is described in the current task "
                        + "instructions.",
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
    public void handle(ImixsAIToolCallEvent event) {

        JsonObject criteria = event.getArguments().getJsonObject("criteria");
        if (criteria == null || criteria.isEmpty()) {
            event.setError("Missing or empty 'criteria' argument!");
            return;
        }

        String refField = event.getArguments().containsKey("refField")
                ? event.getArguments().getString("refField")
                : null;

        logger.info("├── ToolCallHandlerLinkWorkitem: link_workitem refField='"
                + (refField != null ? refField : "(none)") + "'");

        try {
            List<ItemCollection> matches = workitemSearchService.findWorkitems(criteria, MAX_LINK_COUNT);

            ImixsAIContextHandler contextHandler = event.getContextHandler();
            ItemCollection workitem = contextHandler.getWorkItem();

            JsonArrayBuilder matchesArrayBuilder = Json.createArrayBuilder();
            for (ItemCollection match : matches) {
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
                    .add("linkedCount", matches.size())
                    .add("matches", matchesArrayBuilder)
                    .build().toString();

            logger.info("│   └── ✅ link_workitem: linked " + matches.size() + " workitem(s) to '"
                    + DEFAULT_REF_FIELD + "'"
                    + (refField != null && !DEFAULT_REF_FIELD.equals(refField) ? " and '" + refField + "'" : ""));

            event.setToolMessage(resultJson);

        } catch (QueryException e) {
            logger.log(Level.WARNING, "│   └── ⚠️ link_workitem failed: " + e.getMessage());
            event.setError(e.getMessage());
        }
    }
}