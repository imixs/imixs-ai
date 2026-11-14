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

package org.imixs.ai.rag.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.ai.api.APIUtil;
import org.imixs.ai.api.LLMOptions;
import org.imixs.ai.api.OpenAIAPIService;
import org.imixs.ai.rag.RAGUtil;
import org.imixs.ai.rag.cluster.ClusterException;
import org.imixs.ai.rag.cluster.ClusterService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

/**
 * The EmbeddingService is responsible to create embeddings of a BPMN prompt
 * template and send the result via EventLog Entries to the RAG Service. RAG
 * Events are handled by the IndexOperator remotely.
 * 
 * @see IndexOperator
 * @version 1.0
 * @author rsoika
 *
 */
@Stateless
public class RAGService {

    private static Logger logger = Logger.getLogger(RAGService.class.getName());

    @Inject
    ClusterService clusterService;

    @Inject
    OpenAIAPIService openAIAPIService;

    @Inject
    @ConfigProperty(name = RAGUtil.ENV_EMBEDDINGS_PREFIX_DOCUMENT)
    Optional<String> prefixDocument;

    @Inject
    @ConfigProperty(name = RAGUtil.ENV_EMBEDDINGS_PREFIX_QUERY)
    Optional<String> prefixQuery;

    private static final int MAX_CHUNG_SIZE = 1200;

    /**
     * Updates the meta data of a workitem. The method calls
     * clusterService.updateMetaData()
     * 
     * @param workitem
     * @throws PluginException
     * @throws AdapterException
     */
    public void updateMetadata(String refID, ItemCollection workitem)
            throws PluginException {
        try {
            clusterService.updateMetaData(refID, workitem.getWorkflowGroup(),
                    workitem.getTaskID());
        } catch (ClusterException e) {
            throw new PluginException(RAGService.class.getSimpleName(), APIUtil.ERROR_API, e.getMessage(), e);
        }
    }

    /**
     * This method stores a given text with the corresponding embeddings into the
     * cassandra databse.
     * 
     * @param llmIndexDefinitions
     * @param workitem
     * @param event
     * @throws PluginException
     * @throws AdapterException
     * @throws ClusterException
     */
    public void indexWorkitem(String refID, ItemCollection workitem)
            throws PluginException, ClusterException {

        long processingTime = System.currentTimeMillis();
        String embeddingsEndpoint = null;

        boolean debug = workitem.getItemValueBoolean(RAGUtil.ITEM_DEBUG);
        String category = workitem.getItemValueString(RAGUtil.ITEM_RAG_CATEGORY);
        String llmPrompt = workitem.getItemValueString(RAGUtil.ITEM_RAG_TEXT);
        embeddingsEndpoint = workitem.getItemValueString(RAGUtil.ITEM_RAG_ENDPOINT);

        String sEmbeddingOptions = workitem.getItemValueString(RAGUtil.ITEM_RAG_EMBEDDINGS_OPTIONS);
        LLMOptions embeddingOptions = new LLMOptions(sEmbeddingOptions);

        if (debug) {
            logger.info("├── 🔃 index workitem");
            logger.info("│   ├── Endpoint: " + embeddingsEndpoint);
            logger.info("│   ├── Category: " + (category.isEmpty() ? "primary data" : category));
            logger.info("│   ├── Total Prompt Length = " + llmPrompt.length());
            logger.info("│   ├── Prompt: ");
            logger.info(llmPrompt);
        }

        // Remove old embeddings for THIS category only (not all categories!)
        clusterService.removeEmbeddingsByCategory(refID, category);

        // Chunk text and insert with category
        List<String> chunk_list = RAGUtil.chunkMarkdownDocument(llmPrompt, MAX_CHUNG_SIZE);
        int chunkIndex = 1;
        for (String chunk : chunk_list) {
            String chunk_id = String.format("%016d", chunkIndex);
            if (debug) {
                logger.info("│   ├── 🔸 chunk " + chunk_id + ": ");
                logger.info(chunk);
            }
            // The prefix is only used for the embedding, the stored chunk stays unchanged
            String embeddingInput = RAGUtil.applyPrefix(prefixDocument.orElse(""), chunk);
            List<Float> indexResult = openAIAPIService.postEmbedding(
                    embeddingInput, embeddingsEndpoint, embeddingOptions, debug);

            // Write to cassandra WITH category
            clusterService.insertEmbeddings(
                    refID,
                    chunk_id,
                    category, // Category parameter!
                    workitem.getWorkflowGroup(),
                    workitem.getTaskID(),
                    chunk,
                    indexResult);

            if (debug) {
                logger.info(
                        "│   ├── ⇨ " + indexResult.size() + " floats stored in RAG db (category: "
                                + (category.isEmpty() ? "primary" : category) + ")");
            }
            chunkIndex++;
        }

        if (debug) {
            logger.info(
                    "├── ✅ Total processing time: " + (System.currentTimeMillis() - processingTime) + "ms");
        }

    }

    /**
     * Computes the embedding for the given prompt and searches the RAG database
     * for the best matching documents. Each document is returned only once, sorted
     * by similarity (descending).
     * <p>
     * The filters are passed through unchanged. A null value means 'no filter'. For
     * the category an empty string means 'primary data only'.
     *
     * @param prompt             the text to search for
     * @param embeddingsEndpoint the embeddings endpoint
     * @param embeddingOptions   the embedding options
     * @param maxResults         the maximum number of documents
     * @param category           optional category filter
     * @param modelGroups        optional model group filter
     * @param tasks              optional task filter
     * @param debug              prints debug information
     * @return list of matches with the items 'id', 'similarity' and 'contentChunk'
     */
    public List<ItemCollection> retrieval(String prompt, String embeddingsEndpoint,
            LLMOptions embeddingOptions, int maxResults, String category, String modelGroups,
            String tasks, boolean debug) throws PluginException, ClusterException {

        long processingTime = System.currentTimeMillis();
        if (prompt == null || prompt.isBlank()) {
            return new ArrayList<>();
        }

        // The prefix is only used for the embedding, not for the search itself
        String embeddingInput = RAGUtil.applyPrefix(prefixQuery.orElse(""), prompt);
        List<Float> embeddings = openAIAPIService.postEmbedding(
                embeddingInput, embeddingsEndpoint, embeddingOptions, debug);

        List<ItemCollection> result = clusterService.searchEmbeddings(
                embeddings, maxResults, category, modelGroups, tasks);

        if (debug) {
            logger.info("├── 🔎 retrieval: " + result.size() + " matches in "
                    + (System.currentTimeMillis() - processingTime) + "ms");
        }
        return result;
    }

}
