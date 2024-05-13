package org.imixs.ai.workflow;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.logging.Logger;

import org.imixs.ai.xml.LLMXMLParser;
import org.imixs.workflow.FileData;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.ModelService;
import org.imixs.workflow.engine.WorkflowService;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * The LLMService reacts on Processing events and updates build a prompt by a
 * given prompt-template.
 * The service can call a Imixs-AI LLM endpoint.
 * 
 * @author rsoika
 *
 */
@Stateless
@LocalBean
public class LLMService implements Serializable {

    private static final long serialVersionUID = 1L;
    private static Logger logger = Logger.getLogger(LLMService.class.getName());

    public static final String ITEM_AI_RESULT = "ai.result";
    public static final String ITEM_AI_RESULT_ITEM = "ai.result.item";
    public static final String ITEM_SUGGEST_ITEMS = "ai.suggest.items";
    public static final String ITEM_SUGGEST_MODE = "ai.suggest.mode";

    public static final String LLM_SERVICE_ENDPOINT = "llm.service.endpoint";
    public static final String LLM_MODEL = "llm.model";

    @Inject
    protected ModelService modelService;

    @Inject
    protected WorkflowService workflowService;

    @Inject
    private Event<LLMPromptEvent> llmPromptEventObservers = null;

    @Inject
    private Event<LLMResultEvent> llmResultEventObservers = null;

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
     * This method fires a prompt event to all registered PrompEvent Observer
     * classes. This allows adaptors to customize the prompt.
     * 
     * @param workitem
     * @param resultItemName
     * @param resultEventType
     */
    public String buildPrompt(String promptTemplate, ItemCollection workitem) {

        LLMPromptEvent llmPromptEvent = new LLMPromptEvent(promptTemplate, workitem);
        llmPromptEventObservers.fire(llmPromptEvent);
        logger.finest(llmPromptEvent.getPromptTemplate());

        return llmPromptEvent.getPromptTemplate();
    }

    /**
     * This method posts a XML prompt object to a Imixs-AI llm service
     * 
     * The method returns the response body.
     * 
     * @param xmlPromptData
     */
    public String postPrompt(String apiEndpoint, String xmlPromptData) {
        try {

            logger.fine("POST...");
            logger.fine(xmlPromptData);
            if (!apiEndpoint.endsWith("/")) {
                apiEndpoint = apiEndpoint + "/";
            }
            URL url = new URL(apiEndpoint + "prompt");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            // Set the appropriate HTTP method
            conn.setRequestMethod("POST");
            // Indicate that we will send (output) and receive (input) data
            conn.setDoOutput(true);
            conn.setDoInput(true);

            // Set the request content-type header parameter
            conn.setRequestProperty("Content-Type", "application/xml");

            // Create an output stream and write the body data
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = xmlPromptData.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            // Reading the response
            int responseCode = conn.getResponseCode();
            System.out.println("POST Response Code :: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                    StringBuilder responseBody = new StringBuilder();
                    String responseLine = null;
                    while ((responseLine = br.readLine()) != null) {
                        responseBody.append(responseLine.trim() + "\n");
                    }

                    String response = responseBody.toString();
                    System.out.println("Response Body :: " + response);

                    return response;
                }
            } else {
                System.out.println("POST request not worked.");
                return "";
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return "";
    }

    /**
     * This method processes a prompt result. The method expects a workitem
     * including the item 'ai.result' providing the LLM result string.
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
     */
    public void processPromptResult(ItemCollection workitem, String resultItemName,
            String resultEventType) {

        List<String> aiResultList = workitem.getItemValueList(ITEM_AI_RESULT, String.class);
        String lastAIResult = aiResultList.get(aiResultList.size() - 1);
        // xml resolve
        String promptResult = LLMXMLParser.parseResultTag(lastAIResult);

        if (resultItemName != null && !resultItemName.isEmpty()) {
            workitem.setItemValue(resultItemName, promptResult);
            workitem.setItemValue(ITEM_AI_RESULT_ITEM, resultItemName);
        }

        // fire entityTextEvents so that an adapter can resolve the result
        if (resultEventType != null && !resultEventType.isEmpty()) {
            LLMResultEvent llmResultEvent = new LLMResultEvent(promptResult, resultEventType, workitem);
            llmResultEventObservers.fire(llmResultEvent);
        }

    }

}
