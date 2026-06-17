package org.imixs.ai.agent;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.imixs.ai.ImixsAIContextHandler;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.ModelManager;
import org.imixs.workflow.engine.DocumentService;
import org.imixs.workflow.engine.ModelService;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.faces.data.WorkflowController;
import org.imixs.workflow.faces.data.WorkflowEvent;
import org.imixs.workflow.util.XMLParser;
import org.openbpmn.bpmn.BPMNModel;

import jakarta.enterprise.context.ConversationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * CDI controller bean providing the agent conversation history for UI display.
 * Reads the persisted context from the current workitem and filters out
 * internal messages (system prompt, tool calls, tool results).
 */
@Named("aiAgentController")
@ConversationScoped
public class AIAgentController implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = Logger.getLogger(AIAgentController.class.getName());

    @Inject
    protected WorkflowController workflowController;

    @Inject
    AIAgentCache aiAgentCache;

    @Inject
    private ModelService modelService;

    @Inject
    WorkflowService workflowService;

    @Inject
    DocumentService documentService;

    private List<ItemCollection> cachedOperatorWorkitems = null;

    // Cache for the filtered chat history. Rebuilt only when null, or when
    // invalidated by a WorkflowEvent indicating the workitem has changed.
    private List<ItemCollection> cachedChatHistory = null;

    /**
     * Returns the current ai agent status form the AgentCache
     * 
     * @return
     */
    public String getAgentStatus() {

        ItemCollection source = resolveContextSource();
        if (source == null) {
            return AIAgentOperator.AGENT_STATUS_UNDEFINED;
        }
        String status = source.getItemValueString(AIAgentOperator.ITEM_AGENT_STATUS);
        return status;
    }

    /**
     * Returns the conversation history for UI display. The result is cached for the
     * lifetime of the conversation scope and only rebuilt when the cache is empty
     * (e.g. on first access, or after {@link #onWorkflowEvent} invalidated it).
     * <p>
     * The method first checks the {@link AIAgentCache} for a live version of the
     * current workitem. If a cached version exists, it is used to read the
     * conversation context — ensuring that the chat history is up to date while the
     * agent loop is still running. If no cached version exists, the persisted
     * workitem from the {@link WorkflowController} is used as fallback.
     * <p>
     * System messages, tool calls and tool results are filtered out — only user
     * messages and plain-text assistant responses are included.
     *
     * @return list of {@link ItemCollection} instances with chat.role and
     *         chat.message
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public List<ItemCollection> getChatHistory() {
        // Return cached result if already built
        if (cachedChatHistory != null) {
            return cachedChatHistory;
        }

        List<ItemCollection> result = new ArrayList<>();

        ItemCollection source = resolveContextSource();
        if (source == null) {
            return result;
        }

        String contextItem = source.getItemValueString(AIAgentOperator.AGENT_CONFIG_CONTEXT_ITEM);
        if (contextItem.isBlank()) {
            logger.warning("Missing " + AIAgentOperator.AGENT_CONFIG_CONTEXT_ITEM
                    + " item in current workitem - can not resolve chat history.");
            return result;
        }

        List<Object> contextItems = source.getItemValue(contextItem);

        for (Object entry : contextItems) {
            if (!(entry instanceof Map)) {
                continue;
            }
            ItemCollection message = new ItemCollection((Map) entry);
            String role = message.getItemValueString(ImixsAIContextHandler.ITEM_ROLE);

            // Skip system messages — internal only
            if (ImixsAIContextHandler.ROLE_SYSTEM.equals(role)) {
                continue;
            }
            // Skip tool results — internal only
            if (ImixsAIContextHandler.ROLE_TOOL.equals(role)) {
                continue;
            }
            // Skip assistant tool call messages — internal only
            if (ImixsAIContextHandler.ROLE_ASSISTANT.equals(role)
                    && message.getItemValueBoolean("chat.is_tool_call")) {
                continue;
            }

            result.add(message);
        }

        cachedChatHistory = result;
        return cachedChatHistory;
    }

    /**
     * Returns the conversation history in reverse order for UI display.
     * <p>
     * Note: a copy of the cached list is reversed here so that the cached
     * forward-order history returned by {@link #getChatHistory()} is not mutated as
     * a side effect.
     *
     * @return list of {@link ItemCollection} instances in reverse chronological
     *         order
     */
    public List<ItemCollection> getChatHistoryReverse() {
        List<ItemCollection> result = new ArrayList<>(getChatHistory());
        Collections.reverse(result);
        return result;
    }

    /**
     * Returns the last user message from the chat history, or {@code null} if no
     * user message exists yet.
     *
     * @return the last {@link ItemCollection} with role
     *         {@link ImixsAIContextHandler#ROLE_USER}, or {@code null}
     */
    public ItemCollection getLastUserMessage() {
        List<ItemCollection> history = getChatHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            ItemCollection message = history.get(i);
            if (ImixsAIContextHandler.ROLE_USER
                    .equals(message.getItemValueString(ImixsAIContextHandler.ITEM_ROLE))) {
                return message;
            }
        }
        return null;
    }

    /**
     * Returns the last assistant message from the chat history, or {@code null} if
     * no assistant message exists yet. Internal tool-call messages are already
     * filtered out by {@link #getChatHistory()}.
     *
     * @return the last {@link ItemCollection} with role
     *         {@link ImixsAIContextHandler#ROLE_ASSISTANT}, or {@code null}
     */
    public ItemCollection getLastAssistantMessage() {
        List<ItemCollection> history = getChatHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            ItemCollection message = history.get(i);
            if (ImixsAIContextHandler.ROLE_ASSISTANT
                    .equals(message.getItemValueString(ImixsAIContextHandler.ITEM_ROLE))) {
                return message;
            }
        }
        return null;
    }

    /**
     * Returns the tool call history for UI display. The method first checks the
     * {@link AIAgentCache} for a live version of the current workitem. If a cached
     * version exists, it is used to read the conversation context — ensuring that
     * tool call results are visible in the UI while the agent loop is still
     * running. If no cached version exists, the persisted workitem from the
     * {@link WorkflowController} is used as fallback.
     * <p>
     * Only messages with role {@code tool} are included in the result.
     *
     * @return list of {@link ItemCollection} instances representing tool results
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public List<ItemCollection> getToolCallHistory() {
        List<ItemCollection> result = new ArrayList<>();

        ItemCollection source = resolveContextSource();
        if (source == null) {
            return result;
        }

        String contextItem = source.getItemValueString(AIAgentOperator.AGENT_CONFIG_CONTEXT_ITEM);
        List<Object> contextItems = source.getItemValue(contextItem);

        for (Object entry : contextItems) {
            if (!(entry instanceof Map)) {
                continue;
            }
            ItemCollection message = new ItemCollection((Map) entry);
            if (ImixsAIContextHandler.ROLE_TOOL.equals(
                    message.getItemValueString(ImixsAIContextHandler.ITEM_ROLE))) {
                result.add(message);
            }
        }
        return result;
    }

    /**
     * Returns a list of all workitems created by the agent task. Results are cached
     * within the conversation scope and only reloaded when the reference list size
     * changes.
     *
     * @return list of operator workitems
     */
    public List<ItemCollection> getOperatorWorkitems() {
        ItemCollection source = resolveContextSource();
        if (source == null) {
            return Collections.emptyList();
        }

        List<String> refList = workflowController.getWorkitem()
                .getItemValueList(AIAgentOperator.ITEM_AGENT_WORKITEM_REF, String.class);

        // Return cached result if reference list has not changed
        if (cachedOperatorWorkitems != null) {
            return cachedOperatorWorkitems;
        }

        // Reload from DocumentService
        List<ItemCollection> result = new ArrayList<>();
        for (String refID : refList) {
            ItemCollection doc = documentService.load(refID);
            if (doc != null) {
                result.add(doc);
            }
        }

        cachedOperatorWorkitems = result;
        return cachedOperatorWorkitems;
    }

    /**
     * Observes {@link WorkflowEvent} notifications and invalidates the cached chat
     * history whenever the current workitem changes. The next call to
     * {@link #getChatHistory()} will then rebuild the cache from the (now current)
     * context.
     *
     * @param workflowEvent the CDI workflow event
     * @throws PluginException
     */
    public void onWorkflowEvent(@Observes WorkflowEvent workflowEvent) throws PluginException {
        if (workflowEvent == null || workflowEvent.getWorkitem() == null) {
            return;
        }
        int eventType = workflowEvent.getEventType();
        if (WorkflowEvent.WORKITEM_CHANGED == eventType) {
            // Reset caches so they get rebuilt on next access
            cachedChatHistory = null;
            cachedOperatorWorkitems = null;
        }

        // reset agent status
        if (WorkflowEvent.WORKITEM_BEFORE_PROCESS == eventType) {
            resetAgentStatusPending(workflowEvent.getWorkitem());
        }
    }

    /**
     * This helper method is called on the workitem event BEFORE_PROCESS to reset
     * the agent status into PENDING. The method loads the BPMN event definition for
     * the current workitem and checks whether the workflow result contains an
     * eventlog configuration with the topic "ai.agent.process". If so, the method
     * reset the agent status = PENDING.
     *
     * @param processingEvent the CDI processing event fired by the WorkflowService
     * @throws PluginException
     */
    public void resetAgentStatusPending(ItemCollection workitem) throws PluginException {
        long l = System.currentTimeMillis();
        // Load the BPMN event definition from the model.
        try {

            ModelManager modelManager = new ModelManager(workflowService);
            BPMNModel bpmnModel = modelService.getBPMNModel(workitem.getModelVersion());
            ItemCollection event = modelManager.findEventByID(bpmnModel, workitem.getTaskID(), workitem.getEventID());

            // Event not found in model — silently ignore.
            // This happens for every processWorkItem() call where the event
            // is not the ai.agent.process submit event (e.g. success-event, error-event).
            if (event == null) {
                return;
            }

            // parse for eventlog definition to see if we have a agent event....
            List<ItemCollection> bpmnEventLogDefinitions = workflowService.evalWorkflowResultXML(event, "eventlog",
                    AIAgentOperator.AGENT_TOPIC_PROCESS, workitem, true);
            if (bpmnEventLogDefinitions == null || bpmnEventLogDefinitions.isEmpty()) {
                // no op - return
                return;
            }

            // parse the first eventLog document configuration
            ItemCollection eventLogConfig = bpmnEventLogDefinitions.get(0);
            String documentXML = eventLogConfig.getItemValueString("document");

            // do we have a document definition?
            if (!documentXML.isEmpty()) {
                ItemCollection eventLogDocumentData = XMLParser.parseItemStructure(documentXML);

                // Check whether the workflow result of this event contains an
                // <eventlog name="ai.agent.process"> definition. This is the same
                // signal the EventLogPlugin reacts on — no extra configuration needed.

                String endpoint = eventLogDocumentData.getItemValueString(AIAgentOperator.AGENT_CONFIG_ENDPOINT);
                int successEvent = eventLogDocumentData.getItemValueInteger(AIAgentOperator.AGENT_CONFIG_SUCCESS_EVENT);
                int errorEvent = eventLogDocumentData.getItemValueInteger(AIAgentOperator.AGENT_CONFIG_ERROR_EVENT);

                if (endpoint.isBlank()) {
                    throw new PluginException(AIAgentOperator.class.getSimpleName(),
                            "AGENT_CONFIG_ERROR",
                            AIAgentOperator.AGENT_CONFIG_ENDPOINT
                                    + " must not be empty! Verify BPMN event configuration.");
                }
                if (successEvent == 0) {
                    throw new PluginException(AIAgentOperator.class.getSimpleName(),
                            "AGENT_CONFIG_ERROR",
                            AIAgentOperator.AGENT_CONFIG_SUCCESS_EVENT
                                    + " must not be 0! Verify BPMN event configuration.");
                }
                if (errorEvent == 0) {
                    throw new PluginException(AIAgentOperator.class.getSimpleName(),
                            "AGENT_CONFIG_ERROR",
                            AIAgentOperator.AGENT_CONFIG_ERROR_EVENT
                                    + " must not be 0! Verify BPMN event configuration.");
                }
                logger.info(
                        "├── AIAgentController: resolved bpmn logevent in " + (System.currentTimeMillis() - l) + "ms");

                workitem.setItemValue(AIAgentOperator.ITEM_AGENT_STATUS, AIAgentOperator.AGENT_STATUS_PENDING);
                aiAgentCache.put(workitem);
                logger.info("│   ├── Agent.status=" + workitem.getItemValueString(AIAgentOperator.ITEM_AGENT_STATUS));

            }
        } catch (ModelException e) {
            // Model not found or event not resolvable — not an agent task
            throw new PluginException(e.getErrorContext(), e.getErrorCode(), e.getMessage(), e);

        }

        logger.info("├── BPMNAgentProcessingHandler: building skill snapshot"
                + " for workitem " + workitem.getUniqueID());

    }

    /**
     * Resolves the effective workitem source for context reading. Returns the
     * cached version of the current workitem if available in the
     * {@link AIAgentCache} — ensuring real-time data during an active agent loop.
     * Falls back to the persisted workitem from the {@link WorkflowController} if
     * no cache entry exists.
     *
     * @return the effective {@link ItemCollection} source, or {@code null} if no
     *         workitem is available
     */
    private ItemCollection resolveContextSource() {
        ItemCollection workitem = workflowController.getWorkitem();
        if (workitem == null) {
            return null;
        }
        ItemCollection cached = aiAgentCache.getAgentWorkitem(workitem.getUniqueID());
        return cached != null ? cached : workitem;
    }
}