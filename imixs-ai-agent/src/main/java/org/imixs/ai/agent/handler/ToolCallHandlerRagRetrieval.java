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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

import org.imixs.ai.api.ImixsAIContextHandler;
import org.imixs.ai.api.LLMConfigService;
import org.imixs.ai.api.LLMOptions;
import org.imixs.ai.rag.EmbeddingService;
import org.imixs.ai.rag.SearchFilter;
import org.imixs.ai.toolcall.ToolCallFunction;
import org.imixs.ai.toolcall.ToolCallHandler;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.DocumentService;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

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
 * The embeddings endpoint is taken from the agent configuration
 * ('agent.rag.endpoint') via {@link ImixsAIContextHandler#getRagEndpoint()}.
 * The search is always restricted by model group and task id. The category is
 * fixed to the primary data of the index.
 *
 * @see ToolCallHandlerRagSelect
 * @see EmbeddingService#search
 */
@Named
public class ToolCallHandlerRagRetrieval implements ToolCallHandler, Serializable {

    private static final long serialVersionUID = 1L;
    public static final String TOOL_RAG_RETRIEVAL = "rag_retrieval";

    // Workitem item holding the uniqueids of the last retrieval (position = number)
    public static final String ITEM_CANDIDATES = "rag.candidates";

    // Workitem item holding the text of a candidate returned to the LLM
    public static final String ITEM_SUMMARY = "offer.summary";

    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int MAX_RESULTS_LIMIT = 10;

    private static final Logger logger = Logger.getLogger(ToolCallHandlerRagRetrieval.class.getName());

    @Inject
    EmbeddingService embeddingService;

    @Inject
    LLMConfigService llmConfigService;

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
                                "maxResults": {
                                    "type": "integer",
                                    "description": "Optional. Maximum number of candidates (default 5, maximum 10)."
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

        if (query == null || query.isBlank()) {
            _function.setError("Missing or empty 'query' argument!");
            return;
        }
        // The search must never run unfiltered
        if (modelGroups == null || modelGroups.isBlank() || tasks == null || tasks.isBlank()) {
            _function.setError("The arguments 'modelgroups' and 'tasks' are mandatory!");
            return;
        }
        // The embeddings endpoint is defined in the agent configuration
        String embeddingsEndpoint = contextHandler.getRagEndpoint();
        if (embeddingsEndpoint == null || embeddingsEndpoint.isBlank()) {
            _function.setError("RAG search is not configured - missing 'agent.rag.endpoint' in the agent event!");
            return;
        }

        int maxResults = Math.min(Math.max(args.getInt("maxResults", DEFAULT_MAX_RESULTS), 1), MAX_RESULTS_LIMIT);

        if (contextHandler.isDebug()) {
            logger.info("├── ToolCallHandlerRagRetrieval: rag_retrieval modelgroups='" + modelGroups + "' tasks='"
                    + tasks + "' maxResults=" + maxResults);
            logger.info("│   ├── embeddings endpoint: " + embeddingsEndpoint);
            // the query may span several lines - log it in a single line
            logger.info("│   ├── query: " + query.trim().replace("\n", " | "));
        }
        try {
            LLMOptions embeddingOptions = llmConfigService.getOptions(embeddingsEndpoint);
            // The category is fixed to the primary data
            SearchFilter filter = SearchFilter.create()
                    .primaryDataOnly()
                    .modelGroups(modelGroups)
                    .tasks(tasks);

            List<ItemCollection> matches = embeddingService.search(query, embeddingsEndpoint, embeddingOptions,
                    maxResults, filter, contextHandler.isDebug());
            if (contextHandler.isDebug()) {
                logger.info("│   ├── RAG service returned " + matches.size() + " match(es)");
            }

            ItemCollection workitem = contextHandler.getWorkItem();

            // The candidate number is the position in this list (starting with 1)
            List<String> candidateIds = new ArrayList<>();
            JsonArrayBuilder candidatesArray = Json.createArrayBuilder();

            for (ItemCollection match : matches) {
                // The index may be out of date, so always load the current workitem.
                // A workitem that is missing or not readable is skipped.
                String matchId = match.getItemValueString("id");
                ItemCollection candidate = documentService.load(matchId);
                if (candidate == null) {
                    if (contextHandler.isDebug()) {
                        logger.info("│   ├── ⚠️ skipped " + matchId + " (not found or no access)");
                    }
                    continue;
                }
                candidateIds.add(candidate.getUniqueID());

                if (contextHandler.isDebug()) {
                    logger.info("│   ├── candidate " + candidateIds.size() + ": score="
                            + match.getItemValueFloat("similarity") + " id=" + candidate.getUniqueID() + " '"
                            + candidate.getItemValueString("$workflowsummary") + "'");
                }

                JsonObjectBuilder entry = Json.createObjectBuilder()
                        .add("nr", candidateIds.size())
                        .add("score", (double) match.getItemValueFloat("similarity"))
                        .add("workflowgroup", candidate.getItemValueString("$workflowgroup"))
                        .add("workflowstatus", candidate.getItemValueString("$workflowstatus"))
                        .add("workflowsummary", candidate.getItemValueString("$workflowsummary"))
                        .add("summary", candidate.getItemValueString(ITEM_SUMMARY));
                Date created = candidate.getItemValueDate("$created");
                if (created != null) {
                    entry.add("created", created.toInstant().toString());
                }
                candidatesArray.add(entry);
            }

            // Replace (not append): the numbers always refer to the last retrieval
            workitem.replaceItemValue(ITEM_CANDIDATES, candidateIds);

            String resultJson = Json.createObjectBuilder()
                    .add("candidateCount", candidateIds.size())
                    .add("candidates", candidatesArray)
                    .build().toString();
            _function.setResultValue(resultJson);

            String message = "rag_retrieval: " + candidateIds.size() + " candidate(s) found";
            _function.setToolMessage(message);
            if (contextHandler.isDebug()) {
                logger.info("│   └── ✅ " + message);
            }

        } catch (PluginException | AdapterException e) {
            logger.warning("│   └── ⚠️ rag_retrieval failed: " + e.getMessage());
            _function.setError(e.getMessage());
        }
    }
}