/*  
 *  Imixs-Workflow 
 *  
 *  Copyright (C) 2001-2020 Imixs Software Solutions GmbH,  
 *  http://www.imixs.com
 *  
 *  This program is free software; you can redistribute it and/or 
 *  modify it under the terms of the GNU General Public License 
 *  as published by the Free Software Foundation; either version 2 
 *  of the License, or (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful, 
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of 
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU 
 *  General Public License for more details.
 *  
 *  You can receive a copy of the GNU General Public
 *  License at http://www.gnu.org/licenses/gpl.html
 *  
 *  Project: 
 *      https://www.imixs.org
 *      https://github.com/imixs/imixs-workflow
 *  
 *  Contributors:  
 *      Imixs Software Solutions GmbH - Project Management
 *      Ralph Soika - Software Developer
 */

package org.imixs.ai.workflow;

import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.SignalAdapter;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.inject.Inject;
import jakarta.json.JsonObject;

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
 * The result-item defines the item to store the result.
 * Optional also result-events can be defined to handle more complex business
 * rules.
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
 * The field 'items' contains a list of item names. This list will be
 * stored in the item "ai.suggest.items". An UI can use this information for
 * additional input support (e.g. a suggest list)
 * The field 'mode' provides a suggest mode for a UI component. The information
 * is stored in the item 'ai.suggest.mode'
 * 
 * @author Ralph Soika
 * @version 1.0
 *
 */

public class OpenAIAPIAdapter implements SignalAdapter {

    public static final String ML_ENTITY = "entity";
    public static final String API_ERROR = "API_ERROR";
    public static final String LLM_PROMPT = "PROMPT";
    public static final String LLM_SUGGEST = "SUGGEST";

    public static final int API_EVENT_SUCCESS = 110;
    public static final int API_EVENT_FAILURE = 90;

    private static Logger logger = Logger.getLogger(OpenAIAPIAdapter.class.getName());

    @Inject
    @ConfigProperty(name = OpenAIAPIService.ENV_LLM_SERVICE_ENDPOINT)
    Optional<String> serviceEndpoint;

    @Inject
    private WorkflowService workflowService;

    @Inject
    private OpenAIAPIService llmService;

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
     * to the Imixs-AI Analyse service
     * endpoint
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

        logger.finest("...running api adapter...");

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
                    llmAPIEndpoint = parseLLMEndpointByBPMN(promptDefinition);
                    llmAPIResultEvent = promptDefinition.getItemValueString("result-event");
                    llmAPIResultItem = promptDefinition.getItemValueString("result-item");
                    if ("true".equalsIgnoreCase(promptDefinition.getItemValueString("debug"))) {
                        llmAPIDebug = true;
                    }

                    // do we have a valid endpoint?
                    if (llmAPIEndpoint == null || llmAPIEndpoint.isEmpty()) {
                        throw new PluginException(OpenAIAPIAdapter.class.getSimpleName(), API_ERROR,
                                "imixs-ai llm service endpoint is empty!");
                    }
                    logger.info("post Llama-cpp request: " + llmAPIEndpoint);

                    // Build the prompt template....
                    String promptTemplate = llmService.loadPromptTemplate(event);
                    String llmPrompt = llmService.buildPrompt(promptTemplate, workitem);
                    // if we have a prompt we call the llm api endpoint
                    if (!llmPrompt.isEmpty()) {
                        if (llmAPIDebug) {
                            logger.info("===> Total Prompt Length = " + llmPrompt.length());
                            logger.info("===> Prompt: ");
                            logger.info(llmPrompt);
                        }
                        // postPromptCompletion
                        JsonObject jsonPrompt = llmService.buildJsonPromptObject(llmPrompt, false,
                                workitem.getItemValueString("ai.prompt.prompt_options"));
                        String completionResult = llmService.postPromptCompletion(jsonPrompt, llmAPIEndpoint);
                        // process the ai.result....
                        if (llmAPIDebug) {
                            logger.info("===> Completion Result: ");
                            logger.info(completionResult);
                        }
                        llmService.processPromptResult(completionResult, workitem, llmAPIResultItem,
                                llmAPIResultEvent);
                    } else {
                        logger.finest(
                                "......no prompt definition found for " + workitem.getUniqueID());
                    }
                }

            }

            // verify if we also have an optional SUGGEST configuration (only one definition
            // is supported!)
            if (llmSuggestDefinitions != null && llmSuggestDefinitions.size() > 0) {
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
            logger.info("===> Total Processing Time=" + (System.currentTimeMillis() - processingTime) + "ms");
        }
        return workitem;
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
