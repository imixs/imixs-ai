/****************************************************************************
 * Copyright (c) 2022-2025 Imixs Software Solutions GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * This Source Code may also be made available under the terms of the
 * GNU General Public License, version 2 or later (GPL-2.0-or-later),
 * which is available at https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
 ****************************************************************************/

package org.imixs.ai.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.imixs.ai.api.APIUtil;
import org.imixs.ai.api.OpenAIAPIService;
import org.imixs.ai.workflow.ImixsAIPromptService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.exceptions.QueryException;
import org.imixs.workflow.index.vectors.LuceneVectorIndexService;
import org.imixs.workflow.index.vectors.VectorMatch;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

/**
 * The RetrievalService searches the RAG index for documents similar to a given
 * text.
 * <p>
 * <b>Access rights:</b> The service runs with the rights of the current user -
 * it must not be annotated with {@code @RunAs}. The vector search is restricted
 * to documents the current user is allowed to read, exactly as a regular
 * search.
 * <p>
 * A retrieval is defined in the BPMN model:
 *
 * <pre>
 * {@code
 * <imixs-rag name="RETRIEVAL">
 *    <reference-item>similar.claims</reference-item>
 *    <categories>risk,contract</categories>
 *    <query>($workflowgroup:"Claim")</query>
 *    <max-results>5</max-results>
 *    <debug>false</debug>
 * </imixs-rag>
 * }
 * </pre>
 * <ul>
 * <li>reference-item (required) - item to store the ids of the matching
 * documents</li>
 * <li>categories (optional) - comma separated list of categories. Empty = all
 * categories</li>
 * <li>query (optional) - full-text search term to filter the documents (Lucene
 * syntax)</li>
 * <li>max-results (optional) - maximum number of documents, default = 1</li>
 * </ul>
 * The query text is built from the prompt template of the definition. The
 * current workitem itself is never part of the result.
 *
 * @version 1.0
 * @author rsoika
 */
@Stateless
public class RetrievalService {

    private static final Logger logger = Logger.getLogger(RetrievalService.class.getName());

    @Inject
    private ImixsAIPromptService imixsAIPromptService;

    @Inject
    private OpenAIAPIService openAIAPIService;

    @Inject
    private RAGService ragService;

    @Inject
    private LuceneVectorIndexService vectorIndexService;

    /**
     * Executes the given retrieval definitions for a workitem. The ids of the
     * matching documents are stored in the reference-item of each definition.
     *
     * @param retrievalDefinitions - list of RAG RETRIEVAL definitions
     * @param workitem             - the current workitem
     * @param event                - the current BPMN event
     * @throws PluginException  if a definition is invalid
     * @throws AdapterException if the embedding endpoint fails
     */
    public void createRetrieval(List<ItemCollection> retrievalDefinitions, ItemCollection workitem,
            ItemCollection event) throws PluginException, AdapterException {
        for (ItemCollection definition : retrievalDefinitions) {
            long processingTime = System.currentTimeMillis();
            boolean debug = definition.getItemValueBoolean("debug");

            String itemRef = definition.getItemValueString("reference-item");
            if (itemRef.isBlank()) {
                throw new PluginException(RetrievalService.class.getSimpleName(), APIUtil.ERROR_API,
                        "imixs-ai configuration error: missing reference-item in RAG RETRIEVAL definition!");
            }
            List<String> categories = RAGUtil.parseCategories(definition.getItemValueString("categories"));
            String query = definition.getItemValueString("query");
            int maxResults = definition.getItemValueInteger("max-results");
            if (maxResults <= 0) {
                maxResults = 1;
            }

            // build the query text from the prompt template
            String promptTemplate = imixsAIPromptService.loadPromptTemplate(definition, event);
            String text = openAIAPIService.buildEmbeddingsPrompt(promptTemplate, workitem);
            if (text == null || text.isBlank()) {
                throw new PluginException(RetrievalService.class.getSimpleName(), APIUtil.ERROR_API,
                        "Unable to build the query text for RAG retrieval - verify prompt template!");
            }

            if (debug) {
                logger.info("├── 🔎 RAG retrieval for " + workitem.getUniqueID());
                logger.info("│   ├── reference-item: " + itemRef);
                logger.info("│   ├── categories: " + (categories.isEmpty() ? "all" : categories));
                logger.info("│   ├── query: " + (query.isBlank() ? "-" : query));
                logger.info("│   ├── max-results: " + maxResults);
                logger.info("│   ├── query text (" + text.length() + " characters):");
                logger.info(text);
            }

            // one more result, because the current workitem may be part of the result
            List<VectorMatch> matches = search(text, categories, query, maxResults + 1, debug);
            List<String> ids = new ArrayList<>();
            for (VectorMatch match : matches) {
                if (match.uniqueId().equals(workitem.getUniqueID())) {
                    continue;
                }
                ids.add(match.uniqueId());
                if (debug) {
                    logger.info("│   ├── 🔸 " + match.uniqueId() + " (similarity " + match.similarity() + ")");
                }
                if (ids.size() >= maxResults) {
                    break;
                }
            }
            workitem.setItemValue(itemRef, ids);

            if (debug) {
                logger.info("│   └── ✅ " + ids.size() + " match(es) in " + (System.currentTimeMillis() - processingTime)
                        + "ms");
            }
        }
    }

    /**
     * Searches the RAG index for documents similar to the given text. The result
     * contains only documents the current user is allowed to read.
     *
     * @param text       - the query text
     * @param categories - categories to search in. null or empty = all categories
     * @param query      - optional full-text search term to filter the documents
     * @param maxResults - maximum number of documents
     * @param debug      - prints debug information
     * @return matches sorted by similarity (descending)
     * @throws PluginException  if the query is invalid
     * @throws AdapterException if the embedding endpoint fails
     */
    public List<VectorMatch> search(String text, List<String> categories, String query, int maxResults,
            boolean debug) throws PluginException, AdapterException {
        if (text == null || text.isBlank()) {
            throw new PluginException(RetrievalService.class.getSimpleName(), APIUtil.ERROR_API,
                    "Unable to search the RAG index: query text is empty!");
        }
        float[] queryVector = ragService.createQueryEmbedding(text, debug);
        try {
            return vectorIndexService.search(queryVector, categories, query, maxResults);
        } catch (QueryException e) {
            throw new PluginException(RetrievalService.class.getSimpleName(), APIUtil.ERROR_API,
                    "Invalid query in RAG RETRIEVAL definition: " + e.getMessage(), e);
        }
    }

}