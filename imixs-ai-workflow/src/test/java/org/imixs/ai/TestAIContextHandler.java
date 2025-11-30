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

package org.imixs.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test class to test the AIContextHandler
 * 
 * 
 * @author rsoika
 */
public class TestAIContextHandler {

    private static Logger logger = Logger.getLogger(TestAIContextHandler.class.getName());

    protected ImixsAIContextHandler imixsAIContextHandler;
    protected ItemCollection workitem;

    @BeforeEach
    public void setUp() throws PluginException, ModelException {
        imixsAIContextHandler = new ImixsAIContextHandler();
    }

    /**
     * Test parsing a PromptDefinition with two prompt messages
     */
    @Test
    public void testPromptDefinition() {

        String promptDef = "<imixs-ai name=\"CONDITION\">\n" + //
                "  <debug>true</debug>\n" + //
                "  <endpoint><propertyvalue>llm.service.endpoint</propertyvalue></endpoint>\n" + //
                "  <result-event>BOOLEAN</result-event>\n" + //
                "  <PromptDefinition>\n" + //
                "    <prompt_options>{\"n_predict\": 16, \"temperature\": 0 }</prompt_options>\n" + //
                "    <prompt role=\"system\"><![CDATA[\n" + //
                "       You are a sales expert. You evaluate the following condition to 'true' or 'false'. ]]>\n" + //
                "    </prompt>\n" + //
                "    <prompt role=\"user\"><![CDATA[\n" + //
                "       Is Germany an EU member country? ]]>\n" + //
                "    </prompt>\n" + //
                "  </PromptDefinition>\n" + //
                "</imixs-ai>";

        try {

            imixsAIContextHandler.addPromptDefinition(promptDef);

            // we expect two prompt messages
            List<ItemCollection> context = imixsAIContextHandler.getContext();
            assertNotNull(context);
            assertEquals(2, context.size());

            // test system
            ItemCollection promptSystem = imixsAIContextHandler.getContext().get(0);
            assertNotNull(promptSystem);
            assertEquals("system", promptSystem.getItemValueString("chat.role").toLowerCase());
            String message = promptSystem.getItemValueString("chat.message");
            assertTrue(message.contains("sales expert"));

            // test user
            ItemCollection promptUser = imixsAIContextHandler.getContext().get(1);
            assertNotNull(promptUser);
            assertEquals("user", promptUser.getItemValueString("chat.role").toLowerCase());
            message = promptUser.getItemValueString("chat.message");
            assertTrue(message.contains("EU member"));

        } catch (PluginException | AdapterException e) {
            fail(e);
        }

    }
}
