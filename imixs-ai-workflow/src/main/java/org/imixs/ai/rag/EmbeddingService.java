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

import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.imixs.ai.api.APIUtil;
import org.imixs.ai.api.ImixsAIContextHandler;
import org.imixs.ai.api.LLMConfigService;
import org.imixs.ai.api.LLMOptions;
import org.imixs.ai.api.OpenAIAPIService;
import org.imixs.ai.workflow.ImixsAIPromptService;
import org.imixs.melman.RestAPIException;
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
 * The EmbeddingService is responsible to create embeddings of a BPMN prompt
 * template and send the result via EventLog Entries to the RAG Service. RAG
 * Events are handled by the IndexOperator remotely.
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
public class EmbeddingService {

    private static Logger logger = Logger.getLogger(EmbeddingService.class.getName());

    @Inject
    ImixsAIPromptService imixsAIPromptService;

    @Inject
    private OpenAIAPIService openAIAPIService;

    @Inject
    LLMConfigService llmConfigService;

    @Inject
    ImixsAIContextHandler imixsAIContextHandler;

    @Inject
    EventLogService eventLogService;

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
    public void promptWorkitem(ItemCollection indexDefinition, ItemCollection workitem)
            throws PluginException {
        String llmAPIEndpointCompletion = null;
        boolean llmAPIDebug = false;

        try {
            llmAPIEndpointCompletion = imixsAIPromptService.parseEndpointByBPMN(indexDefinition, "completion");
            // do we have a valid endpoint?
            if (llmAPIEndpointCompletion == null || llmAPIEndpointCompletion.isEmpty()) {
                throw new PluginException(EmbeddingService.class.getSimpleName(), APIUtil.ERROR_API,
                        "imixs-ai configuration error: missing tag 'endpoint 'embeddings-completion'!");
            }

            llmAPIDebug = indexDefinition.getItemValueBoolean("debug");
            // load the prompt template from the index definition!
            String promptTemplate = imixsAIPromptService.loadPromptTemplateByDefinition(indexDefinition);
            if (promptTemplate == null || promptTemplate.isBlank()) {
                throw new PluginException(EmbeddingService.class.getSimpleName(), APIUtil.ERROR_PROMPT,
                        "Missing prompt definition - verify model!");
            }

            imixsAIContextHandler.setWorkItem(workitem);

            // Layer 1: endpoint defaults from imixs-llm.xml
            LLMOptions options = llmConfigService.getOptions(llmAPIEndpointCompletion);
            // Layer 2: BPMN event override
            options.merge(indexDefinition.getItemValueString("options"));
            imixsAIContextHandler.setLlmOptions(options);

            imixsAIContextHandler.loadPromptDefinition(promptTemplate);

            String llmPrompt = imixsAIContextHandler.toString();
            // if we have a prompt we call the llm api endpoint
            if (llmPrompt != null && !llmPrompt.isBlank()) {

                // postPromptCompletion
                String completionResult = openAIAPIService.postPromptCompletion(imixsAIContextHandler,
                        llmAPIEndpointCompletion, llmAPIDebug);
                String resultMessage = openAIAPIService.processPromptResult(completionResult, "", workitem);
                indexDefinition.setItemValue(APIUtil.ITEM_PROMPT_TEMPLATE,
                        "<PromptDefinition><prompt><![CDATA[" + resultMessage + "]]></prompt></PromptDefinition>");

                // now create a new index eventLog entry
                eventLogService.createEvent(
                        RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_INDEX,
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
                    throw new PluginException(EmbeddingService.class.getSimpleName(), APIUtil.ERROR_API,
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
                            EmbeddingService.class.getSimpleName(), APIUtil.ERROR_API,
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

                ItemCollection embeddingData = new ItemCollection();
                embeddingData.setItemValue("embeddings", embeddings);
                embeddingData.setItemValue("categories", categories);
                embeddingData.setItemValue("modelGroups", modelGroups);
                embeddingData.setItemValue("tasks", tasks);

                // search index
                String endpoint = "...";
                String token = "...";
                try {
                    IndexClient indexClient = new IndexClient(endpoint, token);
                    List<ItemCollection> matches = indexClient.search(embeddings, maxResults, categories, modelGroups,
                            tasks);
                    List<String> listOfIds = matches.stream().map(m -> m.getItemValueString("id"))
                            .collect(Collectors.toList());
                    workitem.setItemValue(itemRef, listOfIds);
                    if (llmAPIDebug) {
                        logger.info(
                                "├── found " + listOfIds.size() + " matches");
                        logger.info(
                                "├── ✅ Total processing time: " + (System.currentTimeMillis() - processingTime) + "ms");
                    }
                } catch (RestAPIException e) {
                    throw new AdapterException(RAGRetrievalAdapter.class.getSimpleName(), e.getErrorCode(),
                            e.getMessage(), e);
                }

            }
        } catch (PluginException e) {
            logger.severe("├── ⚠️ Failed to post embeddings: " + e.getMessage());
            throw e;
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
    public void updateMetadata(ItemCollection workitem)
            throws PluginException {

        ItemCollection workitemMetaData = new ItemCollection();
        workitemMetaData.setItemValue("$workflowgroup", workitem.getWorkflowGroup());
        workitemMetaData.setItemValue("$taskid", workitem.getTaskID());

        // create eventlog entry
        eventLogService.createEvent(RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_UPDATE, workitem.getUniqueID(),
                workitemMetaData);

    }

    /**
     * This method computes the embeddings form a BPMN AI-Prompt definition. The
     * method stores the final prompt result inot a data entry and creates a
     * eventLog entry that will be asynchronous processed by the Imixs-AI RAG
     * Service.
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
            if (promptTemplate == null || promptTemplate.isBlank()) {
                throw new PluginException(EmbeddingService.class.getSimpleName(), APIUtil.ERROR_PROMPT,
                        "Missing prompt definition - verify model!");
            }

            // Build the prompt template...
            String llmPrompt = openAIAPIService.buildEmbeddingsPrompt(promptTemplate, workitem);
            if (llmPrompt.isBlank()) {
                throw new PluginException(
                        EmbeddingService.class.getSimpleName(), APIUtil.ERROR_API,
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

            ItemCollection embeddingData = new ItemCollection();
            embeddingData.setItemValue(RAGUtil.ITEM_RAG_ENDPOINT, embeddingsEndpoint);
            embeddingData.setItemValue(RAGUtil.ITEM_RAG_EMBEDDINGS_OPTIONS, embeddingOptions.toString());
            embeddingData.appendItemValue(RAGUtil.ITEM_RAG_CATEGORY, category);
            embeddingData.setItemValue(RAGUtil.ITEM_RAG_TEXT, llmPrompt);
            embeddingData.setItemValue(RAGUtil.ITEM_DEBUG, debug);
            // create eventLog entry
            eventLogService.createEvent(RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_INDEX, workitem.getUniqueID(), embeddingData);

            if (debug) {
                logger.info(
                        "├── ✅ Total processing time: " + (System.currentTimeMillis() - processingTime) + "ms");
            }

        } catch (PluginException e) {
            logger.severe("├── ⚠️ Failed to post embeddings: " + e.getMessage());
            throw e;
        }
    }

}
