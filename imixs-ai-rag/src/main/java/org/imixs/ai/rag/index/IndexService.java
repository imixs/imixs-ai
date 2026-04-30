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
import java.util.stream.Collectors;

import org.imixs.ai.ImixsAIContextHandler;
import org.imixs.ai.api.LLMConfigService;
import org.imixs.ai.api.LLMOptions;
import org.imixs.ai.api.OpenAIAPIService;
import org.imixs.ai.rag.cluster.ClusterException;
import org.imixs.ai.rag.cluster.ClusterService;
import org.imixs.ai.rag.cluster.RetrievalResult;
import org.imixs.ai.rag.util.RAGUtil;
import org.imixs.ai.rag.workflow.RAGRetrievalAdapter;
import org.imixs.ai.workflow.ImixsAIPromptService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.DocumentEvent;
import org.imixs.workflow.engine.EventLogService;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RunAs;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

/**
 * The IndexService provides methods to index, update, delete or retrieval data
 * from the vector database.
 * <p>
 * The service uses the ClusterService to interact with the Apache Cassandra
 * database.
 * <p>
 * The service reacts on Document Delete events and automatically removes an
 * existing index. The deletion is handled by the RAGEventService
 * 
 * @see IndexOperator
 * @see DocumentEvent
 * @version 1.0
 * @author rsoika
 *
 */
@DeclareRoles({ "org.imixs.ACCESSLEVEL.MANAGERACCESS" })
@RunAs("org.imixs.ACCESSLEVEL.MANAGERACCESS")
@Stateless
public class IndexService {

    private static Logger logger = Logger.getLogger(IndexService.class.getName());

    public static final String ERROR_API = "ERROR_API";
    public static final String ERROR_PROMPT = "ERROR_PROMPT";

    public static final String RAG_INDEX = "INDEX";
    public static final String RAG_UPDATE = "UPDATE";
    public static final String RAG_DELETE = "DELETE";
    public static final String RAG_PROMPT = "PROMPT";
    public static final String RAG_RETRIEVAL = "RETRIEVAL";
    public static final String RAG_DISABLED = "DISABLED";

    // public static final String ITEM_PROMPT_DEFINITION = "prompt.definition";
    public static final String ITEM_PROMPT_TEMPLATE = "prompt-template";

    @Inject
    private ClusterService clusterService;

    @Inject
    private EventLogService eventLogService;

    @Inject
    private OpenAIAPIService openAIAPIService;

    @Inject
    ImixsAIContextHandler imixsAIContextHandler;

    @Inject
    protected ImixsAIPromptService imixsAIPromptService;

    @Inject
    LLMConfigService llmConfigService;

    /**
     * Creates a RAG Index for a Workitem based on an IndexDefinition
     * 
     * @param llmIndexDefinitions
     * @param workitem
     * @param event
     * @throws PluginException
     * @throws AdapterException
     */
    public void indexWorkitem(ItemCollection indexDefinition, ItemCollection workitem)
            throws PluginException, AdapterException {
        long processingTime = System.currentTimeMillis();
        String embeddingsEndpoint = null;
        boolean debug = false;
        try {
            embeddingsEndpoint = imixsAIPromptService.parseEndpointByBPMN(indexDefinition, "embeddings");
            debug = indexDefinition.getItemValueBoolean("debug");

            logger.info("├── post RAG index request: " + embeddingsEndpoint);

            // Resolve embedding options: Layer 1 (endpoint defaults) + Layer 2 (BPMN
            // override)
            LLMOptions embeddingOptions = llmConfigService.getOptions(embeddingsEndpoint);
            embeddingOptions.merge(indexDefinition.getItemValueString("options"));

            // load the prompt template from the index definition!
            String promptTemplate = imixsAIPromptService.loadPromptTemplateByDefinition(indexDefinition);
            if (promptTemplate.isBlank()) {
                throw new PluginException(IndexService.class.getSimpleName(), ERROR_PROMPT,
                        "Missing prompt definition - verify model!");
            }

            // Build the prompt template...
            String llmPrompt = openAIAPIService.buildEmbeddingsPrompt(promptTemplate, workitem);
            if (llmPrompt.isBlank()) {
                throw new PluginException(
                        RAGRetrievalAdapter.class.getSimpleName(), ERROR_API,
                        "Unable to parse prompt for RAG indexing");
            }

            // Get category from indexDefinition (default: empty string for primary data)
            String category = indexDefinition.getItemValueString("category");
            if (category == null) {
                category = "";
            }

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
                        category,                    // Category parameter!
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

        } catch (PluginException e) {
            logger.severe("├── ⚠️ Failed to post embeddings: " + e.getMessage());
            throw e;
        } catch (ClusterException e) {
            throw new PluginException(RAGRetrievalAdapter.class.getSimpleName(), ERROR_API, e.getMessage(), e);
        }
    }

