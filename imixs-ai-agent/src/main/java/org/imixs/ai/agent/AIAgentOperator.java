/****************************************************************************
 * Copyright (c) 2025 Dynamixs Software Solutions GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 ****************************************************************************/
package org.imixs.ai.agent;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.imixs.ai.ImixsAIContextHandler;
import org.imixs.ai.api.OpenAIAPIService;
import org.imixs.ai.api.ToolCallResult;
import org.imixs.ai.tools.ImixsAIToolRegistrationEvent;
import org.imixs.ai.workflow.ImixsAIPromptService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.ModelManager;
import org.imixs.workflow.engine.EventLogService;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.engine.jpa.EventLog;
import org.imixs.workflow.exceptions.AccessDeniedException;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.exceptions.ProcessingErrorException;
import org.openbpmn.bpmn.BPMNModel;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RunAs;
import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;

/**
 * The AIAgentOperator processes EventLog entries with the topic
 * "ai.agent.process" and "ai.agent.start" in an asynchronous transactional
 * batch process.
 * <p>
 * For each EventLog entry the operator:
 * <ol>
 * <li>Locks the entry to prevent concurrent processing</li>
 * <li>Creates or loads the corresponding workitem</li>
 * <li>Reads the agent configuration from the EventLog data field</li>
 * <li>Restores the conversation context from the workitem</li>
 * <li>Runs the agent loop against the configured LLM endpoint</li>
 * <li>Writes progress updates into the workitem during processing</li>
 * <li>Persists the full conversation context back into the workitem</li>
 * <li>Triggers the configured success or error event on the workitem</li>
 * <li>Removes the EventLog entry</li>
 * </ol>
 * <p>
 * The AIAgentOperator distinguish two event topics.
 * <ul>
 * <li>ai.agent.process</li>
 * <li>ai.agent.start</li>
 * </ul>
 * For the topic "ai.agent.process" the operator starts the AgentLoop for an
 * existing Agent Workitem. For the topic "ai.agent.start" the operator creates
 * a new agent workitem. The 'start' mode is typically used to start a new agent
 * task from a compliance workflow. The 'process' mode is used within an agent
 * ai workflow.
 * <p>
 * The operator runs with MANAGER access since no user session is available in a
 * scheduled context.
 *
 * @author rsoika
 */
@DeclareRoles({ "org.imixs.ACCESSLEVEL.MANAGERACCESS" })
@RunAs("org.imixs.ACCESSLEVEL.MANAGERACCESS")
@Stateless
@LocalBean
public class AIAgentOperator {

    public static final String AGENT_TOPIC_PROCESS = "ai.agent.process";

    public static final String ITEM_AGENT_STATUS = "agent.status";
    // public static final String ITEM_AGENT_SKILLS = "agent.skills";
    public static final String ITEM_AGENT_WORKITEM_REF = "agent.workitem.ref";
    // public static final String ITEM_AGENT_TASK_COMPLETE = "agent.task.complete";
    // public static final String ITEM_AGENT_TASK_RESULT = "agent.task.result";

    public static final String AGENT_STATUS_WAITING = "WAITING";
    private static final String AGENT_STATUS_RUNNING = "RUNNING";
    public static final String AGENT_STATUS_PENDING = "PENDING";
    public static final String AGENT_STATUS_DONE = "DONE";
    public static final String AGENT_STATUS_ERROR = "ERROR";
    public static final String AGENT_STATUS_UNDEFINED = "UNDEFINED";

    // Agent configuration keys read from the EventLog data field
    public static final String AGENT_CONFIG_CONTEXT_ITEM = "agent.context.item";
    public static final String AGENT_CONFIG_MODEL = "agent.model";
    public static final String AGENT_CONFIG_INIT_TASK = "agent.init.task";
    public static final String AGENT_CONFIG_INIT_EVENT = "agent.init.event";
    public static final String AGENT_CONFIG_USER_ITEM = "agent.user.item";
    public static final String AGENT_CONFIG_RESULT_TYPE = "agent.result.type";
    public static final String AGENT_CONFIG_ENDPOINT = "agent.endpoint";
    public static final String AGENT_CONFIG_TIMEOUT = "agent.timeout";
    public static final String AGENT_CONFIG_MAX_ITERATIONS = "agent.max-iterations";
    public static final String AGENT_CONFIG_SUCCESS_EVENT = "agent.event.success";
    public static final String AGENT_CONFIG_ERROR_EVENT = "agent.event.error";
    public static final String AGENT_CONFIG_NEXT_EVENT = "agent.event.next";
    public static final String AGENT_CONFIG_DEBUG = "agent.debug";

