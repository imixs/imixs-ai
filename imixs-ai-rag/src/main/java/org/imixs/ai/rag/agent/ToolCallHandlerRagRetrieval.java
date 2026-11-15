/****************************************************************************
 * Copyright (c) 2022-2025 Imixs Software Solutions GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
 ****************************************************************************/

package org.imixs.ai.rag.agent;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

import org.imixs.ai.api.ImixsAIContextHandler;
import org.imixs.ai.rag.RAGUtil;
import org.imixs.ai.rag.RetrievalService;
import org.imixs.ai.toolcall.ToolCallFunction;
import org.imixs.ai.toolcall.ToolCallHandler;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.DocumentService;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.index.vectors.VectorMatch;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.json.JsonObject;

/**
 * Handles the "rag_retrieval" tool call.
 * <p>
 * The tool searches the RAG index with a query text (semantic search), loads
 * the matching workitems and returns them to the LLM as a numbered list of
 * candidates (number, score, summary). The $uniqueid of a candidate is never
 * passed to the LLM. Instead the handler stores the ordered list of uniqueids
 * in the current workitem (item 'rag.candidates'). The position in this list is
 * the candidate number.
 * <p>
 * The LLM evaluates the candidates and selects the best match with the tool
 * "rag_select", which resolves the number back into the uniqueid.
 * <p>
 * The search is always restricted by model group and task id. Both values are
 * translated into a full-text filter of the vector search. The values are
 * validated, so the LLM can not inject an arbitrary search term. The optional
 * categories restrict the search to specific business views of the
 * embeddings.
 * <p>
 * The search runs with the rights of the current user. The embedding endpoint
 * is defined centrally by the RAG configuration.
 *
 * @see ToolCallHandlerRagSelect
 * @see RetrievalService#search
 */
@Named
public class ToolCallHandlerRagRetrieval implements ToolCallHandler, Serializable {

    private static final long serialVersionUID = 1L;
    public static final String TOOL_RAG_RETRIEVAL = "rag_retrieval";

    // Workitem item holding the uniqueids of the last retrieval (position = number)
    public static final String ITEM_CANDIDATES = "rag.candidates";

    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MAX_RESULTS_LIMIT = 10;

    private static final Logger logger = Logger.getLogger(ToolCallHandlerRagRetrieval.class.getName());

    @Inject
    RetrievalService retrievalService;

    @Inject
    DocumentService documentService;

    @Override
    public String getToolName() {
        return TOOL_RAG_RETRIEVAL;
    }

    @Override
    public void register(ImixsAIContextHandler contextHandler) {
        contextHandler.addFunction(
                TOOL_RAG_RETRIEVAL,
                "Searches the knowledge base for workitems that are semantically similar to a query text "
                        + "and returns them as a numbered list of candidates (max " + MAX_RESULTS_LIMIT + "). "
                        + "The candidates are sorted by similarity, NOT by date. The similarity score only "
                        + "indicates a rough match, it does not prove that a candidate is the right one - you "
                        + "must read each candidate and decide yourself. Every candidate has a number. To select "
                        + "a candidate use the tool rag_select with that number. Always restrict the search with "
                        + "'modelgroups' and 'tasks' exactly as given in the task instructions - never invent "
                        + "these values. The query text must follow the form given in the task instructions.",
                """
                        {
                            "type": "object",
                            "properties": {
                                "query": {
                                    "type": "string",
                                    "description": "The query text, formatted exactly as described in the task instructions."
                                },
                                "modelgroups": {
                                    "type": "string",
                                    "description": "Workflow model group(s) to search in, exactly as given in the task instructions."
                                },
                                "tasks": {
                                    "type": "string",
                                    "description": "Task id(s) to search in, exactly as given in the task instructions."
                                },
                                "categories": {
                                    "type": "string",
                                    "description": "Optional. Comma separated list of knowledge categories to search in, exactly as given in the task instructions. Omit to search in all categories."
                                },
                                "maxResults": {
                                    "type": "integer",
                                    "description": "Optional. Maximum number of candidates (default 5, maximum 10)."
                                },
                                "items": {
                                    "type": "string",
                                    "description": "Optional. Comma separated list of item names. The content of these items is returned for each candidate, exactly as given in the task instructions."
                                }
                            },
                            "required": ["query", "modelgroups", "tasks"]
                        }
                        """);
    }

