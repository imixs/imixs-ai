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
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.ModelManager;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
import org.openbpmn.bpmn.BPMNModel;

import jakarta.inject.Inject;

/**
 * The ImixsAIAssistantAdapter is used to assist a business process with LLMs
 * <p>
 * The adapter implements a continuous consistent prompt template based on the
 * OpenAI API by by combining multiple template layers:
 * <ul>
 * <li><strong>Task Template:</strong> Defines the initial AI 'system' role. it
 * describes the process goals, the process context and available next steps
 * within the process. (WHO am I, WHAT do I do, HOW do I work)</li>
 * 
 * <li><strong>Event Template:</strong> Contains specific instructions for the
 * current action as also context business data (WHAT should I do NOW)</li>
 * <li><strong>Business Data:</strong> Provides process variables from workflow
 * fields and additional context or instructions from the user</li>
 * </ul>
 * <p>
 * This modular approach ensures clean separation of concerns: - Role definition
 * happens only once in the initial task template - Event templates focus purely
 * on specific actions - All templates can be maintained independently
 * <p>
 * Template Association in BPMN: - Tasks are connected to DataObjects containing
 * task-specific context templates - Events are connected to DataObjects
 * containing action-specific instruction templates - The adapter automatically
 * loads and combines all relevant templates
 * <p>
 * The final prompt structure follows this OpenAI Message pattern:
 * 
 * <pre>
    "messages": [
        {
            "role": "system",
            "content": TASK TEMPLATE
        },
        {
            "role": "user",
            "content": EVENT TEMPLATE
        }
    ]
}
 * </pre>
 * <p>
 * The Adapter can be configured by an event entity.
 * <p>
 * Example imixs-ai Prompt:
 *
 * <pre>
 * {@code
 <imixs-ai name="ASSISTANT">
   <endpoint>https://localhost:8080/</endpoint>
   <result-item>request.response.text</result-item>
   <result-event>JSON</result-event>
 </imixs-ai>
 * }
 * </pre>
 * <p>
 * The {@code result-item} holds the message history.
 * <p>
 * The {@code result-event} is a optional custom event send to all CDI Event
 * observers for a LLMResultEvent
 * 
 * @author Ralph Soika
 * @version 1.0
 * @see ImixsAIContextHandler
 */
public class ImixsAIAssistantAdapter extends OpenAIAPIAdapter {

    public static final String LLM_ASSIST = "ASSIST";
    public static final String ITEM_AI_ASSIST_HISTORY = "ai.assist.history";

    private static Logger logger = Logger.getLogger(ImixsAIAssistantAdapter.class.getName());

    @Inject
    ImixsAIContextHandler imixsAIContextHandler;

    @Inject
    ImixsAIPromptService imixsAIPromptService;

    /**
     * Default Constructor
     */
    public ImixsAIAssistantAdapter() {
        super();
    }

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
        // boolean llmAPIDebug = false;
        List<ItemCollection> llmPromptDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-ai",
                LLM_ASSIST, workitem, false);

        if (llmPromptDefinitions != null) {
            for (ItemCollection promptDefinition : llmPromptDefinitions) {
                llmAPIEndpoint = imixsAIPromptService.parseLLMEndpointByBPMN(promptDefinition);
                llmAPIResultEvent = promptDefinition.getItemValueString("result-event");
                llmAPIResultItem = promptDefinition.getItemValueString("result-item");
                // if ("true".equalsIgnoreCase(promptDefinition.getItemValueString("debug"))) {
                // llmAPIDebug = true;
                // }

                // Build template
                try {
                    imixsAIContextHandler.importContext(workitem, ITEM_AI_ASSIST_HISTORY);

                    ModelManager modelManager = new ModelManager(workflowService);
                    BPMNModel model = modelManager.getModelByWorkitem(workitem);

                    // do we have a system template?

                    ItemCollection task = modelManager.loadTask(workitem, model);
                    String taskPromptTemplate = imixsAIPromptService.loadPromptTemplateByModelElement(task);
                    if (taskPromptTemplate != null && !taskPromptTemplate.isBlank()) {
                        imixsAIContextHandler.addPromptDefinition(taskPromptTemplate);
                    }

                    // Build Event Template
                    workitem.replaceItemValue("$event.name", event.getItemValueString("name"));
                    String eventPromptTemplate = imixsAIPromptService.loadPromptTemplateByModelElement(event);
                    imixsAIContextHandler.addPromptDefinition(eventPromptTemplate);

                    logger.setLevel(imixsAIContextHandler.getLogLevel());
                    logger.fine("Task Template: " + taskPromptTemplate);
                    logger.fine("Event Template: " + eventPromptTemplate);

                    // postPromptCompletion
                    // JsonObject jsonPrompt = imixsAIContextHandler.getOpenAIMessageObject();

                    String completionResult = llmService.postPromptCompletion(imixsAIContextHandler, llmAPIEndpoint);
                    // process the ai.result....
                    String resultMessage = llmService.processPromptResult(completionResult, llmAPIResultEvent,
                            workitem);
                    // store the result message
                    if (llmAPIResultItem != null && !llmAPIResultItem.isEmpty()) {
                        workitem.setItemValue(llmAPIResultItem, resultMessage);
                    }
                    // append answer to message history
                    imixsAIContextHandler.addAnswer(resultMessage);
                    imixsAIContextHandler.storeContext();

                } catch (ModelException e) {

                    logger.severe("Invalid Model - unable to parse prompt template - " + e.getMessage());
                    throw new PluginException(
                            OpenAIAPIAdapter.class.getSimpleName(), e.getErrorCode(),
                            "Invalid Model - unable to parse prompt template - verify model - " + e.getMessage(), e);
                }
            }
        }
        return workitem;
    }

}
