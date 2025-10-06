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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.ai.rag.cluster.ClusterException;
import org.imixs.ai.rag.cluster.ClusterService;
import org.imixs.ai.rag.cluster.RetrievalResult;
import org.imixs.ai.rag.events.RAGEventService;
import org.imixs.ai.workflow.OpenAIAPIConnector;
import org.imixs.ai.workflow.OpenAIAPIService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.SignalAdapter;
import org.imixs.workflow.engine.EventLogService;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.inject.Inject;

/**
 * The RAGAdapter is used for Retrieval-Augmented Generation workflows.
 * <p>
 * The adapter operates in two modes:
 * <p>
 * In the 'INDEX' mode the adapter evaluates an associated imixs-ai prompt
 * definition and generates - based on the given prompt template - a text to be
 * indexed in the RAG database.
 * 
 * <pre>
 * {@code
<imixs-ai name="INDEX">
    <endpoint><propertyvalue>llm.service.endpoint</propertyvalue></endpoint>
    <debug>true</debug>
</imixs-ai>
 * }
 * </pre>
 * <p>
 * In the 'RETRIEVAL' mode, the adapter evaluates an associated imixs-ai prompt
 * definition to get retrieval embeddings from the RAG database.
 * 
 * <pre>
 * {@code
<imixs-ai name="RETRIEVAL">
    <endpoint><propertyvalue>llm.service.endpoint</propertyvalue></endpoint>
    <item-ref>[ITEMNAME]</item-ref>
    <debug>true</debug>
</imixs-ai>
 * }
 * </pre>
 * 
 * In both modes the Endpoint defines the Rest API endpoint of the llama-cpp
 * http server or any compatible OpenAI / Open API rest service endpoint.
 * <p>
 * The Prompt Template is based on the concepts of Imixs-AI:
 * 
 * <pre>
 * {@code
<?xml version="1.0" encoding="UTF-8"?>
<IndexDefinition>
    < index_options>{"n_predict": 4096, "temperature": 0}< /index_options>
    <![CDATA[<text><itemvalue>$workflowgroup</itemvalue>: <itemvalue>$workflowstatus</itemvalue>
        # Summary
        <itemvalue>$workflowsummary</itemvalue>
        <itemvalue>offer.summary</itemvalue>
    ]]></text>
</IndexDefinition> 
 * }
 * </pre>
 * 
 * The index Process is
 * 
 * @author Ralph Soika
 * @version 1.0
 *
 */
public class RAGAdapter implements SignalAdapter {

    public static final String ML_ENTITY = "entity";
    public static final String API_ERROR = "API_ERROR";

    public static final String RAG_INDEX = "INDEX";
    public static final String RAG_RETRIEVAL = "RETRIEVAL";

    private static Logger logger = Logger.getLogger(RAGAdapter.class.getName());

    @Inject
    @ConfigProperty(name = OpenAIAPIConnector.ENV_LLM_SERVICE_ENDPOINT)
    Optional<String> serviceEndpoint;

    @Inject
    private WorkflowService workflowService;

    @Inject
    EventLogService eventLogService;

    @Inject
    private OpenAIAPIService llmService;

    @Inject
    private ClusterService clusterService;

    /**
     * Default Constructor
     */
    public RAGAdapter() {
        super();
    }

    /**
     * CDI Constructor to inject WorkflowService
     * 
     * @param workflowService
     */
    @Inject
    public RAGAdapter(WorkflowService workflowService) {
        super();
        this.workflowService = workflowService;
    }

    public void setWorkflowService(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /**
     * This method parses the LLM Event definitions.
     * 
     * For each PROMPT the method posts a context data (e.g text from an attachment)
     * to the Imixs-AI Analyse service endpoint
     * 
     * @throws PluginException
     */
    public ItemCollection execute(ItemCollection workitem, ItemCollection event)
            throws AdapterException, PluginException {

        logger.finest("...running api adapter...");

        // read optional configuration form the model or imixs.properties....
        List<ItemCollection> llmIndexDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-ai",
                RAG_INDEX, workitem, false);
        List<ItemCollection> llmRetrievalDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-ai",
                RAG_RETRIEVAL, workitem, false);

        // verify if we have an INDEX configuration
        if (llmIndexDefinitions != null && llmIndexDefinitions.size() > 0) {
            createIndex(llmIndexDefinitions, workitem, event);
        }

        // verify if we have an RETRIEVAL configuration
        if (llmRetrievalDefinitions != null && llmRetrievalDefinitions.size() > 0) {
            createRetrieval(llmRetrievalDefinitions, workitem, event);
        }

