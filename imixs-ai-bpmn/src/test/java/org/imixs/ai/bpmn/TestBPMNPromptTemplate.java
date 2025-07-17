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

package org.imixs.ai.bpmn;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.imixs.ai.model.BPMNTemplateBuilder;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.MockWorkflowEnvironment;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.openbpmn.bpmn.BPMNModel;

/**
 * This test class demonstrates how to convert a BPMN Model into Markup Language
 * useable for LLMs
 */
public class TestBPMNPromptTemplate {

    private static Logger logger = Logger.getLogger(TestBPMNPromptTemplate.class.getName());

    BPMNModel model = null;
    protected ItemCollection workitem;
    protected ItemCollection event;
    protected MockWorkflowEnvironment workflowEnvironment;

    @BeforeEach
    public void setup() throws PluginException, ModelException {
        Logger.getLogger("org.imixs.workflow.*").setLevel(Level.FINEST);

        // Setup Environment
        MockitoAnnotations.openMocks(this);
        workflowEnvironment = new MockWorkflowEnvironment();
        workflowEnvironment.setUp();
        // Load Models
        workflowEnvironment.loadBPMNModelFromFile("/bpmn/rechnungseingang-de-1.2.41.bpmn");
    }

    /**
     * Test the output of the Model in Markup Language
     */
    @Test
    public void testPromptTemplateOutput() {
        StringBuffer buffer = new StringBuffer();
        List<Integer> allTaskIDs = new ArrayList<>();
        try {
            model = workflowEnvironment.fetchModel("rechnungseingang-de-1.2");

            String result = BPMNTemplateBuilder.buildPromptTemplate(model, workflowEnvironment.getModelManager());

            System.out.print(buffer.toString());

        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }

    }

}
