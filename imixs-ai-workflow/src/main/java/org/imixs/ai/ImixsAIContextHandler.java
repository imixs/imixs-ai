package org.imixs.ai;

import java.io.IOException;
import java.io.Serializable;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.imixs.ai.workflow.ImixsAIPromptEvent;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.ObserverException;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;

/**
 * The ImixsAIContextHandler is a mutable builder class for conversations with
 * an LLM. The class holds the context for a conversation based on a history of
 * system, user and assistant messages. The context is stored in a List of
 * ItemCollection instances that can be persisted and managed by the
 * Imixs-Workflow engine.
 * <p>
 * The class supports methods to add system messages, user questions with
 * metadata, and assistant answers.
 * <p>
 * In addition the ImixsAIContextHandler provides method to convert a
 * conversation into a OpenAI API-compatible message format.
 */
@Named
public class ImixsAIContextHandler implements Serializable {

    private static final Logger logger = Logger.getLogger(ImixsAIContextHandler.class.getName());

    public static final String ERROR_PROMPT_TEMPLATE = "ERROR_LLM_PROMPT_TEMPLATE";
    public static final String ERROR_INVALID_PARAMETER = "ERROR_INVALID_PARAMETER";

    public static final String ITEM_ROLE = "chat.role";
    public static final String ITEM_MESSAGE = "chat.message";
    public static final String ITEM_DATE = "chat.date";
    public static final String ITEM_USERID = "chat.userid";

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    private ItemCollection workItem = null;
    private String itemNameContext;
    private java.util.logging.Level logLevel = Level.INFO;

    @Inject
    private Event<ImixsAIPromptEvent> llmPromptEventObservers = null;

    // Message container for API
    private List<ItemCollection> context = null;

    // Options as flexible JSON object
    private JsonObject options;

    private boolean stream = false;

    /**
     * Default constructor
     */
    public ImixsAIContextHandler() {
        init();
    }

    // reset context
    public void init() {
        context = new ArrayList<ItemCollection>();
        this.options = Json.createObjectBuilder().build();
    }

    /**
     * The the workitem context
     * 
     * @param workItem
     */
    public void setWorkItem(ItemCollection workItem) {
        this.workItem = workItem;
    }