        return workitem;
    }

    /**
     * Creates a RAG Index for a Workitem based on IndexDefinitions
     * 
     * @param llmIndexDefinitions
     * @param workitem
     * @param event
     * @throws PluginException
     * @throws AdapterException
     */
    private void createIndex(List<ItemCollection> llmIndexDefinitions, ItemCollection workitem, ItemCollection event)
            throws PluginException, AdapterException {
        long processingTime = System.currentTimeMillis();
        String llmAPIEndpoint = null;
        boolean llmAPIDebug = false;
        try {
            for (ItemCollection indexDefinition : llmIndexDefinitions) {
                llmAPIEndpoint = parseLLMEndpointByBPMN(indexDefinition);
                // do we have a valid endpoint?
                if (llmAPIEndpoint == null || llmAPIEndpoint.isEmpty()) {
                    throw new PluginException(RAGAdapter.class.getSimpleName(), API_ERROR,
                            "imixs-ai configuration error: no llm service endpoint defined!");
                }
                if ("true".equalsIgnoreCase(indexDefinition.getItemValueString("debug"))) {
                    llmAPIDebug = true;
                }

                logger.info("├── post RAG index request: " + llmAPIEndpoint);
                String promptTemplate = llmService.loadPromptTemplate(event);
                indexDefinition.setItemValue("promptTemplate", promptTemplate);
                indexDefinition.setItemValue("debug", llmAPIDebug);
                indexDefinition.setItemValue("endpoint", llmAPIEndpoint);

                if (llmAPIDebug) {
                    logger.info("│   ├── PromptTemplate: ");
                    logger.info(promptTemplate);
                }

                // Build the prompt template...
                String llmPrompt = llmService.buildPrompt(promptTemplate, workitem);
                if (llmPrompt.isBlank()) {
                    throw new PluginException(
                            RAGAdapter.class.getSimpleName(), API_ERROR,
                            "Unable to parse prompt for RAG indexing");
                }
                indexDefinition.setItemValue("prompt", llmPrompt);

                // send eventLog ....
                eventLogService.createEvent(
                        RAGEventService.EVENTLOG_TOPIC_RAG_EVENT_INDEX,
                        workitem.getUniqueID(),
                        indexDefinition);

                if (llmAPIDebug) {
                    logger.info(
                            "├── ✅ Total processing time: " + (System.currentTimeMillis() - processingTime) + "ms");
                }
            }
        } catch (PluginException e) {
            logger.severe("├── ⚠️ Failed to post embeddings: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Creates a RAG retrieval for a WorkItem based on RetrievalDefinitions
     * 
     * @param llmRetrievalfDefinitions
     * @param workitem
     * @param event
     * @throws PluginException
     * @throws AdapterException
     */
    private void createRetrieval(List<ItemCollection> llmRetrievalfDefinitions, ItemCollection workitem,
            ItemCollection event) throws PluginException, AdapterException {
        long processingTime = System.currentTimeMillis();
        String llmAPIEndpoint = null;
        boolean llmAPIDebug = false;
        try {
            for (ItemCollection indexDefinition : llmRetrievalfDefinitions) {
                llmAPIEndpoint = parseLLMEndpointByBPMN(indexDefinition);
                String itemRef = indexDefinition.getItemValueString("reference-item");
                // do we have a valid endpoint?
                if (llmAPIEndpoint == null || llmAPIEndpoint.isEmpty()) {
                    throw new PluginException(RAGAdapter.class.getSimpleName(), API_ERROR,
                            "imixs-ai configuration error: no llm service endpoint defined!");
                }
                if (itemRef.isEmpty()) {
                    throw new PluginException(RAGAdapter.class.getSimpleName(), API_ERROR,
                            "imixs-ai configuration error: no reference-item defined!");
                }
                if ("true".equalsIgnoreCase(indexDefinition.getItemValueString("debug"))) {
                    llmAPIDebug = true;
                }

                logger.info("├── post RAG Retrieval request: " + llmAPIEndpoint);
                String promptTemplate = llmService.loadPromptTemplate(event);
                logger.info("│   ├── PromptTemplate: ");
                logger.info(promptTemplate);
                String llmPrompt = llmService.buildPrompt(promptTemplate, workitem);
                if (llmPrompt.isBlank()) {
                    throw new PluginException(
                            RAGAdapter.class.getSimpleName(), API_ERROR,
                            "Unable to parse prompt for RAG indexing");
                }
                if (llmAPIDebug) {
                    logger.info("│   ├── Total Prompt Length = " + llmPrompt.length());
                    logger.info("│   ├── Prompt: ");
                    logger.info(llmPrompt);
                }

                // retrieve prompt....
                List<Float> embeddings = llmService.postEmbedding(llmPrompt, llmAPIEndpoint, llmAPIDebug);
                if (llmAPIDebug) {
                    logger.info("├── ⇨ " + embeddings.size() + " floats stored in RAG db.");
                }
                // search cassandra
                List<RetrievalResult> retrievalResultList = clusterService.searchEmbeddings(embeddings, 5);
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
            throw new PluginException(RAGAdapter.class.getSimpleName(), API_ERROR, e.getMessage(), e);
        }
    }

    /**
     * This helper method parses the ml api endpoint either provided by a model
     * definition or a imixs.property or an environment variable.
     * <p>
     * If not api endpoint is defined by the model the adapter uses the default api
     * endpoint.
     * 
     * @param llmPrompt
     * @return
     * @throws PluginException
     */
    private String parseLLMEndpointByBPMN(ItemCollection llmPrompt) throws PluginException {
        boolean debug = logger.isLoggable(Level.FINE);
        String llmAPIEndpoint = null;

        // Test if the model provides a API Endpoint.
        llmAPIEndpoint = null;
        if (llmPrompt != null) {
            llmAPIEndpoint = llmPrompt.getItemValueString("endpoint");
        }

        // switch to default api endpoint?
        if (llmAPIEndpoint == null || llmAPIEndpoint.isEmpty()) {
            // set defautl api endpoint if defined
            if (serviceEndpoint.isPresent() && !serviceEndpoint.get().isEmpty()) {
                llmAPIEndpoint = serviceEndpoint.get();
            }
        }
        if (debug) {
            logger.info("......llm api endpoint " + llmAPIEndpoint);
        }

        // adapt text...
        llmAPIEndpoint = workflowService.adaptText(llmAPIEndpoint, null);
        if (!llmAPIEndpoint.endsWith("/")) {
            llmAPIEndpoint = llmAPIEndpoint + "/";
        }
        return llmAPIEndpoint;
    }

}
