package org.imixs.ai.workflow;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.WorkflowMockEnvironment;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbpmn.bpmn.BPMNModel;

/**
 * Test class to test the LLMAdapter configuration
 * 
 * 
 * @author rsoika
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.WARN)
public class TestLLMAdapter {

    private static Logger logger = Logger.getLogger(TestLLMAdapter.class.getName());

    @InjectMocks
    protected OpenAIAPIAdapter adapter;

    WorkflowMockEnvironment workflowEnvironment;
    ItemCollection workitem;

    /**
     * The setup method loads t
     * 
     * @throws AdapterException
     * 
     */
    @BeforeEach
    public void setUp() throws PluginException, ModelException, AdapterException {
        // Ensures that @Mock and @InjectMocks annotations are processed
        MockitoAnnotations.openMocks(this);

        workflowEnvironment = new WorkflowMockEnvironment();

        workflowEnvironment.setUp();
        workflowEnvironment.loadBPMNModel("/bpmn/llm-example-1.0.0.bpmn");

    }

    /**
     * Test the event configuration
     */
    @Test
    public void testEventSetup() {

        try {

            BPMNModel model = workflowEnvironment.getModelService().getModelManager().getModel("1.0.0");
            ItemCollection event = workflowEnvironment.getModelService().getModelManager().findEventByID(model, 100,
                    10);
            workitem.setEventID(10);
            assertNotNull(event);
            try {
                adapter.execute(workitem, event);
            } catch (NullPointerException | AdapterException | PluginException e) {
                e.printStackTrace();
                fail();
            }

        } catch (ModelException e) {
            e.printStackTrace();
            fail();
        }

    }

}