    public static final String PROMPT_FILECONTEXT = "\n\n<FILECONTEXT>^.+\\.(pdf|PDF|eml|msg)$</FILECONTEXT>";

    public static final int DEFAULT_MAX_ITERATIONS = 10;
    public static final long DEFAULT_TIMEOUT_MS = 120_000L;

    private static final Logger logger = Logger.getLogger(AIAgentOperator.class.getName());

    @Inject
    EventLogService eventLogService;

    @Inject
    WorkflowService workflowService;

    @Inject
    OpenAIAPIService openAIAPIService;

    @Inject
    ImixsAIPromptService imixsAIPromptService;

    @Inject
    ImixsAIContextHandler contextHandler;

    // Function Events
    @Inject
    private Event<ImixsAIToolRegistrationEvent> toolRegistrationEvent;

    /**
     * Looks up EventLog entries for the topic "ai.agent.process" and processes each
     * one in a new transaction. Uses optimistic locking to prevent concurrent
     * handling of the same entry across cluster nodes.
     */
    @TransactionAttribute(value = TransactionAttributeType.REQUIRES_NEW)
    public void processEventLog() {
        long l = System.currentTimeMillis();

        List<EventLog> events = eventLogService.findEventsByTimeout(10, AGENT_TOPIC_PROCESS);
        if (events.isEmpty()) {
            return;
        }

        logger.log(Level.INFO, "├── 🔃 processing {0} AI agent event(s)....", events.size());

        for (EventLog eventLogEntry : events) {
            try {
                logger.log(Level.INFO, "│   ├── Agent event: '" + eventLogEntry.getTopic() + "' ⇢ {0}",
                        eventLogEntry.getRef());

                if (!eventLogService.lock(eventLogEntry)) {
                    continue;
                }
                // Read agent configuration from the EventLog data field.
                // These values were written by the EventLogPlugin from the BPMN model.
                ItemCollection agentConfig = new ItemCollection(eventLogEntry.getData());

                // get Agent Workitem...
                ItemCollection agentWorkitem = null;

                // load agent workitem
                agentWorkitem = workflowService.getWorkItem(eventLogEntry.getRef());
                logger.log(Level.INFO, "│   ├── ☑️ agent workitem loaded: {0}", eventLogEntry.getRef());

                if (agentWorkitem == null) {
                    throw new PluginException(AIAgentOperator.class.getSimpleName(),
                            "AGENT_CONFIG_ERROR",
                            "⚠️ agent workitem not found - eventLog ref=" + eventLogEntry.getRef());
                }

                String endpoint = agentConfig.getItemValueString(AGENT_CONFIG_ENDPOINT);
                int successEvent = agentConfig.getItemValueInteger(AGENT_CONFIG_SUCCESS_EVENT);
                int errorEvent = agentConfig.getItemValueInteger(AGENT_CONFIG_ERROR_EVENT);
                int nextEvent = agentConfig.getItemValueInteger(AGENT_CONFIG_NEXT_EVENT);
                String contextItem = agentConfig.getItemValueString(AGENT_CONFIG_CONTEXT_ITEM);

                if (endpoint.isBlank()) {
                    throw new PluginException(AIAgentOperator.class.getSimpleName(),
                            "AGENT_CONFIG_ERROR",
                            AGENT_CONFIG_ENDPOINT + " must not be empty! Verify BPMN event configuration.");
                }
                if (contextItem.isBlank()) {
                    throw new PluginException(AIAgentOperator.class.getSimpleName(),
                            "AGENT_CONFIG_ERROR",
                            AGENT_CONFIG_CONTEXT_ITEM + " must not be empty! Verify BPMN event configuration.");
                }
                if (successEvent == 0) {
                    throw new PluginException(AIAgentOperator.class.getSimpleName(),
                            "AGENT_CONFIG_ERROR",
                            AGENT_CONFIG_SUCCESS_EVENT + " must not be 0! Verify BPMN event configuration.");
                }
                if (errorEvent == 0) {
                    throw new PluginException(AIAgentOperator.class.getSimpleName(),
                            "AGENT_CONFIG_ERROR",
                            AGENT_CONFIG_ERROR_EVENT + " must not be 0! Verify BPMN event configuration.");
                }
                if (nextEvent == 0) {
                    throw new PluginException(AIAgentOperator.class.getSimpleName(),
                            "AGENT_CONFIG_ERROR",
                            AGENT_CONFIG_NEXT_EVENT + " must not be 0! Verify BPMN event configuration.");
                }

                // copy agent configuration
                agentWorkitem.copy(agentConfig);

                // Run the agent loop
                runAgentLoop(agentWorkitem);

                // finally remove event....
                eventLogService.removeEvent(eventLogEntry.getId());

            } catch (OptimisticLockException e) {
                logger.log(Level.INFO, "│   ├── ⚠️ unable to lock agent event: {0}", e.getMessage());
            } catch (Exception e) {
                logger.log(Level.WARNING, "│   ├── ⚠️ agent processing failed: {0}", e.getMessage());
                eventLogService.removeEvent(eventLogEntry.getId());
            }
        }

        logger.log(Level.INFO, "├── ✅ {0} agent event(s) processed in {1}ms",
                new Object[] { events.size(), System.currentTimeMillis() - l });
    }

