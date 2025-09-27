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

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.ModelManager;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
import org.openbpmn.bpmn.BPMNModel;

import jakarta.json.JsonObject;

/**
 * The ProcessAssistantAdapter is used for complex AI tasks within BPMN
 * workflows.
 * <p>
 * The adapter implements a hierarchical prompt template system that builds
 * continuous prompts by combining multiple template layers:
 * <ul>
 * <li><strong>Initial Template:</strong> Defines the AI assistant's role, basic
 * approach, process goals, and product knowledge (WHO am I, WHAT do I do, HOW
 * do I work)</li>
 * <li><strong>Task Template:</strong> Describes the current process context and
 * available next steps within the sales process</li>
 * <li><strong>Event Template:</strong> Contains specific instructions for the
 * current action (WHAT should I do NOW)</li>
 * <li><strong>Workflow Data:</strong> Customer data and process variables from
 * workflow fields</li>
 * <li><strong>User Input:</strong> Additional context or instructions from the
 * user</li>
 * </ul>
 * <p>
 * This modular approach ensures clean separation of concerns:
 * - Role definition happens only once in the initial template
 * - Task templates provide process context without redundancy
 * - Event templates focus purely on specific actions
 * - All templates can be maintained independently
 * <p>
 * Template Association in BPMN:
 * - Tasks are connected to DataObjects containing task-specific context
 * templates
 * - Events are connected to DataObjects containing action-specific instruction
 * templates
 * - The adapter automatically loads and combines all relevant templates
 * <p>
 * The final prompt structure follows this pattern:
 * 
 * <pre>
 * Final LLM Prompt = Initial Template + Task Template + Event Template + Workflow Data + User Input
 * </pre>
 * <p>
 * The Adapter can be configured by an event entity.
 * <p>
 * Example imixs-ai Prompt:
 *
 * <pre>
 * {@code
 <imixs-ai name="ASSISTANT">
 <endpoint>https://localhost:8111/</endpoint>
 <result-item>invoice.summary</result-item>
 <result-event>JSON</result-event>
 </imixs-ai>
 * }
 * </pre>
 *
 * @author Ralph Soika
 * @version 1.0
 *
 */
public class ProcessAssistantAdapter extends OpenAIAPIAdapter {

    public static final String API_ERROR = "API_ERROR";

    public static final int API_EVENT_SUCCESS = 110;
    public static final int API_EVENT_FAILURE = 90;

    public static final String LLM_ASSIST = "ASSIST";

    private static Logger logger = Logger.getLogger(ProcessAssistantAdapter.class.getName());

    // @Inject
    // @ConfigProperty(name = OpenAIAPIConnector.ENV_LLM_SERVICE_ENDPOINT)
    // Optional<String> serviceEndpoint;

    // @Inject
    // private WorkflowService workflowService;

    // @Inject
    // private OpenAIAPIService llmService;

    /**
     * Default Constructor
     */
    public ProcessAssistantAdapter() {
        super();
    }

    // /**
    // * CDI Constructor to inject WorkflowService
    // *
    // * @param workflowService
    // */
    // @Inject
    // public ProcessAssistantAdapter(WorkflowService workflowService) {
    // super();
    // this.workflowService = workflowService;
    // }

    // public void setWorkflowService(WorkflowService workflowService) {
    // this.workflowService = workflowService;
    // }

    /**
     * This method generate the prompt based on the templates.
     * 
     * @throws PluginException
     */
    public ItemCollection execute(ItemCollection workitem, ItemCollection event)
            throws AdapterException, PluginException {
        String llmAPIEndpoint = null;
        String llmAPIResultEvent = null;
        String llmAPIResultItem = null;
        boolean llmAPIDebug = false;
        List<ItemCollection> llmPromptDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-ai",
                LLM_ASSIST, workitem, false);

        if (llmPromptDefinitions != null) {
            for (ItemCollection promptDefinition : llmPromptDefinitions) {
                llmAPIEndpoint = parseLLMEndpointByBPMN(promptDefinition);
                llmAPIResultEvent = promptDefinition.getItemValueString("result-event");
                llmAPIResultItem = promptDefinition.getItemValueString("result-item");
                if ("true".equalsIgnoreCase(promptDefinition.getItemValueString("debug"))) {
                    llmAPIDebug = true;
                }

                // Build template
                try {

                    ModelManager modelManager = new ModelManager(workflowService);
                    BPMNModel model = modelManager.getModelByWorkitem(workitem);

                    String taskPromptTemplate = workitem.getItemValueString("ai.prompt.last");
                    if (taskPromptTemplate.isEmpty()) {
                        // Build 1st Task Template
                        ItemCollection task = modelManager.loadTask(workitem, model);
                        taskPromptTemplate = loadPromptTemplateByModelElement(task);
                        taskPromptTemplate = llmService.buildPrompt(taskPromptTemplate, workitem);

                    }

                    // Build Event Template
                    workitem.replaceItemValue("$event.name", event.getItemValueString("name"));
                    String eventPromptTemplate = loadPromptTemplateByModelElement(event);
                    eventPromptTemplate = llmService.buildPrompt(eventPromptTemplate, workitem);

                    logger.fine("Task Template: " + taskPromptTemplate);
                    logger.fine("Event Template: " + eventPromptTemplate);

                    String llmPrompt = taskPromptTemplate + eventPromptTemplate;
                    // save last prompt
                    workitem.setItemValue("ai.prompt.last", llmPrompt);

                    // if we have a prompt we call the llm api endpoint
                    if (llmPrompt != null && !llmPrompt.isBlank()) {
                        if (llmAPIDebug) {
                            logger.info("===> Total Prompt Length = " + llmPrompt.length());
                            logger.info("===> Prompt: ");
                            logger.info(llmPrompt);
                        }
                        // postPromptCompletion
                        JsonObject jsonPrompt = llmService.buildJsonPromptObjectV1(llmPrompt, false,
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

                } catch (ModelException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
        return workitem;
    }

    /**
     * Loads a prompt template associated with a event or task
     * 
     * @param element
     * @return
     */
    @SuppressWarnings("unchecked")
    private String loadPromptTemplateByModelElement(ItemCollection element) {
        List<List<String>> dataObjects = element.getItemValue("dataObjects");

        if (dataObjects == null || dataObjects.size() == 0) {
            logger.warning("No data object for prompt template found");
        }

        // take the first data object with a prompt definition....
        for (List<String> dataObject : dataObjects) {
            String name = "" + dataObject.get(0);
            String _prompt = "" + dataObject.get(1);
            if (_prompt.contains("<PromptDefinition>")) {
                return _prompt;

            }
        }

        return null;
    }
}
