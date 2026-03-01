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

package org.imixs.ai.workflow;

import java.util.List;
import java.util.logging.Logger;

import org.imixs.ai.ImixsAIContextHandler;
import org.imixs.ai.api.OpenAIAPIService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.SignalAdapter;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.inject.Inject;

/**
 * The OpenAIAPIAdapter is used for text completion requests by a LLMs.
 * <p>
 * The adapter automatically parses the imixs-ai prompt definition and generates
 * a prompt template to be send to an OpenAIApi Endpoint.
 * <p>
 * The imixs-ai prompt definition defines the API endpoint and processing events
 * to adapt the prompt template or the completion result. The result is stored
 * in the item 'ai.result'
 * <p>
 * The Adapter can be configured by a event entity.
 * <p>
 * Example imixs-ai Prompt:
 * 
 * <pre>
 * {@code
        <imixs-ai name="PROMPT">
            <endpoint>https://localhost:8111/</endpoint>
            <result-item>invoice.summary</result-item>
            <result-event>JSON</result-event>
        </imixs-ai>
 * }
 * </pre>
 * 
 * The Endpoint defines the Rest API endpoint of the llama-cpp http server or
 * any compatible OpenAI / Open API rest service endpoint.
 * 
 * The result-item defines the item to store the result. Optional also
 * result-events can be defined to handle more complex business rules.
 * <p>
 * Optional an imixs-ai SUGGEST configuration can be provided.
 * 
 * <pre>
 * {@code
        <imixs-ai name="SUGGEST">
            <items>invoice.number</items>
            <mode>ON|OFF</mode>
        </imixs-ai>
 * }
 * </pre>
 * 
 * The field 'items' contains a list of item names. This list will be stored in
 * the item "ai.suggest.items". An UI can use this information for additional
 * input support (e.g. a suggest list) The field 'mode' provides a suggest mode
 * for a UI component. The information is stored in the item 'ai.suggest.mode'
 * 
 * @author Ralph Soika
 * @version 1.0
 *
 */

public class OpenAIAPIAdapter implements SignalAdapter {

    public static final String ML_ENTITY = "entity";

    public static final String LLM_PROMPT = "PROMPT";
    public static final String LLM_SUGGEST = "SUGGEST";

    public static final int API_EVENT_SUCCESS = 110;
    public static final int API_EVENT_FAILURE = 90;

    private static Logger logger = Logger.getLogger(OpenAIAPIAdapter.class.getName());

    @Inject
    protected WorkflowService workflowService;

    @Inject
    protected OpenAIAPIService llmService;

    @Inject
    protected ImixsAIPromptService imixsAIPromptService;

    @Inject
    protected ImixsAIContextHandler imixsAIContextHandler;

    /**
     * Default Constructor
     */
    public OpenAIAPIAdapter() {
        super();
    }

    /**
     * CDI Constructor to inject WorkflowService
     * 
     * @param workflowService
     */
    @Inject
    public OpenAIAPIAdapter(WorkflowService workflowService) {
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
        String llmAPIEndpoint = null;
        String llmAPIResultEvent = null;
        String llmAPIResultItem = null;
        boolean llmAPIDebug = false;
        long processingTime = System.currentTimeMillis();
        logger.finest("├── Running OpenAIAPIAdapter...");

        // read optional configuration form the model or imixs.properties....
        try {

            List<ItemCollection> llmPromptDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-ai",
                    LLM_PROMPT, workitem, false);
            List<ItemCollection> llmSuggestDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-ai",
                    LLM_SUGGEST, workitem, false);
            /**
             * Iterate over each PROMPT definition and process the prompt
             */
            if (llmPromptDefinitions != null) {
                for (ItemCollection promptDefinition : llmPromptDefinitions) {
                    llmAPIEndpoint = imixsAIPromptService.parseEndpointByBPMN(promptDefinition);
                    llmAPIResultEvent = promptDefinition.getItemValueString("result-event");
                    llmAPIResultItem = promptDefinition.getItemValueString("result-item");
                    if ("true".equalsIgnoreCase(promptDefinition.getItemValueString("debug"))) {
                        llmAPIDebug = true;
                    }

                    // do we have a valid endpoint?
                    if (llmAPIEndpoint == null || llmAPIEndpoint.isEmpty()) {
                        throw new PluginException(OpenAIAPIAdapter.class.getSimpleName(), OpenAIAPIService.ERROR_API,
                                "imixs-ai llm service endpoint is empty!");
                    }

                    if (llmAPIDebug) {
                        logger.info("├── Running OpenAIAPIAdapter mode=PROMPT.... ");
                    }

                    // Build the prompt template....

                    String promptTemplate = imixsAIPromptService.loadPromptTemplate(promptDefinition, event);
                    imixsAIContextHandler.setWorkItem(workitem);
                    imixsAIContextHandler.addPromptDefinition(promptTemplate);

                    String completionResult = llmService.postPromptCompletion(imixsAIContextHandler, llmAPIEndpoint,
                            llmAPIDebug);
                    // process the ai.result....
                    String resultMessage = llmService.processPromptResult(completionResult, llmAPIResultEvent,
                            workitem);

                    // store the result message
                    if (llmAPIResultItem != null && !llmAPIResultItem.isEmpty()) {
                        workitem.setItemValue(llmAPIResultItem, resultMessage);
                    }

                }

            }

            // verify if we also have an optional SUGGEST configuration (only one definition
            // is supported!)
            if (llmSuggestDefinitions != null && llmSuggestDefinitions.size() > 0) {
                if (llmAPIDebug) {
                    logger.info("├── Running OpenAIAPIAdapter mode=SUGGEST.... ");
                }

                ItemCollection suggestDefinition = llmSuggestDefinitions.get(0);
                String llmSuggestItems = suggestDefinition.getItemValueString("items");
                String llmSuggestMode = suggestDefinition.getItemValueString("mode");
                // do we have a suggest-mode?
                if (llmSuggestMode.equalsIgnoreCase("ON") || llmSuggestMode.equalsIgnoreCase("OFF")) {
                    workitem.setItemValue(OpenAIAPIService.ITEM_SUGGEST_MODE, llmSuggestMode.toUpperCase());
                } else {
                    workitem.setItemValue(OpenAIAPIService.ITEM_SUGGEST_MODE, "OFF");
                }
                // do we have a suggest-item list?
                String[] suggestItemList = llmSuggestItems.split(",");
                workitem.removeItem(OpenAIAPIService.ITEM_SUGGEST_ITEMS);
                for (String item : suggestItemList) {
                    workitem.appendItemValue(OpenAIAPIService.ITEM_SUGGEST_ITEMS, item.trim());
                }
            }

        } catch (PluginException e) {
            logger.severe("Unable to parse item definitions for 'imixs-ai', verify model - " + e.getMessage());
            throw new PluginException(
                    OpenAIAPIAdapter.class.getSimpleName(), e.getErrorCode(),
                    "Unable to parse item definitions for 'imixs-ai', verify model - " + e.getMessage(), e);
        }

        if (llmAPIDebug) {
            logger.info("└── OpenAIAPIAdapter completed in " + (System.currentTimeMillis() - processingTime) + "ms");
        }
        return workitem;
    }

}
