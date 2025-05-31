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

package org.imixs.ai.workflow;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.WorkflowMockEnvironment;
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

    protected ItemCollection workitem;
    protected ItemCollection event;
    protected WorkflowMockEnvironment workflowEnvironment;
    BPMNModel model = null;

    @InjectMocks
    protected OpenAIAPIAdapter adapter;

    @InjectMocks
    protected OpenAIAPIService llmService;

    @BeforeEach
    public void setUp() throws PluginException, ModelException {
        // Ensures that @Mock and @InjectMocks annotations are processed
        MockitoAnnotations.openMocks(this);
        workflowEnvironment = new WorkflowMockEnvironment();

        // register AccessAdapter Mock
        workflowEnvironment.registerAdapter(adapter);

        // Setup Environment
        workflowEnvironment.setUp();
        workflowEnvironment.loadBPMNModel("/bpmn/llm-example-1.0.0.bpmn");
        model = workflowEnvironment.getModelService().getModelManager().getModel("1.0.0");
        adapter.setWorkflowService(workflowEnvironment.getWorkflowService());

        // prepare data
        workitem = new ItemCollection().model("1.0.0").task(100);

        event = new ItemCollection();

    }

    /**
     * Test the event configuration
     */
    @Test
    public void testEventSetup() {
        try {
            event = workflowEnvironment.getModelService().getModelManager().findEventByID(model, 100, 10);
            assertNotNull(event);
            workitem.setEventID(10);
            // currently we ignore this test beause we can not yet mock the OpenAIAPIService
            // at this point

            adapter.execute(workitem, event);
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }
}
