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
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.ai.api.APIUtil;
import org.imixs.ai.api.ImixsAIContextHandler;
import org.imixs.ai.api.LLMConfigService;
import org.imixs.ai.api.LLMOptions;
import org.imixs.ai.api.OpenAIAPIService;
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
 * The EmbeddingService prepares the text of a business document to be indexed
 * in the RAG index. The text is built from a BPMN prompt template and the data
 * of the workitem. The result is written into an EventLog entry with the topic
 * 'rag.event.index'.
 * <p>
 * The embeddings themselves are not computed here. The EventLog entry is
 * processed asynchronously, so the processing cycle never waits for an LLM
 * call. The embedding endpoint and its options are defined centrally - one
 * embedding setup per workflow instance - so all vectors are comparable.
 * <p>
 * The event data of a 'rag.event.index' entry contains:
 * <ul>
 * <li>{@link RAGUtil#ITEM_RAG_CATEGORY} - the business view of the
 * embeddings</li>
 * <li>{@link RAGUtil#ITEM_RAG_TEXT} - the text to be embedded</li>
 * <li>{@link RAGUtil#ITEM_DEBUG} - optional debug flag</li>
 * </ul>
 * <p>
 * The service also provides the search method for the RAG index.
 *
 * @see DocumentEvent
 * @version 2.0
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

    // Optional task prefix for the query embedding (e.g. 'search_query:')
    @Inject
    @ConfigProperty(name = RAGUtil.ENV_EMBEDDINGS_PREFIX_QUERY)
    Optional<String> prefixQuery;

    /**
     * This method sends a LLM completion request based on the prompt definition
     * and indexes the result.
     * <p>
     * The method is triggered asynchronously by processing an event with the topic
     * 'rag.event.prompt'. The completion result replaces the prompt template of
     * the definition, and a regular 'rag.event.index' entry is created by
     * {@link #indexWorkitem}. So both modes create the same event format.
     * <p>
     * The prompt definition must be defined in the index definition.
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
                // a ']]>' in the result would terminate the CDATA section - split it
                String cdataSafeResult = resultMessage.replace("]]>", "]]]]><![CDATA[>");
                indexDefinition.setItemValue(APIUtil.ITEM_PROMPT_TEMPLATE,
                        "<PromptDefinition><prompt><![CDATA[" + cdataSafeResult + "]]></prompt></PromptDefinition>");

                // create a regular index event - same format as the INDEX mode
                indexWorkitem(indexDefinition, workitem);

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
                // the debug flag is evaluated per definition
                boolean llmAPIDebug = "true".equalsIgnoreCase(indexDefinition.getItemValueString("debug"));

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

                List<ItemCollection> matches = search(llmPrompt, embeddingsEndpoint, embeddingOptions, maxResults,
                        categories, llmAPIDebug);
                List<String> listOfIds = matches.stream().map(m -> m.getItemValueString("id"))
                        .collect(Collectors.toList());
                workitem.setItemValue(itemRef, listOfIds);
                if (llmAPIDebug) {
                    logger.info("├── found " + listOfIds.size() + " matches");
                    logger.info("├── ✅ Total processing time: " + (System.currentTimeMillis() - processingTime)
                            + "ms");
                }
            }
        } catch (PluginException e) {
            logger.severe("├── ⚠️ Failed to post embeddings: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Searches the RAG index for documents that are similar to a given text.
     * <p>
     * The method computes the embedding of the text (with the optional query
     * prefix) and sends the vector to the search endpoint of the RAG service. The
     * result is sorted by similarity. Each match has the items 'id', 'similarity'
     * and 'contentChunk'.
     *
     * @param text               the query text
     * @param embeddingsEndpoint the embeddings endpoint used to compute the vector
     * @param embeddingOptions   the options for the embeddings endpoint
     * @param maxResults         the maximum number of documents
     * @param categories         optional category filter
     * @param debug              prints debug information
     * @return list of matches, empty if nothing was found
     * @throws PluginException  if the query or the configuration is invalid
     * @throws AdapterException if the RAG service request failed
     */
    public List<ItemCollection> search(String text, String embeddingsEndpoint, LLMOptions embeddingOptions,
            int maxResults, String categories, boolean debug) throws PluginException, AdapterException {

        long processingTime = System.currentTimeMillis();

        if (text == null || text.isBlank()) {
            throw new PluginException(EmbeddingService.class.getSimpleName(), APIUtil.ERROR_API,
                    "Unable to search the RAG index: query text is empty!");
        }

        // The prefix is only used for the query embedding
        String embeddingInput = RAGUtil.applyPrefix(prefixQuery.orElse(""), text.trim());
        List<Float> embeddings = openAIAPIService.postEmbedding(
                embeddingInput, embeddingsEndpoint, embeddingOptions, debug);

        List<ItemCollection> matches = null;
        logger.warning("search not yet implemented!");
        // matches = indexClient.search(embeddings, maxResults, filter);
        if (debug) {
            logger.info("│   ├── 🔎 RAG search: " + matches.size() + " match(es) in "
                    + (System.currentTimeMillis() - processingTime) + "ms (categories: " + categories + ")");
        }
        return matches;

    }

    /**
     * This method builds the text to be indexed from a BPMN prompt definition and
     * creates an EventLog entry with the topic 'rag.event.index'. The embeddings
     * are computed asynchronously when the event is processed.
     * <p>
     * The index definition must provide a category. The category defines the
     * business view of the embeddings (e.g. 'risk', 'contract').
     *
     * @param indexDefinition - the RAG index definition
     * @param workitem        - the workitem to be indexed
     * @throws PluginException  if the definition is invalid
     * @throws AdapterException if the prompt can not be built
     */
    public void indexWorkitem(ItemCollection indexDefinition, ItemCollection workitem)
            throws PluginException, AdapterException {
        long processingTime = System.currentTimeMillis();
        boolean debug = indexDefinition.getItemValueBoolean("debug");
        try {
            // the category is required - it defines the business view of the embeddings
            String category = indexDefinition.getItemValueString("category");
            if (category.isBlank()) {
                throw new PluginException(EmbeddingService.class.getSimpleName(), APIUtil.ERROR_API,
                        "imixs-ai configuration error: missing category in RAG INDEX definition!");
            }

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

            if (debug) {
                logger.info("├── 🔃 RAG index request for " + workitem.getUniqueID());
                logger.info("│   ├── Category: " + category);
                logger.info("│   ├── Total Prompt Length = " + llmPrompt.length());
                logger.info("│   ├── Prompt: ");
                logger.info(llmPrompt);
            }

            ItemCollection embeddingData = new ItemCollection();
            embeddingData.setItemValue(RAGUtil.ITEM_RAG_CATEGORY, category);
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