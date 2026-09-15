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
import org.imixs.ai.tools.ImixsAIToolCallEvent;
import org.imixs.ai.tools.ToolCallHandler;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.QueryException;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.json.JsonObject;

/**
 * Handles the "aggregate_workitems" tool call.
 * <p>
 * Searches for workitems by a set of index field/value criteria (same
 * mechanism as find_workitem/link_workitem) and computes a single aggregate
 * value over a numeric field of the matches. The relation to the current
 * workitem is not a separate concept - it is expressed like any other
 * criterion, typically as {@code "$workitemref": "{{$uniqueid}}"}, resolved
 * server-side by {@link WorkitemSearchService} so the LLM never has to
 * reproduce a uniqueid itself.
 * <p>
 * An optional {@code filter} further narrows the matches for conditions that
 * cannot be expressed as an indexed search criterion (e.g. a business field
 * that is not part of the search index). It is applied per raw item, after
 * the search, without affecting pagination - see
 * {@link AggregateWorkitemsService#aggregate} for details.
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
    WorkitemSearchService workitemSearchService;

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
                "Searches for workitems by a set of index field/value criteria (same mechanism as "
                        + "find_workitem) and computes a single aggregate value (sum, count, avg, min or max) "
                        + "over a numeric field of the matches. The computation always runs server-side - "
                        + "individual field values of the matched workitems are never returned. Each criteria "
                        + "value can be a literal, or reference a field of the CURRENT workitem using "
                        + "{{itemname}} syntax instead of retyping its value, e.g. {{$uniqueid}} - always "
                        + "prefer this for identifiers already present on the current workitem. An optional "
                        + "'filter' can further narrow down the matches for special cases not covered by "
                        + "criteria - only use it if the task instructions explicitly give you a filter "
                        + "expression, and pass it through exactly as given; never invent one yourself. Which "
                        + "index field names, numeric fields, and filter expressions are available is "
                        + "described in the current task instructions. Do not attempt to compute sums, counts "
                        + "or averages yourself from other tool results - always use this tool for that "
                        + "purpose.",
                """
                        {
                            "type": "object",
                            "properties": {
                                "criteria": {
                                    "type": "object",
                                    "description": "Map of index field name to search value, combined with AND. A value is either a literal, or contains {{itemname}} to reference a field of the current workitem instead of retyping its value - always prefer this for identifiers already present on the current workitem, e.g. {{$uniqueid}}. Example: {\\"$workflowgroup\\": \\"Efforts\\", \\"$workitemref\\": \\"{{$uniqueid}}\\"}",
                                    "additionalProperties": {
                                        "type": "string"
                                    }
                                },
                                "filter": {
                                    "type": "string",
                                    "description": "Optional additional condition to narrow down the selected workitems further, applied after criteria. Only use this if the task instructions explicitly provide a filter expression - copy it exactly as given, do not construct or modify it yourself. Example: \\"(service.billable:true)\\""
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
                            "required": ["criteria", "function"]
                        }
                        """);
    }

    @Override
    public void handle(ImixsAIToolCallEvent event) {
        if (!TOOL_AGGREGATE_WORKITEMS.equals(event.getToolName())) {
            return;
        }

        JsonObject criteria = event.getArguments().getJsonObject("criteria");
        if (criteria == null || criteria.isEmpty()) {
            event.setError("Missing or empty 'criteria' argument!");
            return;
        }

        String function = event.getArguments().containsKey("function")
                ? event.getArguments().getString("function")
                : null;
        String aggregateField = event.getArguments().containsKey("aggregateField")
                ? event.getArguments().getString("aggregateField")
                : null;

        String validationError = aggregateWorkitemsService.validateFunction(function, aggregateField);
        if (validationError != null) {
            event.setError(validationError);
            return;
        }
        function = function.toLowerCase();

        String filter = event.getArguments().containsKey("filter")
                ? event.getArguments().getString("filter")
                : null;
        String targetField = event.getArguments().containsKey("targetField")
                ? event.getArguments().getString("targetField")
                : null;
        String matchIdsField = event.getArguments().containsKey("matchIdsField")
                ? event.getArguments().getString("matchIdsField")
                : null;

        ImixsAIContextHandler contextHandler = event.getContextHandler();
        ItemCollection workitem = contextHandler.getWorkItem();

        logger.info("├── ToolCallHandlerAggregateWorkitems: function='" + function
                + "' aggregateField='" + (aggregateField != null ? aggregateField : "(none)")
                + "' filter='" + (filter != null ? filter : "(none)") + "'");

        boolean collectMatchIds = matchIdsField != null && !matchIdsField.isBlank();

        try {
            AggregateWorkitemsService.AggregateResult result = aggregateWorkitemsService.aggregate(
                    pageIndex -> workitemSearchService.findWorkitems(
                            criteria, workitem, AggregateWorkitemsService.PAGE_SIZE, pageIndex),
                    function, aggregateField, filter, collectMatchIds);

            aggregateWorkitemsService.persistResult(workitem, result, targetField, matchIdsField);
            String resultJson = aggregateWorkitemsService.buildResultJson(result, targetField, matchIdsField)
                    .toString();

            logger.info("│   └── ✅ aggregate_workitems: " + function + " over " + result.matchCount
                    + " match(es) = " + result.value
                    + (result.skippedCount > 0 ? " (" + result.skippedCount + " skipped)" : ""));

            event.setToolMessage(resultJson);

        } catch (QueryException e) {
            logger.log(Level.WARNING, "│   └── ⚠️ aggregate_workitems failed: " + e.getMessage());
            event.setError(e.getMessage());
        }
    }
}