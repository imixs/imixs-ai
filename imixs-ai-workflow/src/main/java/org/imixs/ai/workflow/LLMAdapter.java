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
import org.imixs.ai.json.LLMJSONParser;
import org.imixs.ai.xml.LLMXMLParser;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.SignalAdapter;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.exceptions.ProcessingErrorException;
import org.imixs.workflow.util.XMLParser;

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
        <llm-config name="PROMPT">
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
    public static final String LLM_PROMPT = "PROMPT";

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
     * Default Constructor
     */
    public LLMAdapter() {
        super();
    }

    /**
     * CDI Constructor to inject WorkflowService
     * 
     * @param workflowService
     */
    @Inject
    public LLMAdapter(WorkflowService workflowService) {
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
            if (llmConfig == null) {
                // no configuration found!
                throw new AdapterException(LLMAdapter.class.getSimpleName(), API_ERROR,
                        "Missing llm-config definition in Event!");
            }

            // extract the create subprocess definitions...
            List<String> llmPromptDefinitions = llmConfig.getItemValue(LLM_PROMPT);
            if (llmPromptDefinitions == null || llmPromptDefinitions.size() == 0) {
                // no PROMPT definition found
                throw new AdapterException(LLMAdapter.class.getSimpleName(), API_ERROR,
                        "Missing llm-config PROMPT definition in Event!");
            }

            /**
             * Iterate over each PROMPT definition and process the prompt
             */
            for (String promptDefinitionXML : llmPromptDefinitions) {

                if (promptDefinitionXML.trim().isEmpty()) {
                    // no definition
                    continue;
                }
                // evaluate the prompt definition (XML format expected here!)
                ItemCollection promptDefinition = XMLParser.parseItemStructure(promptDefinitionXML);
                if (promptDefinition != null) {
                    llmAPIEndpoint = parseLLMEndpointByBPMN(promptDefinition);

                    // parse optional filename regex pattern...
                    String _FilenamePattern = parseLLMFilePatternByBPMN(promptDefinition);
                    if (_FilenamePattern != null && !_FilenamePattern.isEmpty()) {
                        llmFilenamePattern = Pattern.compile(_FilenamePattern);
                    }

                    // do we have a valid endpoint?
                    if (llmAPIEndpoint == null || llmAPIEndpoint.isEmpty()) {
                        throw new ProcessingErrorException(LLMAdapter.class.getSimpleName(), API_ERROR,
                                "imixs-ai llm service endpoint is empty!");
                    }

                    String promptTemplate = readPromptTemplate(event);
                    // build the llm context from the current workitem to be used in the prompt....
                    String llmPrompt = new LLMPromptBuilder(promptTemplate, workitem, null, false, llmFilenamePattern)
                            .build();

                    // if we have a prompt we call the llm api endpoint
                    if (!llmPrompt.isEmpty()) {
                        String xmlResult = llmService.postPrompt(llmAPIEndpoint, llmPrompt);
                        workitem.appendItemValue("ai.result", xmlResult);

                        // resolve ai.result....
                        resolveAIResult(workitem, xmlResult);

                    } else {
                        logger.finest("......no ai content found to be analyzed for " + workitem.getUniqueID());
                    }
                }
            }
        } catch (PluginException e) {
            logger.warning("Unable to parse item definitions for 'llm-config', verify model - " + e.getMessage());
        }

        return workitem;
    }

    /**
     * This method resolves the item values in the last ai.result
     * 
     * @param workitem
     */
    private void resolveAIResult(ItemCollection workitem, String xmlResultString) {
        // xml resolve
        String resultString = LLMXMLParser.parseResultTag(xmlResultString);
        // apply the json structure to the worktiem
        LLMJSONParser.applyJSONObject(resultString, workitem);
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
     * @param llmPrompt
     * @return
     */
    private String parseLLMEndpointByBPMN(ItemCollection llmPrompt) {
        boolean debug = logger.isLoggable(Level.FINE);
        debug = true;
        String llmAPIEndpoint = null;

        // test if the model provides a MLEndpoint. If not, the adapter uses the
        // mlDefaultAPIEndpoint
        llmAPIEndpoint = null;
        if (llmPrompt != null) {
            llmAPIEndpoint = llmPrompt.getItemValueString("endpoint");
        }

        // switch to default api endpoint?
        if (llmAPIEndpoint == null || llmAPIEndpoint.isEmpty()) {
            // set defautl api endpoint if defined
            if (mlDefaultAPIEndpoint.isPresent() && !mlDefaultAPIEndpoint.get().isEmpty()) {
                llmAPIEndpoint = mlDefaultAPIEndpoint.get();
            }
        }
        if (debug) {
            logger.info("......llm api endpoint " + llmAPIEndpoint);
        }

        if (!llmAPIEndpoint.endsWith("/")) {
            llmAPIEndpoint = llmAPIEndpoint + "/";
        }

        return llmAPIEndpoint;

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
