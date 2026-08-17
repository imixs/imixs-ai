/****************************************************************************
 * Copyright (c) 2022-2025 Imixs Software Solutions GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
 ****************************************************************************/
package org.imixs.ai.bpmn.handler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.imixs.ai.bpmn.util.AIModelManager;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.MockWorkflowEnvironment;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.openbpmn.bpmn.BPMNModel;

/**
 * Integration test for BPMNFormPromptHandler.
 *
 * This test loads a real BPMN model and resolves the <bpmn.form /> tag
 * against task 5000, which carries the invoice imixs-form Data Object
 * association. The purpose of this test is mainly to visually inspect the
 * generated output (see console output).
 */
public class TestBPMNFormPromptHandler {

    private static final String MODEL_VERSION = "rechnungseingang-de-1.2";
    private static final int TASK_ID = 5000;

    private static Logger logger = Logger.getLogger(TestBPMNFormPromptHandler.class.getName());

    BPMNModel model = null;
    protected ItemCollection workitem;
    protected MockWorkflowEnvironment workflowEnvironment;
    protected BPMNFormPromptHandler handler;

    @BeforeEach
    public void setup() throws PluginException, ModelException {
        Logger.getLogger("org.imixs.workflow.*").setLevel(Level.FINEST);

        // Setup Environment
        MockitoAnnotations.openMocks(this);
        workflowEnvironment = new MockWorkflowEnvironment();
        workflowEnvironment.setUp();
        // Load Model
        workflowEnvironment.loadBPMNModelFromFile("/bpmn/rechnungseingang-de-1.2.41.bpmn");
        model = workflowEnvironment.fetchModel(MODEL_VERSION);

        // Wire the handler with a SharedModelManager backed by the same
        // ModelManager instance used by the MockWorkflowEnvironment, so the
        // handler resolves the Data Object against the loaded test model.
        handler = new BPMNFormPromptHandler();
        handler.setSharedModelManager(new AIModelManager(workflowEnvironment.getModelManager()));
        workitem = new ItemCollection();
        workitem.model(MODEL_VERSION).task(TASK_ID);
    }

    /**
     * Loads the form definition for task 5000 and prints the raw
     * <imixs-form> XML, as well as the generated field mapping block.
     */
    @Test
    public void testFetchFormDefinitionByWorkitem() {
        try {
            String formDefinition = handler.fetchFormDefinitionByWorkitem(workitem);

            System.out.println("=== Raw <imixs-form> definition ===");
            System.out.println(formDefinition);

            assertNotNull(formDefinition);
            assertFalse(formDefinition.isBlank());

            String fieldMapping = handler.buildFormPromptBlock(formDefinition, null, true, "invoice");

            System.out.println("=== Generated field mapping ===");
            System.out.println(fieldMapping);

        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }
    }

    /**
     * End-to-end test: resolves a <bpmn.form /> tag inside a full prompt
     * template against the real model and prints the resulting prompt.
     */
    // @Test
    // public void testOnEvent_resolvesFormTagInPrompt() {
    // try {
    // String promptTemplate = """
    // You are a clerk at the logistic company 'Alexander Global Logistics'.
    // Transfer the invoice data into an XML object with the following fields:
    // <bpmn.form />
    // Output only the XML object!
    // """;

    // ImixsAIPromptEvent event = new ImixsAIPromptEvent(workitem, promptTemplate);
    // handler.onEvent(event);

    // String result = event.getPromptTemplate();

    // System.out.println("=== Resolved prompt template ===");
    // System.out.println(result);

    // assertNotNull(result);
    // assertFalse(result.contains("<bpmn.form"));

    // } catch (Exception e) {
    // e.printStackTrace();
    // fail();
    // }
    // }
}