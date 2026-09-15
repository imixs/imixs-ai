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

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.DocumentService;
import org.imixs.workflow.exceptions.QueryException;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

/**
 * Central search service used by all tool call handlers that query for
 * related workitems (find_workitem, link_workitem, aggregate_workitems).
 * <p>
 * A criteria value can be either:
 * <ul>
 * <li>a literal string, taken as-is (e.g. {@code "Contract"}), or</li>
 * <li>a reference to a field of the CURRENT workitem, written as
 * {@code {{itemname}}} (e.g. {@code {{$uniqueid}}},
 * {@code {{fine.plate.number}}}). The actual value is read directly from the
 * current workitem and substituted before the query is built.</li>
 * </ul>
 * The reference form exists specifically to avoid having the LLM retype a
 * value that already exists as a field on the current workitem - a real risk
 * for long, unstructured values such as a $uniqueid, where a single
 * transcription mistake produces a silently wrong query instead of an
 * obvious error. The literal form remains fully supported and unchanged, so
 * existing prompts that pass values directly (e.g. an extracted license
 * plate number) keep working exactly as before.
 * <p>
 * Resolution failures (a referenced item does not exist, or is blank, on the
 * current workitem) always fail the call with a {@link QueryException}.
 * There is no fallback or best-effort behavior - a missing reference must
 * never silently turn into an empty or wrong criterion.
 */
@Stateless
@LocalBean
public class WorkitemSearchService {

    @Inject
    DocumentService documentService;

    // Matches one "{{itemname}}" reference within a criteria value. Not anchored
    // to the whole value - a value may mix literal text and one or more
    // references, e.g. "INV-{{project.id}}-{{$uniqueid}}". Item names must not
    // contain '{' or '}', so nested or malformed placeholders are never matched.
    private static final Pattern ITEM_REFERENCE_PATTERN = Pattern.compile("\\{\\{([^{}]+)\\}\\}");

    /**
     * Searches for workitems matching the given criteria, scoped to
     * {@code type:workitem}, returning one page of up to {@code maxResult}
     * entries starting at {@code pageIndex}.
     *
     * @param criteria        map of index field name to search value; each value
     *                        is either a literal or a {@code {{itemname}}}
     *                        reference to a field of {@code currentWorkitem}
     * @param currentWorkitem the workitem {@code {{itemname}}} references are
     *                        resolved against
     * @param maxResult       maximum number of results to return for this page
     * @param pageIndex       zero-based page index
     * @throws QueryException if a referenced item is missing or empty on
     *                        {@code currentWorkitem}, or if the query is
     *                        malformed or execution fails
     */
    public List<ItemCollection> findWorkitems(JsonObject criteria, ItemCollection currentWorkitem, int maxResult,
            int pageIndex) throws QueryException {

        JsonObject resolvedCriteria = resolveCriteria(criteria, currentWorkitem);
        String query = buildQuery(resolvedCriteria);
        return documentService.find(query, maxResult, pageIndex);
    }

    /**
     * Resolves every {@code {{itemname}}} reference in the given criteria map
     * against the given workitem, replacing it with the item's actual string
     * value. Values that are not of reference form are passed through unchanged
     * as literals.
     *
     * @throws QueryException if a referenced item does not exist, or exists but
     *                        is blank, on {@code currentWorkitem}
     */
    JsonObject resolveCriteria(JsonObject criteria, ItemCollection currentWorkitem) throws QueryException {
        JsonObjectBuilder resolvedBuilder = Json.createObjectBuilder();

        for (String field : criteria.keySet()) {
            String rawValue = criteria.getString(field);
            String resolvedValue = resolveReferences(rawValue, currentWorkitem);
            resolvedBuilder.add(field, resolvedValue);
        }

        return resolvedBuilder.build();
    }

    /**
     * Replaces every {@code {{itemname}}} occurrence within the given raw value
     * with the actual value of that item on the current workitem. Literal text
     * surrounding one or more references (prefix, suffix, or text between several
     * references) is preserved unchanged. A value with no references at all is
     * returned unchanged.
     *
     * @throws QueryException if any referenced item is missing or empty on
     *                        {@code currentWorkitem}
     */
    private String resolveReferences(String rawValue, ItemCollection currentWorkitem) throws QueryException {
        Matcher matcher = ITEM_REFERENCE_PATTERN.matcher(rawValue);
        StringBuilder resolved = new StringBuilder();
        int lastEnd = 0;

        while (matcher.find()) {
            resolved.append(rawValue, lastEnd, matcher.start());
            String itemName = matcher.group(1).trim();
            resolved.append(resolveItemReference(itemName, currentWorkitem));
            lastEnd = matcher.end();
        }
        resolved.append(rawValue.substring(lastEnd));

        return resolved.toString();
    }

    /**
     * Reads the given item's string value from the current workitem. Fails hard
     * if the item is missing or blank - no guessing, no fallback, since a wrong
     * or empty criterion here would produce a silently wrong result set.
     */
    private String resolveItemReference(String itemName, ItemCollection currentWorkitem) throws QueryException {
        if (!currentWorkitem.hasItem(itemName)) {
            throw new QueryException(QueryException.QUERY_NOT_UNDERSTANDABLE,
                    "Referenced item '" + itemName + "' does not exist on the current workitem - "
                            + "cannot resolve {{" + itemName + "}} in criteria.");
        }
        String value = currentWorkitem.getItemValueString(itemName);
        if (value == null || value.isBlank()) {
            throw new QueryException(QueryException.QUERY_NOT_UNDERSTANDABLE,
                    "Referenced item '" + itemName + "' is empty on the current workitem - "
                            + "cannot resolve {{" + itemName + "}} in criteria.");
        }
        return value;
    }

    /**
     * Builds a Lucene query from the given, already-resolved criteria map -
     * always scoped to type:workitem.
     *
     * @param criteria map of index field name to search value (combined with
     *                 AND); values here are already resolved - no
     *                 {@code {{itemname}}} references remain at this point
     */
    String buildQuery(JsonObject criteria) {
        StringBuilder queryBuilder = new StringBuilder("(type:workitem)");
        for (String field : criteria.keySet()) {
            String value = criteria.getString(field);
            // Quote the value to handle spaces and Lucene special characters
            // like the hyphen in a license plate (e.g. "M-AH-4524")
            queryBuilder.append(" AND (").append(field).append(":\"").append(sanitizeValue(value)).append("\")");
        }
        return queryBuilder.toString();
    }

    /**
     * Sanitizes a literal criteria value before it is embedded into the quoted
     * Lucene phrase query field:"value". Only removes characters that could let
     * the value break out of its quoted context (query injection): double
     * quotes, and backslashes, which Lucene uses to escape a quote and could
     * otherwise be used to escape the query's own closing quote. AND/OR and
     * parentheses are deliberately left untouched - inside a quoted phrase they
     * are literal text to Lucene, not operators, so stripping them would only
     * corrupt legitimate values without any security benefit.
     */
    private String sanitizeValue(String value) {
        return value
                .replace("\"", "")
                .replace("\\", "");
    }
}