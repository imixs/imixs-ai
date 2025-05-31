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

import static org.junit.jupiter.api.Assertions.fail;

import java.util.logging.Logger;

import org.imixs.ai.xml.ImixsAIResultXMLAdapter;
import org.imixs.workflow.ItemCollection;
import org.junit.jupiter.api.Test;

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
            fail(e.getMessage());
        }

    }
}
