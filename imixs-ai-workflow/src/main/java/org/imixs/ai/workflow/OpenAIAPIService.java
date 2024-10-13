package org.imixs.ai.workflow;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Serializable;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.microprofile.config.inject.ConfigProperty;
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
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

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

    public static final String ENV_LLM_SERVICE_ENDPOINT = "llm.service.endpoint";
    public static final String ENV_LLM_SERVICE_ENDPOINT_USER = "llm.service.endpoint.user";
    public static final String ENV_LLM_SERVICE_ENDPOINT_PASSWORD = "llm.service.endpoint.password";
    public static final String ENV_LLM_SERVICE_ENDPOINT_TIMEOUT = "llm.service.timeout";

    @Inject
    @ConfigProperty(name = ENV_LLM_SERVICE_ENDPOINT)
    Optional<String> serviceEndpoint;

    @Inject
    @ConfigProperty(name = ENV_LLM_SERVICE_ENDPOINT_USER)
    Optional<String> serviceEndpointUser;

    @Inject
    @ConfigProperty(name = ENV_LLM_SERVICE_ENDPOINT_PASSWORD)
    Optional<String> serviceEndpointPassword;

    @Inject
    @ConfigProperty(name = ENV_LLM_SERVICE_ENDPOINT_TIMEOUT, defaultValue = "120000")
    int serviceTimeout;

    @Inject
    protected ModelService modelService;

    @Inject
    protected WorkflowService workflowService;

    @Inject
    private Event<ImixsAIPromptEvent> llmPromptEventObservers = null;

    @Inject
    private Event<ImixsAIResultEvent> llmResultEventObservers = null;

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
     * This method first extracts the prompt from the prompt template.
     * Next the method fires a prompt event to all registered PromptEvent Observer
     * classes. This allows adaptors to customize the prompt.
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
    public String buildPrompt(String promptTemplate, ItemCollection workitem) throws PluginException, AdapterException {

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
                        "Missing prompt tag in prompt template!");
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
                    "Unable to extract meta data from prompt template: " + e.getMessage(), e);
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
     * curl --request POST \
     * --url http://localhost:8080/completion \
     * --header "Content-Type: application/json" \
     * --data '{"prompt": "Building a website can be done in 10 simple
     * steps:","n_predict": 128}'
     * 
     * @param jsonPromptObject - an LLM json prompt object
     * @param apiEndpoint      - optional service endpoint
     * @throws PluginException
     */
    public String postPromptCompletion(JsonObject jsonPromptObject, String apiEndpoint)
            throws PluginException {
        String response = null;

        try {
            if (apiEndpoint == null) {
                // default to global endpoint
                if (!serviceEndpoint.isPresent()) {
                    throw new PluginException(OpenAIAPIService.class.getSimpleName(), ERROR_API,
                            "imixs-ai llm service endpoint is empty!");
                }
                apiEndpoint = serviceEndpoint.get();
            }
            if (!apiEndpoint.endsWith("/")) {
                apiEndpoint = apiEndpoint + "/";
            }
            URL url = new URL(apiEndpoint + "completion");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setConnectTimeout(serviceTimeout); // set timeout to 5 seconds
            conn.setReadTimeout(serviceTimeout);
            // Set Basic Authentication?
            if (serviceEndpointUser != null && serviceEndpointUser.isPresent() && !serviceEndpointUser.get().isEmpty()
                    && serviceEndpointPassword.isPresent() && !serviceEndpointPassword.get().isEmpty()) {
                String auth = serviceEndpointUser.get() + ":" + serviceEndpointPassword.get();
                byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
                String authHeaderValue = "Basic " + new String(encodedAuth);
                conn.setRequestProperty("Authorization", authHeaderValue);
            }

            // Set the appropriate HTTP method
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);

            // Write the JSON object to the output stream
            String jsonString = jsonPromptObject.toString();
            logger.fine("JSON Object=" + jsonString);

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
                    logger.fine("Response Body :: " + response);
                }
            } else {
                throw new PluginException(OpenAIAPIService.class.getSimpleName(),
                        ERROR_PROMPT_INFERENCE, "Error during POST prompt: HTTP Result " + responseCode);
            }
            // Close the connection
            conn.disconnect();
            logger.fine("===== postPromptCompletion completed");
            return response;

        } catch (IOException e) {
            logger.severe(e.getMessage());
            throw new PluginException(
                    OpenAIAPIService.class.getSimpleName(),
                    ERROR_PROMPT_TEMPLATE,
                    "Exception during POST prompt - " + e.getClass().getName() + ": " + e.getMessage(), e);
        }

    }

    /**
     * This helper method builds a json prompt object including options params.
     * 
     * See details:
     * https://github.com/ggerganov/llama.cpp/blob/master/examples/server/README.md#api-endpoints
     * 
     * @param prompt
     * @param prompt_options
     * @return
     */
    public JsonObject buildJsonPromptObject(String prompt, String prompt_options) {

        // Create a JsonObjectBuilder instance
        JsonObjectBuilder jsonObjectBuilder = Json.createObjectBuilder();
        jsonObjectBuilder.add("prompt", prompt);

        // Do we have options?
        if (prompt_options != null && !prompt_options.isEmpty()) {
            // Create a JsonReader from the JSON string
            JsonReader jsonReader = Json.createReader(new StringReader(prompt_options));
            JsonObject parsedJsonObject = jsonReader.readObject();
            jsonReader.close();
            // Add each key-value pair from the parsed JsonObject to the new
            // JsonObjectBuilder
            for (Map.Entry<String, JsonValue> entry : parsedJsonObject.entrySet()) {
                jsonObjectBuilder.add(entry.getKey(), entry.getValue());
            }
        }

        // Build the JsonObject
        JsonObject jsonObject = jsonObjectBuilder.build();

        logger.fine("buildJsonPromptObject completed:");
        logger.fine(jsonObject.toString());
        return jsonObject;
    }

    /**
     * This method processes a OpenAI API prompt result in JSON format. The method
     * expects a workitem* including the item 'ai.result' providing the LLM result
     * string.
     * 
     * The parameter 'resultItemName' defines the item to store the result string.
     * This param can be empty.
     * 
     * The parameter 'mode' defines a resolver method.
     * 
     * @param workitem        - the workitem holding the last AI result (stored in a
     *                        value
     *                        list)
     * @param resultItemName  - the item name to store the llm text result
     * @param resultEventType - optional event type send to all CDI Event observers
     *                        for the LLMResultEvent
     * @throws PluginException
     */
    public void processPromptResult(String completionResult, ItemCollection workitem, String resultItemName,
            String resultEventType) throws PluginException {

        // We expect a OpenAI API Json Result object
        // Extract the field 'content'
        // Create a JsonReader from the JSON string
        JsonReader jsonReader = Json.createReader(new StringReader(completionResult));
        JsonObject parsedJsonObject = jsonReader.readObject();
        jsonReader.close();

        // extract content
        String promptResult = parsedJsonObject.getString("content");
        if (promptResult == null) {
            throw new PluginException(OpenAIAPIService.class.getSimpleName(),
                    ERROR_PROMPT_INFERENCE, "Error during POST prompt - no result returned!");
        }
        promptResult = promptResult.trim();
        workitem.appendItemValue(ITEM_AI_RESULT, promptResult);

        if (resultItemName != null && !resultItemName.isEmpty()) {
            workitem.setItemValue(resultItemName, promptResult);
            workitem.setItemValue(ITEM_AI_RESULT_ITEM, resultItemName);
        }

        // fire entityTextEvents so that an adapter can resolve the result
        if (resultEventType != null && !resultEventType.isEmpty()) {
            ImixsAIResultEvent llmResultEvent = new ImixsAIResultEvent(promptResult, resultEventType, workitem);
            llmResultEventObservers.fire(llmResultEvent);
        }

    }

    public void setServiceEndpointUser(Optional<String> serviceEndpointUser) {
        this.serviceEndpointUser = serviceEndpointUser;
    }

    public void setServiceEndpointPassword(Optional<String> serviceEndpointPassword) {
        this.serviceEndpointPassword = serviceEndpointPassword;
    }

}
