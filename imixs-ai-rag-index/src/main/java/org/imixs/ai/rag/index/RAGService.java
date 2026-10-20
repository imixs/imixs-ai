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

import java.util.List;
import java.util.logging.Logger;

import org.imixs.ai.api.APIUtil;
import org.imixs.ai.api.LLMOptions;
import org.imixs.ai.api.OpenAIAPIService;
import org.imixs.ai.rag.RAGUtil;
import org.imixs.ai.rag.cluster.ClusterException;
import org.imixs.ai.rag.cluster.ClusterService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RunAs;
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
@DeclareRoles({ "org.imixs.ACCESSLEVEL.MANAGERACCESS" })
@RunAs("org.imixs.ACCESSLEVEL.MANAGERACCESS")
@Stateless
public class RAGService {

    private static Logger logger = Logger.getLogger(RAGService.class.getName());

    @Inject
    ClusterService clusterService;

    @Inject
    OpenAIAPIService openAIAPIService;

    /**
     * Updates the meta data of a workitem. The method calls
     * clusterService.updateMetaData()
     * 
     * @param workitem
     * @throws PluginException
     * @throws AdapterException
     */
    public void updateMetadata(ItemCollection workitem)
            throws PluginException {
        try {
            clusterService.updateMetaData(workitem.getUniqueID(), workitem.getWorkflowGroup(),
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
    public void indexWorkitem(ItemCollection workitem)
            throws PluginException, ClusterException {

        logger.info(" Bin in indexWorkitem");
        long processingTime = System.currentTimeMillis();
        String embeddingsEndpoint = null;

        boolean debug = workitem.getItemValueBoolean(RAGUtil.ITEM_DEBUG);
        String category = workitem.getItemValueString(RAGUtil.ITEM_RAG_CATEGORY);
        String llmPrompt = workitem.getItemValueString(RAGUtil.ITEM_RAG_TEXT);

        String sEmbeddingOptions = workitem.getItemValueString(RAGUtil.ITEM_RAG_EMBEDDINGS_OPTIONS);
        LLMOptions embeddingOptions = new LLMOptions(sEmbeddingOptions);

        if (debug) {
            logger.info("│   ├── Category: " + (category.isEmpty() ? "primary data" : category));
            logger.info("│   ├── Total Prompt Length = " + llmPrompt.length());
            logger.info("│   ├── Prompt: ");
            logger.info(llmPrompt);
        }

        // Remove old embeddings for THIS category only (not all categories!)
        clusterService.removeEmbeddingsByCategory(workitem.getUniqueID(), category);

        // Chunk text and insert with category
        List<String> chunk_list = RAGUtil.chunkMarkupDocument(llmPrompt, 512);
        int chunkIndex = 1;
        for (String chunk : chunk_list) {
            String chunk_id = String.format("%016d", chunkIndex);
            if (debug) {
                logger.info("│   ├── 🔸 chunk " + chunk_id + ": ");
                logger.info(chunk);
            }
            List<Float> indexResult = openAIAPIService.postEmbedding(
                    chunk, embeddingsEndpoint, embeddingOptions, debug);

            // Write to cassandra WITH category
            clusterService.insertEmbeddings(
                    workitem.getUniqueID(),
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

}
