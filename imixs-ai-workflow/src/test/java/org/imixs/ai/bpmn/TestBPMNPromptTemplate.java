package org.imixs.ai.bpmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.ModelManager;
import org.imixs.workflow.exceptions.ModelException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openbpmn.bpmn.BPMNModel;
import org.openbpmn.bpmn.exceptions.BPMNModelException;
import org.openbpmn.bpmn.util.BPMNModelFactory;
import org.xml.sax.SAXException;

public class TestBPMNPromptTemplate {

    private static Logger logger = Logger.getLogger(TestBPMNPromptTemplate.class.getName());

    BPMNModel model = null;
    ModelManager openBPMNModelManager = null;

    @BeforeEach
    public void setup() throws ParserConfigurationException, SAXException, IOException {
        openBPMNModelManager = new ModelManager();
        try {
            openBPMNModelManager.addModel(BPMNModelFactory.read("/bpmn/rechnung-ai-test.bpmn"));
            model = openBPMNModelManager.getModel("1.0.0");
            assertNotNull(model);
        } catch (ModelException | BPMNModelException e) {
            fail(e.getMessage());
        }
    }

    /**
     * Test the event configuration
     */
    @Test
    public void testEventSetup() {

        try {
            // find start tasks
            List<ItemCollection> startTasks = openBPMNModelManager.findStartTasks(model, "Rechnungseingang");
            assertNotNull(startTasks);

            logger.info("Start tasks=" + startTasks.size());
            assertEquals(1, startTasks.size());
        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }

    }
}
