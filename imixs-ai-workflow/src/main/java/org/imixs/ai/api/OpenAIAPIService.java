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

package org.imixs.ai.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Serializable;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.ai.ImixsAIContextHandler;
import org.imixs.ai.workflow.ImixsAIPromptEvent;
import org.imixs.ai.workflow.ImixsAIResultEvent;
import org.imixs.ai.workflow.ImixsAIToolCallEvent;
import org.imixs.workflow.FileData;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.ModelService;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.ObserverException;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.ws.rs.core.MediaType;

/**
 * The OpenAIAPIService provides methods to post prompt templates to the OpenAI
 * API supported by Llama-cpp http server.
 * 
 * The service supports various processing events to update a prompt template
 * and to evaluate a completion result
 * 
 * 
 * @author rsoika
 *
 */
@Stateless
@LocalBean
public class OpenAIAPIService implements Serializable {

    private static final long serialVersionUID = 1L;
    private static Logger logger = Logger.getLogger(OpenAIAPIService.class.getName());

    public static final String ERROR_API = "ERROR_LLM_API";
    public static final String ERROR_PROMPT_TEMPLATE = "ERROR_LLM_PROMPT_TEMPLATE";
    public static final String ERROR_PROMPT_INFERENCE = "ERROR_LLM_PROMPT_INFERENCE";
    public static final String ITEM_AI_RESULT = "ai.result";
    public static final String ITEM_AI_RESULT_ITEM = "ai.result.item";
    public static final String ITEM_SUGGEST_ITEMS = "ai.suggest.items";
    public static final String ITEM_SUGGEST_MODE = "ai.suggest.mode";

    public static final String LLM_MODEL = "llm.model";

    public static final String ENV_LLM_SERVICE_ENDPOINT_TIMEOUT = "llm.service.timeout";

    @Inject
    @ConfigProperty(name = OpenAIAPIConnector.ENV_LLM_SERVICE_ENDPOINT)
    Optional<String> serviceEndpoint;

    @Inject
    @ConfigProperty(name = ENV_LLM_SERVICE_ENDPOINT_TIMEOUT, defaultValue = "120000")
    int serviceTimeout;

    @Inject
    protected OpenAIAPIConnector openAIAPIConnector;

    @Inject
    protected ModelService modelService;

    @Inject
    protected WorkflowService workflowService;

    @Inject
    private Event<ImixsAIPromptEvent> llmPromptEventObservers = null;

    @Inject
    private Event<ImixsAIResultEvent> llmResultEventObservers = null;

    @Inject
    private Event<ImixsAIToolCallEvent> toolCallEventObservers = null;

    /**
     * This method returns a string with all the text content of all documents
     * attached to a workitem.
     * 
     * @return
     */
    @SuppressWarnings("rawtypes")
    public String getAllDocumentText(ItemCollection workitem) {
        if (workitem == null) {
            return null;
        }

        String result = "";
        List<FileData> fileDataList = workitem.getFileData();

        for (FileData fileData : fileDataList) {
            List fileText = (List) fileData.getAttribute("text");
            if (fileText != null && fileText.size() > 0) {
                result = result + fileText.get(0) + " ";
            }
        }

        return result;
    }

    /**
     * This method returns the result message of an OpenAI API completions JSON
     * result string. The String is expected in the OpenAI API completion result
     * format. For backward compatibility the method also supports the old Llama.cpp
     * format
     * 
     * @param jsonCompletionResult - a JSON String holding the completion result
     * @param resultEventType      - optional event type send to all CDI Event
     *                             observers for the LLMResultEvent
     * @param workitem             - workitem instance for an ImixsAIResultEvent
     * @throws PluginException
     */
    public String processPromptResult(String jsonCompletionResult, String resultEventType, ItemCollection workitem)
            throws PluginException {

        // Parse the JSON result
        JsonReader jsonReader = Json.createReader(new StringReader(jsonCompletionResult));
        JsonObject parsedJsonObject = jsonReader.readObject();
        jsonReader.close();

        String promptResult = null;

        // Case 1: OpenAI chat format -> {"choices":[{"message":{"content":"..."}}]}
        if (parsedJsonObject.containsKey("choices")) {
            JsonArray choices = parsedJsonObject.getJsonArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JsonObject firstChoice = choices.getJsonObject(0);
                if (firstChoice.containsKey("message")) {
                    JsonObject message = firstChoice.getJsonObject("message");
                    promptResult = message.getString("content", null);
                }
            }
        }

