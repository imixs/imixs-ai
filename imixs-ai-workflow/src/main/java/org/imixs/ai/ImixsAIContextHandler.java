package org.imixs.ai;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;

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
public class ImixsAIContextHandler {

    private static final Logger logger = Logger.getLogger(ImixsAIContextHandler.class.getName());

    public static final String ITEM_ROLE = "chat.role";
    public static final String ITEM_MESSAGE = "chat.message";
    public static final String ITEM_DATE = "chat.date";
    public static final String ITEM_USERID = "chat.userid";

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    private ItemCollection workItem;
    private String itemNameContext;

    // Message container for API
    private List<ItemCollection> context;

    // Options as flexible JSON object
    private JsonObject options;

    /**
     * Default constructor reads an existing conversation from the item
     * 'itemNameChatHistory'.
     */
    public ImixsAIContextHandler(ItemCollection workItem, String itemNameContext) {
        this.workItem = workItem;
        this.itemNameContext = itemNameContext;
        importContext(workItem, itemNameContext);
        this.options = Json.createObjectBuilder().build();
    }

    /**
     * Add a system message to the conversation (for LLM context only, not shown in
     * chat history)
     */
    public ImixsAIContextHandler addSystemMessage(String content) {
        ItemCollection message = new ItemCollection();
        message.setItemValue(ITEM_ROLE, ROLE_SYSTEM);
        message.setItemValue(ITEM_MESSAGE, content);
        context.add(message);
        return this;
    }

    /**
     * Add a user question (starts new chat history entry)
     */
    public ImixsAIContextHandler addQuestion(String content, String userId, Date timestamp) {
        ItemCollection message = new ItemCollection();
        message.setItemValue(ITEM_ROLE, ROLE_USER);
        message.setItemValue(ITEM_MESSAGE, content);
        message.setItemValue(ITEM_DATE, timestamp);
        message.setItemValue(ITEM_USERID, userId);
        context.add(message);

        return this;
    }

    /**
     * Add assistant answer (completes current chat history entry)
     */
    public ImixsAIContextHandler addAnswer(String content) {
        ItemCollection message = new ItemCollection();
        message.setItemValue(ITEM_ROLE, ROLE_ASSISTANT);
        message.setItemValue(ITEM_MESSAGE, content);
        context.add(message);

        return this;
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
     * Convert to JSON string for API calls or database storage
     */
    @Override
    public String toString() {
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

        JsonObject result = builder.build();
        logger.fine("Generated JSON: " + result.toString());
        return result.toString();
    }

    /**
     * Get chat history for UI display
     */
    public List<ItemCollection> getContext() {
        return context;
    }

    /**
     * converts the Map List of a workitem into a List of ItemCollections
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void importContext(ItemCollection workitem, String childItemName) {
        // convert current list of childItems into ItemCollection elements
        context = new ArrayList<ItemCollection>();

        List<Object> mapOrderItems = workitem.getItemValue(childItemName);
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