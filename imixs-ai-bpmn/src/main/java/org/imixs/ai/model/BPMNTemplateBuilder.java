/****************************************************************************
 * Copyright (c) 2022-2025 Imixs Software Solutions GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * This Source Code may also be made available under the terms of the
 * GNU General Public License, version 2 or later (GPL-2.0-or-later),
 * which is available at https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
 ****************************************************************************/

package org.imixs.ai.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.ItemCollectionComparator;
import org.imixs.workflow.ModelManager;
import org.imixs.workflow.bpmn.BPMNEntityBuilder;
import org.imixs.workflow.bpmn.BPMNLinkedFlowIterator;
import org.imixs.workflow.bpmn.BPMNUtil;
import org.imixs.workflow.exceptions.ModelException;
import org.openbpmn.bpmn.BPMNModel;
import org.openbpmn.bpmn.elements.Event;
import org.openbpmn.bpmn.elements.core.BPMNElementNode;
import org.openbpmn.bpmn.exceptions.BPMNValidationException;

/**
 * This builder class convert a BPMN Model into Markup Language useable for LLMs
 */
public class BPMNTemplateBuilder {

    private static Logger logger = Logger.getLogger(BPMNTemplateBuilder.class.getName());

    /**
     * Test the output of the Model in Markup Language
     * 
     * @throws ModelException
     */

    public static String buildPromptTemplate(BPMNModel model, ModelManager modelManager)
            throws ModelException, BPMNValidationException {
        StringBuffer buffer = new StringBuffer();
        List<Integer> allTaskIDs = new ArrayList<>();

        Set<String> groups = modelManager.findAllGroupsByModel(model);

        for (String group : groups) {
            buffer.append("Business Process: " + group + "\n\n");

            // find start tasks
            List<ItemCollection> startTasks = modelManager.findStartTasks(model, group);
            for (ItemCollection startTask : startTasks) {
                buffer.append("START\n  |\n");
                allTaskIDs.add(startTask.getItemValueInteger("taskid"));
                printTask(model, startTask, buffer, modelManager);

            }

            // now print all immediate tasks...
            List<ItemCollection> allTasks = modelManager.findTasks(model, group);
            Iterator<ItemCollection> taskIterator = allTasks.iterator();
            while (taskIterator.hasNext()) {
                ItemCollection task = taskIterator.next();
                if (task.getItemValueString("txtType").endsWith("archive")) {
                    continue;
                }
                int taskID = task.getItemValueInteger("taskid");
                if (!allTaskIDs.contains(taskID)) {
                    allTaskIDs.add(taskID);
                    printTask(model, task, buffer, modelManager);
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
                    printTask(model, task, buffer, modelManager);
                }
            }
        }

        buffer.append("  \n");
        System.out.print(buffer.toString());

        return buffer.toString();

    }

    /**
     * Helper method to print a task node
     * 
     * @param task
     * @param buffer
     * @param modelManager
     * @return
     * @throws BPMNValidationException
     */
    private static int printTask(BPMNModel model, ItemCollection task, StringBuffer buffer, ModelManager modelManager)
            throws BPMNValidationException {
        int taskID = task.getItemValueInteger("taskid");
        buffer.append("  |- [Task: " + taskID + "] "
                + task.getItemValueString("name") + "\n");

        String documentation = task.getItemValueString("documentation");
        if (!documentation.isEmpty() && !documentation.startsWith("<")) {
            buffer.append("  |  "
                    + documentation + "\n");
        }

        List<ItemCollection> events = modelManager.findEventsByTask(model, taskID);
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
