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
import java.util.logging.Level;
import java.util.logging.Logger;

import org.imixs.ai.ImixsAIContextHandler;
import org.imixs.ai.tools.ToolCallFunction;
import org.imixs.ai.tools.ToolCallHandler;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.QueryException;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.json.JsonObject;

/**
 * Handles the "aggregate_workitems" tool call.
 * <p>
 * Searches for workitems by a set of index field/value criteria (same mechanism
 * as find_workitem/link_workitem) and computes a single aggregate value over a
 * numeric field of the matches. The relation to the current workitem is not a
 * separate concept - it is expressed like any other criterion, typically as
 * {@code "$workitemref": "<item>$uniqueid</item>"}, resolved server-side by
 * {@link WorkitemSearchService} so the LLM never has to reproduce a uniqueid
 * itself.
 * <p>
 * {@code filter} further narrows the matches for conditions that cannot be
 * expressed as an indexed search criterion (e.g. a business field that is not
 * part of the search index). It is applied per raw item, after the search,
 * without affecting pagination - see
 * {@link AggregateWorkitemsService#aggregate} for details.
 * <p>
 * {@code filter} is a required schema field, even though it is empty in most
 * calls. This is a deliberate choice, not an oversight: a field only ever
 * omitted "when not needed" is - in practice, with several locally hosted
 * models tested - dropped noticeably more often than a field the schema always
 * requires the model to emit (even with an empty value). Marking it required
 * removed an observed, reproducible failure mode where the model silently left
 * the filter out of one of several very similar calls in the same completion.
 * See the tool documentation for the full rationale.
 * <p>
 * This handler is only responsible for parsing arguments and paging through
 * {@code WorkitemSearchService.findWorkitems(...)}. All actual aggregation
 * logic (paging protocol, filtering, numeric extraction, computation,
 * persistence, result JSON) lives in {@link AggregateWorkitemsService} - see
 * there for the rationale of why the computation always runs server-side and
 * never returns row-level data to the LLM.
 */
@Named
public class ToolCallHandlerAggregateWorkitems implements ToolCallHandler, Serializable {

    private static final long serialVersionUID = 1L;
    public static final String TOOL_AGGREGATE_WORKITEMS = "aggregate_workitems";

    private static final Logger logger = Logger.getLogger(ToolCallHandlerAggregateWorkitems.class.getName());

    @Inject
    AggregateWorkitemsService aggregateWorkitemsService;

    @Override
    public String getToolName() {
        return TOOL_AGGREGATE_WORKITEMS;
    }

    @Override
    public void register(ImixsAIContextHandler contextHandler) {
        contextHandler.addFunction(
                TOOL_AGGREGATE_WORKITEMS,
                "Searches for workitems by a set of index field/value criteria "
                        + "and computes a single aggregate value (sum, count, avg, min or max) "
                        + "over a numeric field of the matches. The computation always runs server-side - "
                        + "individual field values of the matched workitems are never returned. Each criteria "
                        + "value can be a literal, or reference a field of the CURRENT workitem using "
                        + "<item>itemname</item> syntax instead of retyping its value, e.g. "
                        + "<item>$uniqueid</item> - always prefer this for identifiers already present on "
                        + "the current workitem. A 'filter' can further narrow down the matches for special "
                        + "cases not covered by criteria - use a non-empty value only if the task "
                        + "instructions explicitly give you a filter expression, and pass it through exactly "
                        + "as given; never invent one yourself. This field is required - pass an empty value "
                        + "when no filter is needed. Which index field names, numeric fields, and filter "
                        + "expressions are available is described in the current task instructions. Do not "
                        + "attempt to compute sums, counts or averages yourself from other tool results - "
                        + "always use this tool for that purpose.",
                """
                        {
                            "type": "object",
                            "properties": {
                                "criteria": {
                                    "type": "object",
                                    "description": "Map of index field name to search value, combined with AND. A value is either a literal, or contains <item>itemname</item> to reference a field of the current workitem instead of retyping its value - always prefer this for identifiers already present on the current workitem, e.g. <item>$uniqueid</item>. Example: {\\"$workflowgroup\\": \\"Efforts\\", \\"$workitemref\\": \\"<item>$uniqueid</item>\\"}",
                                    "additionalProperties": {
                                        "type": "string"
                                    }
                                },
                                "filter": {
                                    "type": "string",
                                    "description": "Additional condition to narrow down the selected workitems further, applied after criteria, or an empty value when no filter is needed. Only use a non-empty value if the task instructions explicitly provide a filter expression - copy it exactly as given, do not construct or modify it yourself. This field must always be present in the call, even when empty. Example: (service.billable:true)"
                                },
                                "aggregateField": {
                                    "type": "string",
                                    "description": "Name of the numeric field to aggregate, e.g. 'service.hours'. Required unless function is 'count'."
                                },
                                "function": {
                                    "type": "string",
                                    "enum": ["sum", "count", "avg", "min", "max"],
                                    "description": "The aggregation function to apply."
                                },
                                "targetField": {
                                    "type": "string",
                                    "description": "Optional name of a field on the current workitem the computed scalar result is written into. If omitted, the result is only returned, not persisted."
                                },
                                "matchIdsField": {
                                    "type": "string",
                                    "description": "Optional name of a field on the current workitem the list of matched $uniqueids is written into, for traceability. Independent of targetField."
                                }
                            },
                            "required": ["criteria", "function", "filter"]
                        }
                        """);
    }