    @Override
    public void handle(ToolCallFunction _function) {
        JsonObject args = _function.getArguments();
        ImixsAIContextHandler contextHandler = _function.getContextHandler();

        String query = args.containsKey("query") ? args.getString("query") : null;
        String modelGroups = args.containsKey("modelgroups") ? args.getString("modelgroups") : null;
        String tasks = args.containsKey("tasks") ? args.getString("tasks") : null;
        String categories = args.containsKey("categories") ? args.getString("categories") : null;

        // read optional item list:
        String itemNames = args.containsKey("items") ? args.getString("items") : null;
        List<String> itemList = parseItemNames(itemNames);

        if (query == null || query.isBlank()) {
            _function.setError("Missing or empty 'query' argument!");
            return;
        }
        // The search must never run unfiltered
        if (modelGroups == null || modelGroups.isBlank() || tasks == null || tasks.isBlank()) {
            _function.setError("The arguments 'modelgroups' and 'tasks' are mandatory!");
            return;
        }
        // translate model groups and task ids into a full-text filter
        String filterQuery;
        try {
            filterQuery = buildFilterQuery(modelGroups, tasks);
        } catch (IllegalArgumentException e) {
            _function.setError(e.getMessage());
            return;
        }

        int maxResults = Math.min(Math.max(args.getInt("maxResults", DEFAULT_MAX_RESULTS), 1), MAX_RESULTS_LIMIT);

        if (contextHandler.isDebug()) {
            logger.info("├── ToolCallHandlerRagRetrieval: rag_retrieval modelgroups='" + modelGroups + "' tasks='"
                    + tasks + "' maxResults=" + maxResults);
            logger.info("│   ├── categories: " + (categories == null || categories.isBlank() ? "all" : categories));
            logger.info("│   ├── filter: " + filterQuery);
            // the query may span several lines - log it in a single line
            logger.info("│   ├── query: " + query.trim().replace("\n", " | "));
        }
        try {
            List<VectorMatch> matches = retrievalService.search(query, RAGUtil.parseCategories(categories),
                    filterQuery, maxResults, contextHandler.isDebug());
            if (contextHandler.isDebug()) {
                logger.info("│   ├── vector search returned " + matches.size() + " match(es)");
            }

            ItemCollection workitem = contextHandler.getWorkItem();

            // The candidate number is the position in this list (starting with 1)
            List<String> candidateIds = new ArrayList<>();
            List<String> candidateBlocks = new ArrayList<>();

            for (VectorMatch match : matches) {
                String matchId = match.uniqueId();
                ItemCollection candidate = documentService.load(matchId);
                if (candidate == null) {
                    if (contextHandler.isDebug()) {
                        logger.info("│   ├── ⚠️ skipped " + matchId + " (not found or no access)");
                    }
                    continue;
                }
                candidateIds.add(candidate.getUniqueID());
                int nr = candidateIds.size();
                float score = match.similarity();

                if (contextHandler.isDebug()) {
                    logger.info("│   ├── candidate " + nr + ": score=" + score + " id=" + candidate.getUniqueID()
                            + " '" + candidate.getItemValueString("$workflowsummary") + "'");
                }
                candidateBlocks.add(buildCandidateText(nr, score, candidate, itemList));
            }

            // Replace (not append): the numbers always refer to the last retrieval
            workitem.replaceItemValue(ITEM_CANDIDATES, candidateIds);

            // The tool message is the text that is sent back to the LLM as the tool result.
            StringBuilder result = new StringBuilder();
            result.append("candidateCount: ").append(candidateIds.size()).append("\n");
            for (String block : candidateBlocks) {
                result.append("\n").append(block);
            }
            _function.setToolMessage(result.toString());
            // short summary for the comment log
            _function.setLogMessage(candidateIds.size() + " candidate(s) found");

            if (contextHandler.isDebug()) {
                logger.info("│   └── ✅ rag_retrieval: " + candidateIds.size() + " candidate(s) found");
            }

        } catch (PluginException | AdapterException e) {
            logger.warning("│   └── ⚠️ rag_retrieval failed: " + e.getMessage());
            _function.setError(e.getMessage());
        }
    }

