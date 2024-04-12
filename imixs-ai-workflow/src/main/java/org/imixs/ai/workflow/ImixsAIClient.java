package org.imixs.ai.workflow;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ImixsAIClient {

    private String apiEndpoint;

    public ImixsAIClient(String apiEndpoint) {
        if (!apiEndpoint.endsWith("/")) {
            apiEndpoint = apiEndpoint + "/";
        }
        this.apiEndpoint = apiEndpoint;
    }

    /**
     * Helper method sending a JSON prompt
     * 
     * @param xmlPromptData
     */
    public void sendPrompt(String xmlPromptData) {
        try {
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

}
