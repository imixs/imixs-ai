package org.imixs.ai;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Logger;

import org.junit.Before;
import org.junit.Test;

/**
 * TestMLIntegrationTest is used to test againsed the python imixs-ml service
 * module with test data based on ItemCollection objects.
 * 
 * 
 * @author rsoika
 */
public class TestMLIntegrationTest {

    private static Logger logger = Logger.getLogger(TestMLIntegrationTest.class.getName());

    static String AI_SERVICE_API = "http://llama-cpp.foo.com:8080/";

    /**
     * The setup method
     * 
     */
    @Before
    public void setup() {
        logger.info("setup...");

    }

    @Test
    public void hello() {
        logger.info("Hello...");
    }

    /**
     * 
     */
    @Test
    public void testSimple() {
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

            // Create the data you want to send
            String jsonInputString = "{\"prompt\": \"Building a website can be done in 10 simple steps:\",\"n_predict\": 128}";

            // Create an output stream and write the body data
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonInputString.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            // Reading the response
            int responseCode = conn.getResponseCode();
            System.out.println("POST Response Code :: " + responseCode);
  
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try(BufferedReader br = new BufferedReader(  
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





    @Test
    public void testSimple2() {
        try {
          
            // Create the data you want to send
            sendJSONPrompt( "{\"prompt\": \"Building a website can be done in 3 simple steps:\",\"n_predict\": 128}");

          
        } catch (Exception e) {
            e.printStackTrace();
        }
    }






/**
 * Helper method sending a JSON prompt 
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
                try(BufferedReader br = new BufferedReader(  
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