    /**
     * Runs the agent loop for a single workitem.
     * <p>
     * Restores the conversation context from the workitem. If no system message
     * exists yet, the prompt definition from the BPMN task is added as the system
     * message. The user question is then appended and the loop iterates until the
     * LLM produces a plain-text response or the timeout/iteration limit is reached.
     * The full conversation context is persisted back into the workitem on success.
     *
     * @param workitem      the AI-Task workitem
     * @param userInput     item name that holds the user's input text
     * @param endpoint      logical LLM endpoint id from imixs-llm.xml
     * @param timeout       maximum wall-clock time in milliseconds
     * @param maxIterations maximum number of LLM calls
     * @param successEvent  BPMN event ID to trigger on successful completion
     * @param errorEvent    BPMN event ID to trigger on failure or timeout
     */
    private void runAgentLoop(ItemCollection workitem) {

        String workitemId = workitem.getUniqueID();
        logger.info("├── Starting agent loop for workitem " + workitemId);

        String contextItemName = workitem.getItemValueString(AGENT_CONFIG_CONTEXT_ITEM);
        String userInputInput = workitem.getItemValueString(AGENT_CONFIG_USER_ITEM);
        if (userInputInput.isBlank()) {
            userInputInput = "agent.user.input";
        }

        String endpoint = workitem.getItemValueString(AGENT_CONFIG_ENDPOINT);
        long timeout = workitem.getItemValueLong(AGENT_CONFIG_TIMEOUT);
        int maxIterations = workitem.getItemValueInteger(AGENT_CONFIG_MAX_ITERATIONS);
        int successEvent = workitem.getItemValueInteger(AGENT_CONFIG_SUCCESS_EVENT);
        int errorEvent = workitem.getItemValueInteger(AGENT_CONFIG_ERROR_EVENT);
        int nextEvent = workitem.getItemValueInteger(AGENT_CONFIG_NEXT_EVENT);
        String resultType = workitem.getItemValueString(AGENT_CONFIG_RESULT_TYPE);
        boolean debug = workitem.getItemValueBoolean(AGENT_CONFIG_DEBUG);

        logger.log(Level.INFO, "│   ├── " + AGENT_CONFIG_ENDPOINT + "={0}", endpoint);
        logger.log(Level.INFO, "│   ├── " + AGENT_CONFIG_CONTEXT_ITEM + "={0}", contextItemName);
        logger.log(Level.INFO, "│   ├── " + AGENT_CONFIG_USER_ITEM + "={0}", userInputInput);
        logger.log(Level.INFO, "│   ├── " + AGENT_CONFIG_TIMEOUT + "={0}", timeout);
        logger.log(Level.INFO, "│   ├── " + AGENT_CONFIG_MAX_ITERATIONS + "={0}", maxIterations);
        logger.log(Level.INFO, "│   ├── " + AGENT_CONFIG_NEXT_EVENT + "={0}", nextEvent);
        logger.log(Level.INFO, "│   ├── " + AGENT_CONFIG_SUCCESS_EVENT + "={0}", successEvent);
        logger.log(Level.INFO, "│   ├── " + AGENT_CONFIG_ERROR_EVENT + "={0}", errorEvent);
        logger.log(Level.INFO, "│   ├── " + AGENT_CONFIG_RESULT_TYPE + "={0}", resultType);

        if (timeout <= 0) {
            timeout = DEFAULT_TIMEOUT_MS;
        }
        if (maxIterations <= 0) {
            maxIterations = DEFAULT_MAX_ITERATIONS;
        }

        // Mark the workitem as running
        workitem.setItemValue(ITEM_AGENT_STATUS, AGENT_STATUS_RUNNING);
        long deadline = System.currentTimeMillis() + timeout;

        try {
            String userMessage = workitem.getItemValueString(userInputInput);
            // Restore conversation context from workitem.
            // importContext calls init() internally, so functions must be added afterwards.
            contextHandler.importContext(workitem, contextItemName);

            // Only initialize the system prompt on the very first call.
            // On subsequent calls the context already contains the system message
            // and we must not reset it.
            if (!contextHandler.hasSystemMessage()) {
                ModelManager modelManager = new ModelManager(workflowService);
                BPMNModel model = modelManager.getModelByWorkitem(workitem);
                ItemCollection task = modelManager.loadTask(workitem, model);
                if (task == null) {
                    throw new PluginException(AIAgentOperator.class.getSimpleName(),
                            "AGENT_ERROR", "BPMN Task " + workitem.getTaskID() + " not found in model!");
                }
                String taskPromptTemplate = imixsAIPromptService.loadPromptTemplateByModelElement(task);
                if (taskPromptTemplate == null || taskPromptTemplate.isBlank()) {
                    throw new PluginException(AIAgentOperator.class.getSimpleName(),
                            "AGENT_ERROR", "BPMN Task " + workitem.getTaskID() + " does not have a prompt definition!");
                }
                contextHandler.addPromptDefinition(taskPromptTemplate);
            }

            // Re-add function definitions — these are not persisted in the context
            // and must be provided with every request.
            // Fire registration event — all handlers add their function definitions
            ImixsAIToolRegistrationEvent registrationEvent = new ImixsAIToolRegistrationEvent(contextHandler);
            toolRegistrationEvent.fire(registrationEvent);

            // Register all collected functions with the context
            for (ImixsAIToolRegistrationEvent.FunctionDefinition fn : registrationEvent.getFunctions()) {
                contextHandler.addFunction(fn.getName(), fn.getDescription(), fn.getSchema());
            }

            // Append the new user question to the conversation if userMessage is defined in
            // the current loop. Otherwise we assume that the user prompt is part of the
            // prompt definition template
            if (!userMessage.isBlank()) {
                logger.log(Level.INFO, "│   ├── 💥 User question provided - adding user user message...");
                String userPrompt = userMessage;
                // append also an optional file context
                if (workitem.getFileNames().size() > 0) {
                    userPrompt = userPrompt + PROMPT_FILECONTEXT;
                }
                contextHandler.addQuestion(userPrompt, workitem.getItemValueString("$creator"), null);
            }

            // do we have user message?
            ItemCollection lastMessage = contextHandler.getLastMessage();
            if (lastMessage == null || !lastMessage.getItemValueString(ImixsAIContextHandler.ITEM_ROLE)
                    .equals(ImixsAIContextHandler.ROLE_USER)) {
                logger.log(Level.WARNING,
                        "│   ├── ⚠️ Context does not finish with Question (chat.role==user) - verify bpmn agent-model !");
            }

            // Agent loop — iterate until the LLM returns a plain-text response,
            // the timeout is reached, or the maximum number of iterations is exceeded.
            // Reset task_complete flag from previous loop iteration
            // workitem.setItemValue(AIAgentOperator.ITEM_AGENT_TASK_COMPLETE, false);
            int iterations = 0;
            while (iterations < maxIterations) {
                iterations++;

                if (System.currentTimeMillis() > deadline) {
                    throw new PluginException(AIAgentOperator.class.getSimpleName(),
                            "AGENT_TIMEOUT", "Agent timeout after " + timeout + "ms");
                }

                // call LLM
                String response = openAIAPIService.postPromptCompletion(contextHandler, endpoint, debug);

                // evaluate Tool Calls
                ToolCallResult toolCallResult = openAIAPIService.processToolCallResult(response, contextHandler,
                        resultType);

                if (toolCallResult == null || !toolCallResult.wasToolCall()) {
                    // Plain-text response — the agent is done.
                    // Add the answer to the context and persist the full conversation.
                    String agentResponse = openAIAPIService.processPromptResult(response, resultType, workitem);

                    contextHandler.addAnswer(agentResponse);
                    contextHandler.storeContext();
                    // reset user message - save input history
                    workitem.insertItemValue(userInputInput, "");
                    // add comment
                    workitem.setItemValue("comment.user", agentResponse);
                    logger.log(Level.INFO,
                            "│   ├── ⏳ awaiting user input — triggering next event {0}", nextEvent);

                    workitem.setItemValue(ITEM_AGENT_STATUS, AGENT_STATUS_WAITING);
                    triggerWorkflowEvent(workitem, nextEvent);
                    return;
                } else {
                    logger.log(Level.INFO,
                            "│   ├── 🔁 processing tool calls successful");

                    // Update context
                    contextHandler.storeContext();
                    // Check if task_complete was called in this tool call iteration
                    if (toolCallResult.isTaskComplete()) {
                        logger.log(Level.INFO,
                                "│   ├── ✅ task complete via tool call — triggering success event {0}", successEvent);
                        workitem.setItemValue(ITEM_AGENT_STATUS, AGENT_STATUS_DONE);
                        triggerWorkflowEvent(workitem, successEvent);

                        return;

                    }
                }
            }

            throw new PluginException(AIAgentOperator.class.getSimpleName(),
                    "AGENT_LOOP_ERROR", "Max iterations reached without result");

        } catch (AdapterException | PluginException | ModelException e) {
            logger.log(Level.WARNING, "│   ├── ⚠️ Agent loop failed: {0}", e.getMessage());
            workitem.setItemValue(ITEM_AGENT_STATUS, AGENT_STATUS_ERROR);
            try {
                triggerWorkflowEvent(workitem, errorEvent);
            } catch (Exception ex) {
                logger.log(Level.SEVERE, "│   └── ❌ Failed to trigger error event: {0}", ex.getMessage());
            }
        }
    }