    /**
     * Builds the full-text filter for the vector search from the given model
     * groups and task ids. Both values are comma separated lists. The values are
     * validated and quoted, so the LLM can not inject an arbitrary search term.
     *
     * @return the filter query, e.g. ($workflowgroup:"Claim") AND ($taskid:1000)
     * @throws IllegalArgumentException if a value is invalid
     */
    static String buildFilterQuery(String modelGroups, String tasks) {
        List<String> groupTerms = new ArrayList<>();
        for (String group : modelGroups.split(",")) {
            if (!group.isBlank()) {
                // quote the value - escape backslashes and quotes
                String value = group.trim().replace("\\", "\\\\").replace("\"", "\\\"");
                groupTerms.add("$workflowgroup:\"" + value + "\"");
            }
        }
        List<String> taskTerms = new ArrayList<>();
        for (String task : tasks.split(",")) {
            String value = task.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (!value.matches("\\d+")) {
                throw new IllegalArgumentException("Invalid task id '" + value + "' - expected a number!");
            }
            taskTerms.add("$taskid:" + value);
        }
        if (groupTerms.isEmpty() || taskTerms.isEmpty()) {
            throw new IllegalArgumentException("The arguments 'modelgroups' and 'tasks' are mandatory!");
        }
        return "(" + String.join(" OR ", groupTerms) + ") AND (" + String.join(" OR ", taskTerms) + ")";
    }

    /**
     * Splits a comma separated list of item names into a clean list.
     */
    private List<String> parseItemNames(String itemNames) {
        List<String> result = new ArrayList<>();
        if (itemNames == null || itemNames.isBlank()) {
            return result;
        }
        for (String name : itemNames.split(",")) {
            if (!name.isBlank()) {
                result.add(name.trim());
            }
        }
        return result;
    }

    /**
     * Builds the text block for a single candidate. The block starts with a header
     * (number, score), followed by the creation date and the workflow summary.
     * After
     * that the content of each requested item is added, separated by an empty line.
     * Items without a value are skipped.
     */
    private String buildCandidateText(int nr, float score, ItemCollection candidate, List<String> itemList) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Candidate ").append(nr).append(" (score ").append(score).append(") ===\n");

        Date created = candidate.getItemValueDate("$created");
        if (created != null) {
            sb.append("created: ").append(created.toInstant().toString()).append("\n");
        }

        // Add workflow group and status to help the LLM classify the candidate
        sb.append("workflowgroup: ").append(candidate.getItemValueString("$workflowgroup")).append("\n");
        sb.append("workflowstatus: ").append(candidate.getItemValueString("$workflowstatus")).append("\n");

        String summary = candidate.getItemValueString("$workflowsummary");
        if (!summary.isBlank()) {
            sb.append("workflowsummary: ").append(summary).append("\n");
        }

        for (String itemName : itemList) {
            // join all values of a multi-value item
            StringBuilder value = new StringBuilder();
            for (Object o : candidate.getItemValue(itemName)) {
                if (o != null && !o.toString().isBlank()) {
                    if (value.length() > 0) {
                        value.append("\n");
                    }
                    value.append(o.toString().trim());
                }
            }
            if (value.length() > 0) {
                sb.append("\n").append(value).append("\n");
            }
        }
        return sb.toString();
    }
}