    public java.util.logging.Level getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(java.util.logging.Level logLevel) {
        this.logLevel = logLevel;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    /**
     * Add a message and role to the conversation
     * <p>
     * If the message is a SYSTEM message, the context will be cleared before. Not
     * two system messages are allowed in one context!
     * 
     * @throws PluginException
     */
    public ImixsAIContextHandler addMessage(String role, String content, String userId, Date timestamp)
            throws PluginException {
        ItemCollection message = new ItemCollection();

        if (!ROLE_ASSISTANT.equals(role) && !ROLE_SYSTEM.equals(role) && !ROLE_USER.equals(role)) {
            throw new PluginException(
                    ImixsAIContextHandler.class.getSimpleName(),
                    ImixsAIContextHandler.ERROR_INVALID_PARAMETER,
                    "Missing Role parameter - system|user|assistant required!");
        }

        // reset context?
        if (ROLE_SYSTEM.equals(role)) {
            resetContext();
        }

        message.setItemValue(ITEM_ROLE, role);

        // Fire Prompt Event if EventObservers available...
        if (llmPromptEventObservers != null) {
            ImixsAIPromptEvent llmPromptEvent = new ImixsAIPromptEvent(content, workItem);
            try {
                llmPromptEventObservers.fire(llmPromptEvent);
            } catch (ObserverException e) {
                // catch Adapter Exceptions
                if (e.getCause() instanceof AdapterException) {
                    AdapterException ae = (AdapterException) e.getCause();
                    throw new PluginException(ae);
                }
            }
            content = llmPromptEvent.getPromptTemplate();
        }
        message.setItemValue(ITEM_MESSAGE, content);

        if (timestamp != null) {
            message.setItemValue(ITEM_DATE, timestamp);
        }
        if (userId != null) {
            message.setItemValue(ITEM_USERID, userId);

        }

        context.add(message);
        return this;
    }

    /**
     * This method reset the current context.
     */
    private void resetContext() {
        context = new ArrayList<ItemCollection>();
    }

    /**
     * Add a system message to the conversation (for LLM context only, not shown in
     * chat history)
     * 
     * @throws PluginException
     */
    public ImixsAIContextHandler addSystemMessage(String content) throws PluginException {
        return addMessage(ROLE_SYSTEM, content, null, null);
    }

    /**
     * Add a user question (starts new chat history entry)
     * 
     * @throws PluginException
     */
    public ImixsAIContextHandler addQuestion(String content, String userId, Date timestamp) throws PluginException {
        return addMessage(ROLE_USER, content, userId, timestamp);
    }

    /**
     * Add assistant answer (completes current chat history entry)
     * 
     * @throws PluginException
     */
    public ImixsAIContextHandler addAnswer(String content) throws PluginException {
        return addMessage(ROLE_ASSISTANT, content, null, null);
    }

    /**
     * This method adds an Imixs PromptDefinition as a new message entry into the
     * current context. The method evaluates the attribute 'role' from the tag
     * prompt containing the template.
     * <p>
     * The method fires a prompt event to all registered PromptEvent Observer
     * classes. This allows adaptors to customize the final prompt.
     * <p>
     * If the prompt definition contains options, the method updates the options of
     * the current Context.
     * 
     * 
     * @param promptTemplate - a imixs-ai prompt XML-Template
     * @param workitem       - the workitem to be processed
     * @throws PluginException
     * @throws AdapterException
     */
    public ImixsAIContextHandler addPromptDefinition(String promptTemplate)
            throws PluginException, AdapterException {

        if (workItem == null) {
            throw new PluginException(
                    ImixsAIContextHandler.class.getSimpleName(),
                    ERROR_INVALID_PARAMETER,
                    "Workitem is not set - call importContext or set workitem!");
        }

        String prompt = null;
        String role = null;
        // Extract Meta Information from XML....
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new java.io.ByteArrayInputStream(promptTemplate.getBytes()));

            parseLogLevel(doc);

            // check prompt_options
            NodeList optionNodes = doc.getElementsByTagName("prompt_options");
            if (optionNodes.getLength() > 0) {
                Node modelNode = optionNodes.item(0);
                String promptOptions = modelNode.getTextContent();
                if (!promptOptions.isBlank()) {
                    logger.fine("Update PromptOptions: " + promptOptions);
                    this.setOptions(promptOptions);
                }

            }

            // extract each prompt and role
            NodeList promptNodes = doc.getElementsByTagName("prompt");
            for (int i = 0; i < promptNodes.getLength(); i++) {

                Element modelNode = (Element) promptNodes.item(i);
                prompt = modelNode.getTextContent();
                role = modelNode.getAttribute("role");
                if (role.isBlank()) {
                    role = ROLE_USER;
                }
                addMessage(role, prompt, null, null);
            }

        } catch (IOException | ParserConfigurationException | SAXException e) {
            throw new PluginException(
                    ImixsAIContextHandler.class.getSimpleName(),
                    ImixsAIContextHandler.ERROR_PROMPT_TEMPLATE,
                    "Unable to extract meta data from prompt template: " + e.getMessage(), e);
        }

