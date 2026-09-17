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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.QueryException;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

/**
 * Aggregation logic backing the "aggregate_workitems" tool call.
 * <p>
 * Pages through {@link WorkitemSearchService#findWorkitems} for the given
 * criteria, applies an optional post-search filter, and computes a single
 * aggregate value (sum, count, avg, min, max) over a numeric field of the
 * matches. The computation is always performed server-side in Java -
 * individual field values of the matched workitems never leave this class,
 * only the resulting scalar and bookkeeping metadata are exposed to the
 * calling handler (and, from there, to the LLM).
 */
@Stateless
@LocalBean
public class AggregateWorkitemsService {

    // Batch size used to page through the full result set, independent of any
    // result-set-size limit used elsewhere (find_workitem/link_workitem cap the
    // result itself, because it is shown to a user - an aggregate must always
    // reflect the FULL result set, so it pages instead of capping).
    public static final int PAGE_SIZE = 500;

    // Maximum number of $uniqueids persisted into a matchIdsField. The aggregate
    // scalar itself is always computed over ALL matches, independent of this cap.
    public static final int MAX_MATCH_IDS_STORED = 500;

    private static final int AGGREGATE_SCALE = 4;
    private static final List<String> VALID_FUNCTIONS = List.of("sum", "count", "avg", "min", "max");

    private static final Logger logger = Logger.getLogger(AggregateWorkitemsService.class.getName());

    @Inject
    WorkitemSearchService workitemSearchService;

    /**
     * Result of an aggregation run. Immutable value holder - callers persist and
     * report it further as needed.
     */
    public static final class AggregateResult {
        public final String function;
        public final String aggregateField;
        public final long matchCount;
        public final int skippedCount;
        public final BigDecimal value;
        public final List<String> matchIds;
        public final boolean truncated;

        AggregateResult(String function, String aggregateField, long matchCount, int skippedCount,
                BigDecimal value, List<String> matchIds, boolean truncated) {
            this.function = function;
            this.aggregateField = aggregateField;
            this.matchCount = matchCount;
            this.skippedCount = skippedCount;
            this.value = value;
            this.matchIds = matchIds;
            this.truncated = truncated;
        }
    }

    /**
     * Validates the function/aggregateField combination.
     *
     * @return an error message describing the problem, or {@code null} if the
     *         arguments are valid.
     */
    public String validateFunction(String function, String aggregateField) {
        if (function == null || function.isBlank()) {
            return "Missing 'function' argument!";
        }
        if (!VALID_FUNCTIONS.contains(function.toLowerCase())) {
            return "Invalid 'function' value '" + function + "' - expected one of sum, count, avg, min, max.";
        }
        if (!"count".equals(function.toLowerCase()) && (aggregateField == null || aggregateField.isBlank())) {
            return "Missing 'aggregateField' argument - required unless function is 'count'.";
        }
        return null;
    }