    @Override
    public void handle(ToolCallFunction _function) {
        long l = System.currentTimeMillis();
        if (!TOOL_AGGREGATE_WORKITEMS.equals(_function.getToolName())) {
            return;
        }

        JsonObject criteria = _function.getArguments().getJsonObject("criteria");
        if (criteria == null || criteria.isEmpty()) {
            _function.setError("Missing or empty 'criteria' argument!");
            return;
        }

        String function = _function.getArguments().containsKey("function")
                ? _function.getArguments().getString("function")
                : null;
        String aggregateField = _function.getArguments().containsKey("aggregateField")
                ? _function.getArguments().getString("aggregateField")
                : null;

        String validationError = aggregateWorkitemsService.validateFunction(function, aggregateField);
        if (validationError != null) {
            _function.setError(validationError);
            return;
        }
        function = function.toLowerCase();

        String filter = _function.getArguments().containsKey("filter")
                ? _function.getArguments().getString("filter")
                : null;
        String targetField = _function.getArguments().containsKey("targetField")
                ? _function.getArguments().getString("targetField")
                : null;
        String matchIdsField = _function.getArguments().containsKey("matchIdsField")
                ? _function.getArguments().getString("matchIdsField")
                : null;

        ImixsAIContextHandler contextHandler = _function.getContextHandler();
        ItemCollection workitem = contextHandler.getWorkItem();

        logger.info("├── ToolCallHandlerAggregateWorkitems: function='" + function
                + "' aggregateField='" + (aggregateField != null ? aggregateField : "(none)")
                + "' filter='" + (filter != null ? filter : "(none)") + "'");

        boolean collectMatchIds = matchIdsField != null && !matchIdsField.isBlank();

        try {
            AggregateWorkitemsService.AggregateResult result = aggregateWorkitemsService.aggregate(
                    criteria, workitem, function, aggregateField, filter, collectMatchIds);

            aggregateWorkitemsService.persistResult(workitem, result, targetField, matchIdsField);
            String resultJson = aggregateWorkitemsService.buildResultJson(result, targetField, matchIdsField)
                    .toString();

            logger.info("│   └── ⚙️ aggregate_workitems: " + function + " over " + result.matchCount
                    + " match(es) = " + result.value
                    + (result.skippedCount > 0 ? " (" + result.skippedCount + " skipped)" : ""));

            logger.info("└── ✅ aggregate_workitems completed in " + (System.currentTimeMillis() - l) + "ms");

            _function.setToolMessage(resultJson);

        } catch (QueryException e) {
            logger.log(Level.WARNING, "│   └── ⚠️ aggregate_workitems failed: " + e.getMessage());
            _function.setError(e.getMessage());
        }
    }
}