        return this;
    }

    /**
     * Extract and apply logging level form prompt definition
     * 
     * @param doc
     */
    private void parseLogLevel(Document doc) {
        NodeList loggingNodes = doc.getElementsByTagName("loglevel");
        if (loggingNodes.getLength() > 0) {
            Node loggingNode = loggingNodes.item(0);
            String logLevelStr = loggingNode.getTextContent().trim();
            if (!logLevelStr.isBlank()) {
                try {
                    // convert to java.util.logging.Level
                    logLevel = java.util.logging.Level.parse(logLevelStr.toUpperCase());

                    // Setze das Level für diesen Logger
                    logger.setLevel(logLevel);

                    // Optional: Setze auch für die Eltern-Handler
                    // java.util.logging.Logger parentLogger = logger.getParent();
                    // if (parentLogger != null) {
                    // parentLogger.setLevel(level);
                    // }

                    logger.config("Logger level set to: " + logLevel.getName());

                } catch (IllegalArgumentException e) {
                    logger.warning("Invalid log level '" + logLevelStr +
                            "' in XML. Using default level.");
                }
            }
        }
    }

    /**
     * Set options from JSON string
     */
    public ImixsAIContextHandler setOptions(String optionsJson) {
        if (optionsJson != null && !optionsJson.trim().isEmpty()) {
            try (JsonReader reader = Json.createReader(new StringReader(optionsJson))) {
                this.options = reader.readObject();
            }
        } else {
            this.options = Json.createObjectBuilder().build();
        }
        return this;
    }

    /**
     * Add or merge additional options from JSON string
     */
    public ImixsAIContextHandler addOptions(String optionsJson) {
        if (optionsJson != null && !optionsJson.trim().isEmpty()) {
            try (JsonReader reader = Json.createReader(new StringReader(optionsJson))) {
                JsonObject newOptions = reader.readObject();
                JsonObjectBuilder builder = Json.createObjectBuilder(this.options);
                // Merge new options
                for (String key : newOptions.keySet()) {
                    builder.add(key, newOptions.get(key));
                }
                this.options = builder.build();
            }
        }
        return this;
    }

    /**
     * Set a single option
     */
    public ImixsAIContextHandler setOption(String key, String value) {
        JsonObjectBuilder builder = Json.createObjectBuilder(this.options);
        builder.add(key, value);
        this.options = builder.build();
        return this;
    }

    /**
     * Set a single boolean option
     */
    public ImixsAIContextHandler setOption(String key, boolean value) {
        JsonObjectBuilder builder = Json.createObjectBuilder(this.options);
        builder.add(key, value);
        this.options = builder.build();
        return this;
    }

    /**
     * Set a single numeric option
     */
    public ImixsAIContextHandler setOption(String key, double value) {
        JsonObjectBuilder builder = Json.createObjectBuilder(this.options);
        builder.add(key, value);
        this.options = builder.build();
        return this;
    }

    /**
     * Returns a JSON Object presenting an OpenAI API Message request object
     */
    public JsonObject getOpenAIMessageObject() {
        JsonObjectBuilder builder = Json.createObjectBuilder();

        // Build messages array
        JsonArrayBuilder messagesArray = Json.createArrayBuilder();
        for (ItemCollection message : context) {
            JsonObjectBuilder messageBuilder = Json.createObjectBuilder();
            messageBuilder.add("role", message.getItemValueString(ITEM_ROLE));
            messageBuilder.add("content", message.getItemValueString(ITEM_MESSAGE));
            messagesArray.add(messageBuilder);
        }
        builder.add("messages", messagesArray);

        // Merge all options
        for (String key : options.keySet()) {
            builder.add(key, options.get(key));
        }
        if (stream) {
            builder.add("stream", true);
        }
        JsonObject result = builder.build();
        logger.fine("Generated JSON: " + result.toString());
        return result;
    }

    /**
     * Converts the current Message Object into JSON string
     */
    @Override
    public String toString() {
        return getOpenAIMessageObject().toString();
    }

    /**
     * Get chat history for UI display
     */
    public List<ItemCollection> getContext() {
        if (context == null) {
            context = new ArrayList<ItemCollection>();
        }
        return context;
    }

    /**
     * converts the Map List of a workitem into a List of ItemCollections
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void importContext(ItemCollection workitem, String itemNameContext) {
        this.init();
        this.workItem = workitem;
        this.itemNameContext = itemNameContext;
        context = new ArrayList<ItemCollection>();

        List<Object> mapOrderItems = workitem.getItemValue(itemNameContext);
        for (Object mapOderItem : mapOrderItems) {
            if (mapOderItem instanceof Map) {
                ItemCollection itemCol = new ItemCollection((Map) mapOderItem);
                context.add(itemCol);
            }
        }

    }

    /**
     * Convert the List of ItemCollections back into a List of Map elements
     * 
     * @param workitem
     */
    @SuppressWarnings({ "rawtypes" })
    public void storeContext() {
        List<Map> mapOrderItems = new ArrayList<Map>();
        // convert the child ItemCollection elements into a List of Map
        if (context != null) {
            // iterate over all order items..
            for (ItemCollection orderItem : context) {
                mapOrderItems.add(orderItem.getAllItems());
            }
            workItem.replaceItemValue(itemNameContext, mapOrderItems);
        }
    }
}