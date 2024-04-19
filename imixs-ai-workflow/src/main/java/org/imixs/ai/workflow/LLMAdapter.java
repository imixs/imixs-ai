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
import java.util.regex.Pattern;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.SignalAdapter;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.exceptions.ProcessingErrorException;

import jakarta.inject.Inject;

/**
 * This adapter class is used for text analysis with LLMs.
 * <p>
 * The LLMAdapter automatically analyses the text content of a attached files by
 * a given prompt template. The result is stored in the item 'ai.result' which
 * is typically a json object.
 * <p>
 * The Adapter can be configured by a event entity.
 * <p>
 * Example:
 * 
 * <pre>
 * {@code
        <llm-config>
            <endpoint>https://localhost:8111/</endpoint>
        
        </llm-config>
 * }
 * </pre>
 * 
 * 
 * @author Ralph Soika
 * @version 1.0
 *
 */

public class LLMAdapter implements SignalAdapter {

    public static final String ML_ENTITY = "entity";
    public static final String API_ERROR = "API_ERROR";

    public static final int API_EVENT_SUCCESS = 110;
    public static final int API_EVENT_FAILURE = 90;

    private static Logger logger = Logger.getLogger(LLMAdapter.class.getName());

    @Inject
    @ConfigProperty(name = LLMConfig.LLM_SERVICE_ENDPOINT)
    Optional<String> mlDefaultAPIEndpoint;

    @Inject
    @ConfigProperty(name = LLMConfig.LLM_MODEL, defaultValue = "imixs-model")
    String mlDefaultModel;

    @Inject
    private WorkflowService workflowService;

    @Inject
    private LLMService llmService;

    /**
     * This method posts a text from an attachment to the Imixs-AI Analyse service
     * endpoint
     */
    public ItemCollection execute(ItemCollection workitem, ItemCollection event) throws AdapterException {
        String llmAPIEndpoint = null;

        // String mlQuality = null;
        Pattern llmFilenamePattern = null;
        boolean debug = logger.isLoggable(Level.FINE);
        debug = true;

        logger.finest("...running api adapter...");
        ItemCollection llmConfig = null;
        // read optional configuration form the model or imixs.properties....
        try {
            llmConfig = workflowService.evalWorkflowResult(event, "llm-config", workitem, false);

            llmAPIEndpoint = parseLLMEndpointByBPMN(llmConfig);

            // parse optional filename regex pattern...
            String _FilenamePattern = parseLLMFilePatternByBPMN(llmConfig);
            if (_FilenamePattern != null && !_FilenamePattern.isEmpty()) {
                llmFilenamePattern = Pattern.compile(_FilenamePattern);
            }

        } catch (PluginException e) {
            logger.warning("Unable to parse item definitions for 'llm-config', verify model - " + e.getMessage());
        }

        // do we have a valid endpoint?
        if (llmAPIEndpoint == null || llmAPIEndpoint.isEmpty()) {
            throw new ProcessingErrorException(LLMAdapter.class.getSimpleName(), API_ERROR,
                    "imixs-ai llm service endpoint is empty!");
        }

        String promptTemplate = readPromptTemplate(event);
        // build the llm context from the current workitem to be used in the prompt....
        String llmPrompt = new LLMPromptBuilder(promptTemplate, workitem, null, false, llmFilenamePattern).build();

        // if we have a prompt we call the llm api endpoint
        if (!llmPrompt.isEmpty()) {
            String result = llmService.postPrompt(llmAPIEndpoint, llmPrompt);
            workitem.setItemValue("ai.result", result);

        } else {
            logger.finest("......no ml content found to be analysed for " + workitem.getUniqueID());
        }

        return workitem;
    }

    /**
     * Helper method that reads the prompt template form a BPMN DataObject
     * associated with the current Event object.
     * 
     * @param event
     * @return
     */
    private String readPromptTemplate(ItemCollection event) {
        List<?> dataObjects = event.getItemValue("dataObjects");

        if (dataObjects == null || dataObjects.size() == 0) {
            logger.warning("No data object for prompt template found");
        }

        // tage teh first data object....
        List<String> data = (List<String>) dataObjects.get(0);
        String name = "" + data.get(0);
        String prompt = "" + data.get(1);
        return prompt;

    }

    /**
     * This helper method parses the ml api endpoint either provided by a model
     * definition or a imixs.property or an environment variable
     * 
     * @param mlConfig
     * @return
     */
    private String parseLLMEndpointByBPMN(ItemCollection mlConfig) {
        boolean debug = logger.isLoggable(Level.FINE);
        debug = true;
        String mlAPIEndpoint = null;

        // test if the model provides a MLEndpoint. If not, the adapter uses the
        // mlDefaultAPIEndpoint
        mlAPIEndpoint = null;
        if (mlConfig != null) {
            mlAPIEndpoint = mlConfig.getItemValueString("endpoint");
        }

        // switch to default api endpoint?
        if (mlAPIEndpoint == null || mlAPIEndpoint.isEmpty()) {
            // set defautl api endpoint if defined
            if (mlDefaultAPIEndpoint.isPresent() && !mlDefaultAPIEndpoint.get().isEmpty()) {
                mlAPIEndpoint = mlDefaultAPIEndpoint.get();
            }
        }
        if (debug) {
            logger.info("......ml api endpoint " + mlAPIEndpoint);
        }

        if (!mlAPIEndpoint.endsWith("/")) {
            mlAPIEndpoint = mlAPIEndpoint + "/";
        }

        return mlAPIEndpoint;

    }

    /**
     * This helper method parses the ml model name either provided by a model
     * definition or a imixs.property or an environment variable
     *
     * @param mlConfig
     * @return
     */
    private String parseLLMFilePatternByBPMN(ItemCollection mlConfig) {
        boolean debug = logger.isLoggable(Level.FINE);
        debug = true;
        String filePattern = null;

        // test if the model provides a MLModel name. If not, the adapter uses the
        // mlDefaultAPIEndpoint
        if (mlConfig != null) {
            filePattern = mlConfig.getItemValueString("filename.pattern");
        }

        if (debug) {
            logger.info("......llm file.pattern = " + filePattern);
        }

        return filePattern;

    }

}
