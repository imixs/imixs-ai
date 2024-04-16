package org.imixs.ai.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.Logger;

import org.junit.Before;
import org.junit.Test;

/**
 * Test class to test more complex prompts
 * 
 * 
 * @author rsoika
 */
public class TestInvoice {

    private static Logger logger = Logger.getLogger(TestInvoice.class.getName());

    static String AI_SERVICE_API = "http://llama-cpp.foo.com:8000/";

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

            String path = this.getClass().getClassLoader().getResource("kraxi-invoice2.prompt").getPath();
            // String path =
            // this.getClass().getClassLoader().getResource("demo-01.prompt").getPath();

            String prompt = readFileAsString(path);

            logger.info("Prompt=" + prompt);

            PromptData promptData = new PromptData()
                    // .setModel("mistral-7b-instruct-v0.2.Q3_K_M.gguf")
                    .setModel("mistral-7b-instruct-v0.2.Q4_K_M.gguf")
                    .setPrompt(prompt);

            String xmlPromptData = promptData.build();

            logger.info("result=" + xmlPromptData);

            ImixsAIClient imixsAIClient = new ImixsAIClient(AI_SERVICE_API);
            imixsAIClient.sendPrompt(xmlPromptData);

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
