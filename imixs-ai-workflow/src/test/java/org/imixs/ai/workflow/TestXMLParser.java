package org.imixs.ai.workflow;

import java.util.logging.Logger;

import org.imixs.ai.xml.ImixsAIResultXMLAdapter;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.WorkflowMockEnvironment;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test class to test the LLMAdapter configuration
 * 
 * 
 * @author rsoika
 */
public class TestXMLParser {

    private static Logger logger = Logger.getLogger(TestXMLParser.class.getName());

    protected ItemCollection workitem;
    protected ItemCollection event;
    protected ItemCollection documentProcess;
    protected WorkflowMockEnvironment workflowMockEnvironment;
    protected OpenAIAPIAdapter adapter;

    /**
     * Test the event configuration
     */
    @Test
    public void testSimple() {

        String value = "1090,00";

        String result = ImixsAIResultXMLAdapter.cleanDoubleFormatting(value);
        System.out.println("Result=" + value);

        try {
            double d = Double.parseDouble(result);
            System.out.println("Double=" + d);
        } catch (NumberFormatException e) {
            Assert.fail(e.getMessage());
        }

    }
}