    /**
     * Updates the meta data of a workitem. The method calls
     * clusterService.updateMetaData()
     * 
     * @param indexDefinition
     * @param workitem
     * @throws PluginException
     * @throws AdapterException
     */
    public void updateMetadata(ItemCollection indexDefinition, ItemCollection workitem)
            throws PluginException {
        try {
            clusterService.updateMetaData(workitem.getUniqueID(), workitem.getWorkflowGroup(),
                    workitem.getTaskID());
        } catch (ClusterException e) {
            throw new PluginException(RAGRetrievalAdapter.class.getSimpleName(), ERROR_API, e.getMessage(), e);
        }
    }

    /**
     * This method calls the OpenAPIService with a given prompt and creates an index
     * event with the result
     * <p>
     * The method is triggered by the IndexOperator processing a event topic
     * 'rag.event.prompt'.The method initiates a LLM completion request and send the
     * result in a new rag.event.index event to be indexed in a isolated
     * 'rag.event.index'.
     * <p>
     * The prompt definition is must be defined in the index definition
     * 
     * @param indexDefinition
     * @param workitem
     * @throws PluginException
     * @throws AdapterException
     */
    public void promptWorkitem(ItemCollection indexDefinition, ItemCollection workitem) throws PluginException {
        String llmAPIEndpointCompletion = null;
        boolean llmAPIDebug = false;

        try {
            llmAPIEndpointCompletion = imixsAIPromptService.parseEndpointByBPMN(indexDefinition, "completion");
            // do we have a valid endpoint?
            if (llmAPIEndpointCompletion == null || llmAPIEndpointCompletion.isEmpty()) {
                throw new PluginException(RAGRetrievalAdapter.class.getSimpleName(), ERROR_API,
                        "imixs-ai configuration error: missing tag 'endpoint 'embeddings-completion'!");
            }

            llmAPIDebug = indexDefinition.getItemValueBoolean("debug");
            // load the prompt template from the index definition!
            String promptTemplate = imixsAIPromptService.loadPromptTemplateByDefinition(indexDefinition);
            if (promptTemplate == null || promptTemplate.isBlank()) {
                throw new PluginException(IndexService.class.getSimpleName(), ERROR_PROMPT,
                        "Missing prompt definition - verify model!");
            }

            imixsAIContextHandler.setWorkItem(workitem);

            // Layer 1: endpoint defaults from imixs-llm.xml
            LLMOptions options = llmConfigService.getOptions(llmAPIEndpointCompletion);
            // Layer 2: BPMN event override
            options.merge(indexDefinition.getItemValueString("options"));
            imixsAIContextHandler.setOptions(options);

            imixsAIContextHandler.addPromptDefinition(promptTemplate);

            String llmPrompt = imixsAIContextHandler.toString();
            // if we have a prompt we call the llm api endpoint
            if (llmPrompt != null && !llmPrompt.isBlank()) {

                // postPromptCompletion
                String completionResult = openAIAPIService.postPromptCompletion(imixsAIContextHandler,
                        llmAPIEndpointCompletion, llmAPIDebug);
                String resultMessage = openAIAPIService.processPromptResult(completionResult, "", workitem);
                indexDefinition.setItemValue(ITEM_PROMPT_TEMPLATE,
                        "<PromptDefinition><prompt><![CDATA[" + resultMessage + "]]></prompt></PromptDefinition>");

                // now create a new index eventLog entry
                eventLogService.createEvent(
                        IndexOperator.EVENTLOG_TOPIC_RAG_EVENT_INDEX,
                        workitem.getUniqueID(),
                        indexDefinition);

            } else {
                logger.finest(
                        "......no prompt definition found for " + workitem.getUniqueID());
            }

        } catch (PluginException e) {
            logger.severe("├── ⚠️ Failed to post embeddings: " + e.getMessage());
            throw e;
        } catch (AdapterException e) {
            logger.severe("├── ⚠️ Failed to post embeddings: " + e.getMessage());
            throw new PluginException(e);
        }
    }

