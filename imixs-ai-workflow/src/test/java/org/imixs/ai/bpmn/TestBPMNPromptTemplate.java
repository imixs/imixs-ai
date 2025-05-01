package org.imixs.ai.bpmn;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.ItemCollectionComparator;
import org.imixs.workflow.ModelManager;
import org.imixs.workflow.bpmn.BPMNEntityBuilder;
import org.imixs.workflow.bpmn.BPMNLinkedFlowIterator;
import org.imixs.workflow.bpmn.BPMNUtil;
import org.imixs.workflow.exceptions.ModelException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openbpmn.bpmn.BPMNModel;
import org.openbpmn.bpmn.elements.Event;
import org.openbpmn.bpmn.elements.core.BPMNElementNode;
import org.openbpmn.bpmn.exceptions.BPMNModelException;
import org.openbpmn.bpmn.exceptions.BPMNValidationException;
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
    public void testPromptTempalteOutput() {
        StringBuffer buffer = new StringBuffer();
        List<Integer> allTaskIDs = new ArrayList<>();
        try {

            Set<String> groups = openBPMNModelManager.findAllGroups();

            for (String group : groups) {
                buffer.append("Prozess: " + group + "\n\n");

                // find start tasks
                List<ItemCollection> startTasks = openBPMNModelManager.findStartTasks(model, group);
                for (ItemCollection startTask : startTasks) {
                    buffer.append("START\n  |\n");
                    allTaskIDs.add(startTask.getItemValueInteger("taskid"));
                    printTask(startTask, buffer);

                }

                // now print all immediate tasks...
                List<ItemCollection> allTasks = openBPMNModelManager.findTasks(model, group);
                Iterator<ItemCollection> taskIterator = allTasks.iterator();
                while (taskIterator.hasNext()) {
                    ItemCollection task = taskIterator.next();
                    if (task.getItemValueString("txtType").endsWith("archive")) {
                        continue;
                    }
                    int taskID = task.getItemValueInteger("taskid");
                    if (!allTaskIDs.contains(taskID)) {
                        allTaskIDs.add(taskID);
                        printTask(task, buffer);
                    }

                }

                // finally print all END tasks...
                buffer.append(" END\n  |\n");
                for (ItemCollection task : allTasks) {
                    if (!task.getItemValueString("txtType").endsWith("archive")) {
                        continue;
                    }
                    int taskID = task.getItemValueInteger("taskid");
                    if (!allTaskIDs.contains(taskID)) {
                        allTaskIDs.add(taskID);
                        printTask(task, buffer);
                    }
                }

            }

            buffer.append("  *\n");
            System.out.print(buffer.toString());

        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }

    }

    private int printTask(ItemCollection task, StringBuffer buffer) throws BPMNValidationException {
        int taskID = task.getItemValueInteger("taskid");
        buffer.append("  |- [Task: " + taskID + "] "
                + task.getItemValueString("name") + "\n");

        String documentation = task.getItemValueString("documentation");
        if (!documentation.isEmpty()) {
            buffer.append("  |  "
                    + documentation + "\n");
        }

        List<ItemCollection> events = openBPMNModelManager.findEventsByTask(model, taskID);
        Collections.sort(events, new ItemCollectionComparator("eventID", true));
        for (ItemCollection event : events) {
            String id = event.getItemValueString("id");
            Event eventElement = (Event) model.findElementNodeById(id);

            BPMNLinkedFlowIterator<BPMNElementNode> elementNavigator = new BPMNLinkedFlowIterator<BPMNElementNode>(
                    eventElement,
                    node -> ((BPMNUtil.isImixsTaskElement(node))), null);

            if (elementNavigator.hasNext()) {

                BPMNElementNode target = elementNavigator.next();
                ItemCollection targetItemCol = BPMNEntityBuilder.build(target);

                buffer.append("  |   |-- [Event: " + event.getItemValueInteger("eventID") + "] "
                        + event.getItemValueString("name") + " --> [Task: "
                        + targetItemCol.getItemValueString("taskid") + "] "
                        + targetItemCol.getItemValueString("name") + "\n");

            }
        }

        buffer.append("  |\n");

        return taskID;
    }
}
