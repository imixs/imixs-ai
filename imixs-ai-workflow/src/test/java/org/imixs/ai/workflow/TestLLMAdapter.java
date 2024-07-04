package org.imixs.ai.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Vector;
import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.WorkflowMockEnvironment;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Test class to test the LLMAdapter configuration
 * 
 * 
 * @author rsoika
 */
public class TestLLMAdapter {

    private static Logger logger = Logger.getLogger(TestLLMAdapter.class.getName());

    protected ItemCollection workitem;
    protected ItemCollection event;
    protected ItemCollection documentProcess;
    protected WorkflowMockEnvironment workflowMockEnvironment;
    protected OpenAIAPIAdapter adapter;

    /**
     * The setup method loads t
     * 
     * @throws AdapterException
     * 
     */
    @Before
    public void setUp() throws PluginException, ModelException, AdapterException {

        workflowMockEnvironment = new WorkflowMockEnvironment();
        workflowMockEnvironment.setModelPath("/bpmn/llm-example-1.0.0.bpmn");
        workflowMockEnvironment.setup();

        adapter = new OpenAIAPIAdapter();
        adapter.setWorkflowService(workflowMockEnvironment.getWorkflowService());

        // prepare data
        workitem = new ItemCollection().model(WorkflowMockEnvironment.DEFAULT_MODEL_VERSION).task(100);
        logger.info("[TestAccessAdapterProcessEntity] setup test data...");
        Vector<String> list = new Vector<String>();
        list.add("manfred");
        list.add("anna");
        workitem.replaceItemValue("namTeam", list);
        workitem.replaceItemValue("namCreator", "ronny");

    }

    /**
     * Test the event configuration
     */
    @Test
    @Ignore // integration test only!
    public void testEventSetup() {

        try {
            event = workflowMockEnvironment.getModel().getEvent(100, 10);
            Assert.assertNotNull(event);
            workitem.setEventID(10);
            try {
                adapter.execute(workitem, event);
            } catch (NullPointerException | AdapterException e) {
                e.printStackTrace();
                Assert.fail();
            }

        } catch (ModelException e) {
            e.printStackTrace();
            Assert.fail();
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