    /**
     * Creates a RAG retrieval for a WorkItem based on RetrievalDefinitions
     * 
     * @param llmRetrievalDefinitions
     * @param workitem
     * @param event
     * @throws PluginException
     * @throws AdapterException
     */
    public void createRetrieval(List<ItemCollection> llmRetrievalDefinitions, ItemCollection workitem,
            ItemCollection event) throws PluginException, AdapterException {
        long processingTime = System.currentTimeMillis();
        String embeddingsEndpoint = null;
        boolean llmAPIDebug = false;
        try {
            for (ItemCollection indexDefinition : llmRetrievalDefinitions) {

                embeddingsEndpoint = imixsAIPromptService.parseEndpointByBPMN(indexDefinition, "embeddings");
                logger.info("├── post RAG Retrieval request: " + embeddingsEndpoint);

                // Resolve embedding options: Layer 1 (endpoint defaults) + Layer 2 (BPMN
                // override)
                LLMOptions embeddingOptions = llmConfigService.getOptions(embeddingsEndpoint);
                embeddingOptions.merge(indexDefinition.getItemValueString("options"));

                String itemRef = indexDefinition.getItemValueString("reference-item");

                String modelGroups = indexDefinition.getItemValueString("modelgroups");
                String categories = indexDefinition.getItemValueString("categories");
                String tasks = indexDefinition.getItemValueString("tasks");
                int maxResults = indexDefinition.getItemValueInteger("max-results");
                if (maxResults <= 0) {
                    // default
                    logger.warning("│   ├── max-results is not set, default=1");
                    maxResults = 1;
                }

                if (itemRef.isEmpty()) {
                    throw new PluginException(RAGRetrievalAdapter.class.getSimpleName(), ERROR_API,
                            "imixs-ai configuration error: no reference-item defined!");
                }
                if ("true".equalsIgnoreCase(indexDefinition.getItemValueString("debug"))) {
                    llmAPIDebug = true;
                }

                logger.info("│   ├── reference-item: " + itemRef);
                logger.info("│   ├── max-results: " + maxResults);
                logger.info("│   ├── categories: " + categories);
                logger.info("│   ├── modelgroups: " + modelGroups);
                logger.info("│   ├── tasks: " + tasks);
                String promptTemplate = imixsAIPromptService.loadPromptTemplate(indexDefinition, event);
                logger.info("│   ├── PromptTemplate: ");
                logger.info(promptTemplate);

                String llmPrompt = openAIAPIService.buildEmbeddingsPrompt(promptTemplate, workitem);
                if (llmPrompt.isBlank()) {
                    throw new PluginException(
                            RAGRetrievalAdapter.class.getSimpleName(), IndexService.ERROR_API,
                            "Unable to parse prompt for RAG indexing");
                }
                if (llmAPIDebug) {
                    logger.info("│   ├── Total Prompt Length = " + llmPrompt.length());
                    logger.info("│   ├── Prompt: ");
                    logger.info(llmPrompt);
                }

                // retrieve prompt....
                List<Float> embeddings = openAIAPIService.postEmbedding(
                        llmPrompt, embeddingsEndpoint, embeddingOptions, llmAPIDebug);
                if (llmAPIDebug) {
                    logger.info("├── ⇨ " + embeddings.size() + " floats stored in RAG db.");
                }
                // search cassandra
                List<RetrievalResult> retrievalResultList = clusterService.searchEmbeddings(embeddings, maxResults,
                        categories, modelGroups, tasks);
                List<String> listOfIds = retrievalResultList.stream()
                        .map(RetrievalResult::getUniqueId)
                        .collect(Collectors.toList());
                workitem.setItemValue(itemRef, listOfIds);
                if (llmAPIDebug) {
                    logger.info(
                            "├── found " + retrievalResultList.size() + " matches");
                    logger.info(
                            "├── ✅ Total processing time: " + (System.currentTimeMillis() - processingTime) + "ms");
                }
            }
        } catch (PluginException e) {
            logger.severe("├── ⚠️ Failed to post embeddings: " + e.getMessage());
            throw e;
        } catch (ClusterException e) {
            throw new PluginException(RAGRetrievalAdapter.class.getSimpleName(), ERROR_API, e.getMessage(), e);
        }
    }

}