    /**
     * Pages through all workitems matching {@code criteria} for
     * {@code currentWorkitem} and computes the aggregate. Paging continues until
     * a raw page smaller than {@link #PAGE_SIZE} is returned by
     * {@link WorkitemSearchService#findWorkitems}. Pagination is always governed
     * by that RAW page size - filtering (see below) never affects it, so a page
     * that is fully consumed by the filter still correctly continues to the next
     * page.
     * <p>
     * If {@code filter} is given, each raw match is additionally tested against
     * it via {@link WorkitemHelper#matches(ItemCollection, String)} before being
     * counted, aggregated, or added to the match id list - a non-matching item is
     * skipped entirely, exactly as if it had never been part of the result set.
     * A blank or {@code null} filter matches everything, so passing it through
     * unconditionally is safe and needs no separate null check.
     *
     * @param criteria        search criteria, as passed to
     *                        {@link WorkitemSearchService#findWorkitems}
     * @param currentWorkitem the workitem {@code <item>itemname</item>}
     *                        references in criteria are resolved against
     * @param function        one of sum, count, avg, min, max (case-insensitive)
     * @param aggregateField  numeric field to aggregate; ignored for "count"
     * @param filter          optional WorkitemHelper filter expression applied
     *                        per item, e.g. {@code "(service.billable:true)"}
     * @param collectMatchIds whether to collect matched $uniqueids at all
     */
    public AggregateResult aggregate(JsonObject criteria, ItemCollection currentWorkitem, String function,
            String aggregateField, String filter, boolean collectMatchIds) throws QueryException {

        function = function.toLowerCase();
        long matchCount = 0;
        int skippedCount = 0;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal min = null;
        BigDecimal max = null;
        List<String> matchIds = new ArrayList<>();

        int pageIndex = 0;
        List<ItemCollection> page;
        do {
            page = workitemSearchService.findWorkitems(criteria, currentWorkitem, PAGE_SIZE, pageIndex);

            for (ItemCollection match : page) {

                // Filter is applied per raw item, before any counting. The raw
                // page size (checked below in the while condition) is untouched
                // by this, so pagination remains correct regardless of how many
                // items in a page pass the filter.
                if (!WorkitemHelper.matches(match, filter)) {
                    continue;
                }

                matchCount++;

                if (collectMatchIds && matchIds.size() < MAX_MATCH_IDS_STORED) {
                    matchIds.add(match.getUniqueID());
                }

                if ("count".equals(function)) {
                    continue;
                }

                BigDecimal fieldValue = extractNumericValue(match, aggregateField);
                if (fieldValue == null) {
                    logger.warning("│   ├── ⚠️ aggregate: workitem " + match.getUniqueID()
                            + " has no numeric value for field '" + aggregateField + "', skipping");
                    skippedCount++;
                    continue;
                }

                sum = sum.add(fieldValue);
                if (min == null || fieldValue.compareTo(min) < 0) {
                    min = fieldValue;
                }
                if (max == null || fieldValue.compareTo(max) > 0) {
                    max = fieldValue;
                }
            }
            pageIndex++;
        } while (page.size() == PAGE_SIZE);

        long consideredCount = matchCount - skippedCount;
        BigDecimal value;
        switch (function) {
            case "count":
                value = BigDecimal.valueOf(matchCount);
                break;
            case "sum":
                value = sum.setScale(AGGREGATE_SCALE, RoundingMode.HALF_UP);
                break;
            case "avg":
                value = consideredCount == 0
                        ? BigDecimal.ZERO
                        : sum.divide(BigDecimal.valueOf(consideredCount), AGGREGATE_SCALE, RoundingMode.HALF_UP);
                break;
            case "min":
                value = (min != null ? min : BigDecimal.ZERO).setScale(AGGREGATE_SCALE, RoundingMode.HALF_UP);
                break;
            case "max":
                value = (max != null ? max : BigDecimal.ZERO).setScale(AGGREGATE_SCALE, RoundingMode.HALF_UP);
                break;
            default:
                // unreachable - validated by validateFunction() before this is called
                value = BigDecimal.ZERO;
        }

        boolean truncated = collectMatchIds && matchCount > MAX_MATCH_IDS_STORED;

        return new AggregateResult(function, aggregateField, matchCount, skippedCount, value, matchIds, truncated);
    }

    /**
     * Persists the scalar result and/or the match id list into the given
     * workitem. Both fields are always overwritten, never accumulated, since an
     * aggregate represents the current state of a re-runnable report - not a
     * user-curated list like link_workitem's reference field.
     */
    public void persistResult(ItemCollection workitem, AggregateResult result, String targetField,
            String matchIdsField) {
        if (targetField != null && !targetField.isBlank()) {
            workitem.setItemValue(targetField, result.value.doubleValue());
        }
        if (matchIdsField != null && !matchIdsField.isBlank()) {
            workitem.setItemValue(matchIdsField, result.matchIds);
        }
    }

    /**
     * Builds the compact JSON result returned to the LLM as the tool message.
     * Never includes row-level field values of the matched workitems - only the
     * computed scalar and bookkeeping metadata.
     */
    public JsonObject buildResultJson(AggregateResult result, String targetField, String matchIdsField) {
        JsonObjectBuilder builder = Json.createObjectBuilder()
                .add("function", result.function)
                .add("matchCount", result.matchCount)
                .add("value", result.value);
        if (result.aggregateField != null) {
            builder.add("aggregateField", result.aggregateField);
        }
        if (result.skippedCount > 0) {
            builder.add("skippedCount", result.skippedCount);
        }
        if (targetField != null && !targetField.isBlank()) {
            builder.add("targetField", targetField);
        }
        if (matchIdsField != null && !matchIdsField.isBlank()) {
            builder.add("matchIdsField", matchIdsField);
            builder.add("matchIdsStored", result.matchIds.size());
            builder.add("truncated", result.truncated);
        }
        return builder.build();
    }

    /**
     * Extracts a numeric value from the given field of the given workitem.
     * Returns null if the field is missing or its value cannot be interpreted as
     * a number - callers treat this as "skip this workitem", not as an error that
     * aborts the whole aggregation.
     */
    private BigDecimal extractNumericValue(ItemCollection workitem, String field) {
        if (field == null || !workitem.hasItem(field)) {
            return null;
        }
        List<Object> values = workitem.getItemValue(field);
        if (values.isEmpty() || values.get(0) == null) {
            return null;
        }
        Object rawValue = values.get(0);
        try {
            if (rawValue instanceof BigDecimal) {
                return (BigDecimal) rawValue;
            }
            if (rawValue instanceof Number) {
                return BigDecimal.valueOf(((Number) rawValue).doubleValue());
            }
            // fall back to string parsing (e.g. field stored as text)
            String stringValue = rawValue.toString().trim();
            if (stringValue.isEmpty()) {
                return null;
            }
            return new BigDecimal(stringValue);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}