        // Case 2: old Llama.cpp format -> {"content": "..."}
        if (promptResult == null && parsedJsonObject.containsKey("content")) {
            promptResult = parsedJsonObject.getString("content", null);
        }

        // Error handling
        if (promptResult == null) {
            throw new PluginException(OpenAIAPIService.class.getSimpleName(),
                    ERROR_PROMPT_INFERENCE, "Error during POST prompt - no result returned!");
        }

        // Clean result
        promptResult = promptResult.trim();

        // Fire CDI event (Adapters can resolve the result)
        if (resultEventType != null && !resultEventType.isEmpty()) {
            ImixsAIResultEvent llmResultEvent = new ImixsAIResultEvent(promptResult, resultEventType, workitem);
            llmResultEventObservers.fire(llmResultEvent);
        }

        return promptResult;
    }

    /**
     * This method processes a tool call response from the LLM. If the finish_reason
     * is "tool_calls", the method fires an ImixsAIToolCallEvent for each tool call
     * so that observers can handle it. The result is added to the context as a tool
     * message.
     *
     * Returns true if tool calls were found and handled, false if the response is a
     * normal text completion.
     *
     * @param jsonCompletionResult - raw JSON response from the LLM
     * @param contextHandler       - the current conversation context
     * @throws PluginException
     */
    public boolean processToolCallResult(String jsonCompletionResult,
            ImixsAIContextHandler contextHandler) throws PluginException {

        // Parse the JSON result
        JsonReader jsonReader = Json.createReader(new StringReader(jsonCompletionResult));
        JsonObject parsedJsonObject = jsonReader.readObject();
        jsonReader.close();

        // Check finish_reason
        if (!parsedJsonObject.containsKey("choices")) {
            return false;
        }
        JsonArray choices = parsedJsonObject.getJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return false;
        }
        JsonObject firstChoice = choices.getJsonObject(0);
        String finishReason = firstChoice.getString("finish_reason", "");
        if (!"tool_calls".equals(finishReason)) {
            return false;
        }

        // Extract tool_calls from the assistant message
        JsonObject message = firstChoice.getJsonObject("message");
        JsonArray toolCalls = message.getJsonArray("tool_calls");
        if (toolCalls == null || toolCalls.isEmpty()) {
            return false;
        }

        // Add the assistant message with tool_calls to the context
        // so the LLM knows what it requested
        contextHandler.addToolCallAssistantMessage(message.toString());

        // Process each tool call
        for (JsonValue toolCallValue : toolCalls) {
            JsonObject toolCall = toolCallValue.asJsonObject();
            String toolCallId = toolCall.getString("id");
            JsonObject function = toolCall.getJsonObject("function");
            String toolName = function.getString("name");

            // Parse arguments - this is JSON in JSON!
            String argumentsString = function.getString("arguments");
            JsonObject arguments;
            try (JsonReader argReader = Json.createReader(new StringReader(argumentsString))) {
                arguments = argReader.readObject();
            }

            logger.info("├── Tool Call: " + toolName + " / arguments: " + arguments);

            // Fire CDI event - observers handle the actual execution
            ImixsAIToolCallEvent toolCallEvent = new ImixsAIToolCallEvent(
                    toolName, arguments, toolCallId);
            toolCallEventObservers.fire(toolCallEvent);

            if (!toolCallEvent.isHandled()) {
                throw new PluginException(OpenAIAPIService.class.getSimpleName(),
                        ERROR_PROMPT_INFERENCE,
                        "No observer handled tool call: " + toolName);
            }

            // Add tool result to context for next LLM request
            contextHandler.addToolResult(toolCallId, toolCallEvent.getResult());

            logger.info("└── Tool Call handled: " + toolName
                    + " result length=" + toolCallEvent.getResult().length());
        }

        return true;
    }

    /**
     * This method POSTs a LLM Prompt to the service endpoint '/completion' and
     * returns the predicted completion. The method returns the response body.
     * <p>
     * The endpoint is optional and can be null. In the endpoint is not provided the
     * method resolves the endpoint from the environment variable
     * <code>llm.service.endpoint</code>.
     * <p>
     * The method optional test if the environment variables
     * LLM_SERVICE_ENDPOINT_USER and LLM_SERVICE_ENDPOINT_PASSWORD are set. In this
     * case a BASIC Authentication is used for the connection to the LLMService.
     * 
     * See details:
     * https://github.com/ggerganov/llama.cpp/blob/master/examples/server/README.md#api-endpoints
     * 
     * 
     * curl example:
     * 
     * curl --request POST \ --url http://localhost:8080/completion \ --header
     * "Content-Type: application/json" \ --data '{"prompt": "Building a website can
     * be done in 10 simple steps:","n_predict": 128}'
     * 
     * @param jsonPromptObject - an LLM json prompt object
     * @param apiEndpoint      - optional service endpoint
     * @throws PluginException
     */
    public String postPromptCompletion(ImixsAIContextHandler imixsAIContextHandler, String apiEndpoint, boolean debug)
            throws PluginException {
        String response = null;
        long processingTime = System.currentTimeMillis();
        try {
            HttpURLConnection conn = openAIAPIConnector.createHttpConnection(apiEndpoint,
                    OpenAIAPIConnector.ENDPOINT_URI_COMPLETIONS);

            imixsAIContextHandler.log(debug ? Level.INFO : Level.FINE,
                    "├── POST Completion: " + conn.getURL().toString());

            // Set the appropriate HTTP method
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON + "; utf-8");
            conn.setRequestProperty("Accept", MediaType.APPLICATION_JSON);
            conn.setDoOutput(true);

            // Write the JSON object to the output stream
            String jsonString = imixsAIContextHandler.getOpenAIMessageObject().toString();

            imixsAIContextHandler.log(debug ? Level.INFO : Level.FINE, "│   ├── 📥 Completion Request: ");
            imixsAIContextHandler.log(debug ? Level.INFO : Level.FINE, jsonString);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonString.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Reading the response
            int responseCode = conn.getResponseCode();
            logger.fine("POST Response Code :: " + responseCode);
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder responseBody = new StringBuilder();
                    String responseLine = null;
                    while ((responseLine = br.readLine()) != null) {
                        responseBody.append(responseLine.trim() + "\n");
                    }
                    response = responseBody.toString();

                    imixsAIContextHandler.log(debug ? Level.INFO : Level.FINE, "│   ├── 📤 Completion Result: ");
                    imixsAIContextHandler.log(debug ? Level.INFO : Level.FINE, response);

                }
            } else {
                logger.severe("└──  ⚠️ postCompletion failed -  HTTP Result=" + responseCode);
                throw new PluginException(OpenAIAPIService.class.getSimpleName(),
                        OpenAIAPIService.ERROR_PROMPT_INFERENCE,
                        "HTTP Result " + responseCode);
            }
            // Close the connection
            conn.disconnect();

            imixsAIContextHandler.log(debug ? Level.INFO : Level.FINE,
                    "└── POST Completion completed in " + (System.currentTimeMillis() - processingTime) + "ms");

            return response;

        } catch (IOException e) {
            logger.severe(e.getMessage());
            throw new PluginException(
                    OpenAIAPIService.class.getSimpleName(),
                    ERROR_PROMPT_TEMPLATE,
                    "⚠️ postCompletion failed - " + e.getClass().getName() + ": " + e.getMessage(), e);

        }

    }

    /**
     * RAG Support - compute vector by text
     *
     * See details:
     * https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md
     *
     *
     * curl example:
     *
     * curl --request POST \ --url http://localhost:8080/completion \ --header
     * "Content-Type: application/json" \ --data '{"prompt": "Building a website can
     * be done in 10 simple steps:","n_predict": 128}'
     *
     * @param prompt      - the prompt to be indexed
     * @param apiEndpoint - llm api endpoint
     * @param debug       - debug mode
     * @throws PluginException
     */
    public List<Float> postEmbedding(String prompt, String apiEndpoint, boolean debug) throws PluginException {

        List<Float> result = new ArrayList<>();

        if (debug) {
            logger.info("├── postEmbeddings...");
            logger.info("├── text size=" + prompt.length());
        }

        JsonObjectBuilder jsonObjectBuilder = Json.createObjectBuilder();
        jsonObjectBuilder.add("content", prompt);
        JsonObject jsonObject = jsonObjectBuilder.build();
        String jsonPrompt = jsonObject.toString();
        try {
            HttpURLConnection conn = openAIAPIConnector.createHttpConnection(apiEndpoint,
                    OpenAIAPIConnector.ENDPOINT_URI_EMBEDDINGS);

            // Set the appropriate HTTP method
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON + "; utf-8");
            conn.setRequestProperty("Accept", MediaType.APPLICATION_JSON);
            conn.setDoOutput(true);

            // Write the text to the output stream
            if (debug) {
                logger.info("│   ├── POST Text:");
                logger.info(jsonPrompt);
            }
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonPrompt.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Reading the response
            int responseCode = conn.getResponseCode();
            if (debug) {
                logger.info("│   ├── POST Response Code: " + responseCode);
            }
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder responseBody = new StringBuilder();
                    String responseLine = null;
                    while ((responseLine = br.readLine()) != null) {
                        responseBody.append(responseLine.trim() + "\n");
                        // logger.info("read line: " + responseLine);
                    }
                    String jsonResponse = responseBody.toString();
                    // logger.info(jsonResponse);
                    // Extract the vector field from the json string
                    try (JsonReader jsonReader = Json.createReader(new StringReader(jsonResponse))) {
                        // Lies das JSON-Array
                        JsonArray rootArray = jsonReader.readArray();
                        if (!rootArray.isEmpty()) {
                            JsonObject firstObject = rootArray.getJsonObject(0);
                            if (firstObject.containsKey("embedding")) {
                                JsonArray embeddingArray = firstObject.getJsonArray("embedding");
                                if (!embeddingArray.isEmpty()) {
                                    JsonArray firstEmbedding = embeddingArray.getJsonArray(0);
                                    // Extrahiere die Werte als List<Float>
                                    // result = new float[firstEmbedding.size()];
                                    for (int i = 0; i < firstEmbedding.size(); i++) {
                                        result.add((float) firstEmbedding.getJsonNumber(i).doubleValue());
                                    }
                                }
                            }
                        }
                    }
                }
            } else {

                // logger.severe("│ ├── ⚠️ postEmbeddings failed - '" + apiEndpoint
                // + OpenAIAPIConnector.ENDPOINT_URI_EMBEDDINGS + "' ");

                // FEHLERFALL: Response Body aus dem ErrorStream lesen
                String errorResponse = readStream(conn.getErrorStream());

                logger.severe("│   ├── ⚠️ postEmbeddings failed!");
                logger.severe("│   ├── Status: " + responseCode);
                logger.severe("│   ├── Response: " + errorResponse); // Hier ist der Content!

                throw new PluginException(OpenAIAPIService.class.getSimpleName(),
                        OpenAIAPIService.ERROR_PROMPT_INFERENCE,
                        "HTTP Result " + responseCode);
            }
            // Close the connection
            conn.disconnect();
            if (debug) {
                logger.info("│   ├── index size= " + result.size() + " floats");
                logger.info("├── ✅ postEmbeddings completed");
            }
            return result;

        } catch (IOException e) {

            logger.severe(e.getMessage());
            throw new PluginException(
                    OpenAIAPIService.class.getSimpleName(),
                    ERROR_PROMPT_TEMPLATE,
                    "⚠️ postEmbeddings failed - '" + apiEndpoint + "' : " + e.getMessage(), e);

        }

    }

    /**
     * This helper method builds a json prompt object for OpenAI API including
     * optional params.
     *
     * See details:
     * https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md
     *
     * @param prompt
     * @param stream         - boolean indicates if the client tries to stream the
     *                       result.
     * @param prompt_options
     * @return
     */
    public JsonObject buildJsonPromptObjectV1(ImixsAIContextHandler imixsAIContextHandler) {
        JsonObject messageObject = imixsAIContextHandler.getOpenAIMessageObject();
        JsonObjectBuilder jsonObjectBuilder = Json.createObjectBuilder();

        // Create the messages array with one user message
        JsonArrayBuilder messagesArrayBuilder = Json.createArrayBuilder();
        JsonObjectBuilder userMessageBuilder = Json.createObjectBuilder();
        userMessageBuilder.add("role", "user");
        userMessageBuilder.add("content", imixsAIContextHandler.toString());
        messagesArrayBuilder.add(userMessageBuilder);
        jsonObjectBuilder.add("messages", messagesArrayBuilder);

        JsonObject jsonObject = jsonObjectBuilder.build();

        logger.fine("buildJsonPromptObjectV1 completed:");
        logger.fine(jsonObject.toString());
        return jsonObject;
    }

    /**
     * RAG Support - builds a prompt for embeddings from a Imixs prompt template
     * <p>
     * This method builds a prompt for embeddings based on a prompt template. The
     * method first extracts the prompt from the prompt template. Next the method
     * fires a prompt event to all registered PromptEvent Observer classes. This
     * allows adaptors to customize the prompt.
     * 
     * Finally the method stores the prompt_options in the item
     * 'ai.prompt.prompt_options'
     * 
     * 
     * @param promptTemplate - a imixs-ai prompt XML-Template
     * @param workitem       - the workitem to be processed
     * @return the plain prompt to be send to the llm endpoint
     * @throws PluginException
     * @throws AdapterException
     */
    public String buildEmbeddingsPrompt(String promptTemplate, ItemCollection workitem)
            throws PluginException, AdapterException {

        if (promptTemplate == null || promptTemplate.isBlank()) {
            throw new PluginException(
                    OpenAIAPIService.class.getSimpleName(),
                    ERROR_PROMPT_TEMPLATE,
                    "Prompt template is empty, verify model configuration");
        }
        String prompt = null;
        // Extract Meta Information from XML....
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new java.io.ByteArrayInputStream(promptTemplate.getBytes()));

            // extract prompt
            NodeList modelNodes = doc.getElementsByTagName("prompt");
            if (modelNodes.getLength() > 0) {
                Node modelNode = modelNodes.item(0);
                prompt = modelNode.getTextContent();
            }

            if (prompt == null || prompt.isEmpty()) {
                throw new PluginException(
                        OpenAIAPIService.class.getSimpleName(),
                        ERROR_PROMPT_TEMPLATE,
                        "Missing prompt tag in embedding prompt template!");
            }

            // prompt_options
            modelNodes = doc.getElementsByTagName("prompt_options");
            if (modelNodes.getLength() > 0) {
                Node modelNode = modelNodes.item(0);
                workitem.setItemValue("ai.prompt.prompt_options", modelNode.getTextContent());
            }

        } catch (IOException | ParserConfigurationException | SAXException e) {
            throw new PluginException(
                    OpenAIAPIService.class.getSimpleName(),
                    ERROR_PROMPT_TEMPLATE,
                    "Unable to extract meta data from embedding prompt template: " + e.getMessage(), e);
        }

        // Fire Prompt Event...
        ImixsAIPromptEvent llmPromptEvent = new ImixsAIPromptEvent(prompt, workitem);
        try {
            llmPromptEventObservers.fire(llmPromptEvent);
        } catch (ObserverException e) {
            // catch Adapter Exceptions
            if (e.getCause() instanceof AdapterException) {
                throw (AdapterException) e.getCause();
            }

        }
        logger.finest(llmPromptEvent.getPromptTemplate());

        return llmPromptEvent.getPromptTemplate();
    }

    /**
     * Helper method to read an error message content
     * 
     * @param is
     * @return
     */
    private String readStream(InputStream is) {
        if (is == null)
            return "No error body";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line.trim());
            }
            return response.toString();
        } catch (IOException e) {
            return "Could not read error stream: " + e.getMessage();
        }
    }
}