    /**
     * Triggers a BPMN workflow event on the workitem by setting the event ID and
     * calling the WorkflowService. This advances the workitem to its next state
     * according to the model — either the success task or the error task.
     *
     * @param workitem the AI-Task workitem
     * @param eventId  BPMN event ID (success-event or error-event from agent
     *                 config)
     */
    private void triggerWorkflowEvent(ItemCollection workitem, int eventId)
            throws PluginException, ModelException {
        workitem.event(eventId);

        logger.info(" trigger event " + workitem.getModelVersion() + "  " + workitem.getTaskID() + "."
                + workitem.getEventID());

        workflowService.processWorkItem(workitem);
        logger.info("│   └── ✅ Workflow event " + eventId + " triggered for " + workitem.getUniqueID());
        return;
    }

    /**
     * This method creates a new Agent Workitem based on a given Agent configuration
     *
     * The method expects an instance of the parent process
     * 
     * <pre>
      {@code
       <imixs-ai name="AGENT">
          <debug>false</debug>  
          <agent.model>ai-agent-calculator-de-1.0</agent.model>
          <agent.init.task>100</agent.init.task>
          <agent.init.event>100</agent.init.event>
       </imixs-ai>
        }
     * </pre>
     * 
     * @param agentConfig
     * @return
     * @throws ModelException
     * @throws PluginException
     * @throws ProcessingErrorException
     * @throws AccessDeniedException
     */
    public ItemCollection createAgent(ItemCollection agentConfig, ItemCollection workitem)
            throws AccessDeniedException, ProcessingErrorException, PluginException, ModelException {

        logger.log(Level.INFO, "├── 🔃 creating new AIAgent....");
        String model = agentConfig.getItemValueString(AIAgentOperator.AGENT_CONFIG_MODEL);
        int initTask = agentConfig.getItemValueInteger(AIAgentOperator.AGENT_CONFIG_INIT_TASK);
        int initEvent = agentConfig.getItemValueInteger(AIAgentOperator.AGENT_CONFIG_INIT_EVENT);
        ItemCollection agentWorkitem = new ItemCollection().model(model).task(initTask).event(initEvent);

        logger.log(Level.INFO, "│   ├── ModelVersion: " + model);
        logger.log(Level.INFO, "│   ├── InitTask: " + initTask);
        logger.log(Level.INFO, "│   ├── InitEvent: " + initEvent);
        logger.log(Level.INFO, "│   ├── Workitem Ref: " + workitem.getUniqueID());

        // set agent.ref
        agentWorkitem.appendItemValueUnique(AIAgentOperator.ITEM_AGENT_WORKITEM_REF, workitem.getUniqueID());
        // set workitem ref
        agentWorkitem.appendItemValueUnique("$workitemref", workitem.getUniqueID());

        // process workitem
        agentWorkitem = workflowService.processWorkItem(agentWorkitem);
        logger.log(Level.INFO, "├── ✅ AI Agent created: {0}", agentWorkitem.getUniqueID());
        return agentWorkitem;
    }
}