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
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.ai.api.LLMConfigService;
import org.imixs.ai.api.LLMOptions;
import org.imixs.ai.api.OpenAIAPIService;
import org.imixs.ai.rag.index.IndexOperator;
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
    @ConfigProperty(name = RAGUtil.CONFIG_EMBEDDINGS_ENDPOINT)
    Optional<String> embeddingsEndpoint;

    @Inject
    LLMConfigService llmConfigService;

    @Inject
    OpenAIAPIService openAIAPIService;

    @Inject
    @ConfigProperty(name = RAGUtil.CONFIG_EMBEDDINGS_PREFIX_DOCUMENT)
    Optional<String> prefixDocument;

    @Inject
    @ConfigProperty(name = RAGUtil.CONFIG_EMBEDDINGS_PREFIX_QUERY)
    Optional<String> prefixQuery;

    /**
     * Verifies that the central embedding endpoint is configured. Called on startup
     * by the IndexScheduler - a missing configuration aborts the deployment.
     */
    public void verifyConfiguration() {
        if (embeddingsEndpoint.map(String::isBlank).orElse(true)) {
            logger.log(Level.SEVERE, "├── ❌ invalid RAG configuration!");
            logger.log(Level.SEVERE, "│   ├── missing required property ''{0}'' (environment variable"
                    + " ''RAG_EMBEDDINGS_ENDPOINT'')", RAGUtil.CONFIG_EMBEDDINGS_ENDPOINT);
            logger.log(Level.SEVERE, "│   └── 👉 deployment aborted - please fix the configuration");
            throw new IllegalStateException("Missing RAG configuration '" + RAGUtil.CONFIG_EMBEDDINGS_ENDPOINT + "'");
        }
    }

    /**
     * Splits the given text into chunks and computes one embedding per chunk. The
     * embeddings are computed by the central embedding endpoint.
     * <p>
     * The optional document prefix (e.g. 'search_document:') is only used for the
     * embedding input. An empty text results in an empty list - indexing an empty
     * list removes the vectors of the category.
     *
     * @param text  - the text to be embedded
     * @param debug - prints debug information
     * @return list of vectors, one per chunk
     * @throws AdapterException if the embedding endpoint fails
     * @throws PluginException
     */
    public List<float[]> createEmbeddings(String text, boolean debug) throws AdapterException, PluginException {
        long processingTime = System.currentTimeMillis();
        List<float[]> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        String endpoint = embeddingsEndpoint.orElseThrow(
                () -> new IllegalStateException(
                        "Missing RAG configuration '" + RAGUtil.CONFIG_EMBEDDINGS_ENDPOINT + "'"));
        LLMOptions options = llmConfigService.getOptions(endpoint);

        List<String> chunks = RAGUtil.chunkMarkdownDocumentWithTitle(text, RAGUtil.MAX_CHUNK_SIZE);
        if (debug) {
            logger.info("├── 🔃 create embeddings: " + chunks.size() + " chunk(s), " + text.length()
                    + " characters, endpoint: " + endpoint);
        }
        for (String chunk : chunks) {
            if (chunk.isBlank()) {
                continue;
            }
            if (debug) {
                logger.info("│   ├── 🔸 chunk " + (result.size() + 1) + ":");
                logger.info(chunk);
            }
            // the prefix is only used for the embedding input
            String embeddingInput = RAGUtil.applyPrefix(prefixDocument.orElse(""), chunk);
            List<Float> embedding = openAIAPIService.postEmbedding(embeddingInput, endpoint, options, debug);
            result.add(toArray(embedding));
        }

        if (debug) {
            logger.info("│   └── ✅ " + result.size() + " embedding(s) in "
                    + (System.currentTimeMillis() - processingTime) + "ms");
        }
        return result;
    }

    /**
     * Converts an embedding returned by the OpenAIAPIService into a float array.
     */
    public static float[] toArray(List<Float> embedding) {
        float[] result = new float[embedding.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = embedding.get(i);
        }
        return result;
    }

    /**
     * Computes the embedding of a query text. The optional query prefix (e.g.
     * 'search_query:') is only used for the embedding input.
     *
     * @param text  - the query text
     * @param debug - prints debug information
     * @return the query vector
     * @throws AdapterException if the embedding endpoint fails
     * @throws PluginException
     */
    public float[] createQueryEmbedding(String text, boolean debug) throws AdapterException, PluginException {
        String endpoint = embeddingsEndpoint.orElseThrow(
                () -> new IllegalStateException(
                        "Missing RAG configuration '" + RAGUtil.CONFIG_EMBEDDINGS_ENDPOINT + "'"));
        LLMOptions options = llmConfigService.getOptions(endpoint);
        String embeddingInput = RAGUtil.applyPrefix(prefixQuery.orElse(""), text.trim());
        return toArray(openAIAPIService.postEmbedding(embeddingInput, endpoint, options, debug));
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
    // public List<ItemCollection> retrieval(String prompt, String
    // embeddingsEndpoint,
    // LLMOptions embeddingOptions, int maxResults, String category, String
    // modelGroups,
    // String tasks, boolean debug) throws PluginException, ClusterException {

    // long processingTime = System.currentTimeMillis();
    // if (prompt == null || prompt.isBlank()) {
    // return new ArrayList<>();
    // }

    // // The prefix is only used for the embedding, not for the search itself
    // String embeddingInput = RAGUtil.applyPrefix(prefixQuery.orElse(""), prompt);
    // List<Float> embeddings = openAIAPIService.postEmbedding(
    // embeddingInput, embeddingsEndpoint, embeddingOptions, debug);

    // List<ItemCollection> result = clusterService.searchEmbeddings(
    // embeddings, maxResults, category, modelGroups, tasks);

    // if (debug) {
    // logger.info("├── 🔎 retrieval: " + result.size() + " matches in "
    // + (System.currentTimeMillis() - processingTime) + "ms");
    // }
    // return result;
    // }

}
