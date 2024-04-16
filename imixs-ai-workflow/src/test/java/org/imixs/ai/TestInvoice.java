package org.imixs.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.Logger;

import org.imixs.ai.json.PromptBuilder;
import org.junit.Before;
import org.junit.Test;

/**
 * Test class to test more complex prompts
 * 
 * 
 * @author rsoika
 */
public class TestInvoice {

    private static Logger logger = Logger.getLogger(TestRestAPI.class.getName());

    static String AI_SERVICE_API = "http://llama-cpp.foo.com:8080/";

    /**
     * The setup method loads t
     * 
     */
    @Before
    public void setup() {
        logger.info("setup...");

    }

    /**
     * Test usage of Imixs-AI PromptBuilder
     */
    @Test
    public void testPromptBuilder() {

        try {

            // Pfad relativ zum Klassenpfad, "testdatei.txt" befindet sich direkt unter
            // "src/test/resources"
            String path = this.getClass().getClassLoader().getResource("kraxi-invoice.prompt").getPath();

            String prompt = readFileAsString(path);

            logger.info("Prompt=" + prompt);
            PromptBuilder promptBuilder = new PromptBuilder();

            String jsonString = promptBuilder
                    .setPredict(3129)
                    .setPrompt(prompt)
                    .buildString();

            logger.info("result=" + jsonString);

            sendJSONPrompt(jsonString);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Helper method sending a JSON prompt
     * 
     * @param jsonInputString
     */
    public void sendJSONPrompt(String jsonInputString) {
        try {
            URL url = new URL(AI_SERVICE_API + "completion");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            // Set the appropriate HTTP method
            conn.setRequestMethod("POST");
            // Indicate that we will send (output) and receive (input) data
            conn.setDoOutput(true);
            conn.setDoInput(true);

            // Set the request content-type header parameter
            conn.setRequestProperty("Content-Type", "application/json");

            // Create an output stream and write the body data
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonInputString.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            // Reading the response
            int responseCode = conn.getResponseCode();
            System.out.println("POST Response Code :: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine = null;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    System.out.println("Response Body :: " + response.toString());
                }
            } else {
                System.out.println("POST request not worked.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Helper method to read a text file into a String
     * 
     * @param filePath
     * @return
     * @throws IOException
     */
    public static String readFileAsString(String filePath) throws IOException {

        return new String(Files.readAllBytes(Paths.get(filePath)));
    }
}
