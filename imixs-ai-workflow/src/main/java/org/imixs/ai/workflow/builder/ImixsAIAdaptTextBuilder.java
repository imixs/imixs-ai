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

package org.imixs.ai.workflow.builder;

import java.util.logging.Logger;

import org.imixs.ai.workflow.ImixsAIPromptEvent;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * The ImixsAIAdaptTextBuilder adapts text item values into the
 * prompt template.
 * 
 * The template must provide corresponding Text Adapter Tags e.g.
 * 
 * <itemvalue>$workflowgroup</itemvalue>
 * 
 * The supported text adapters are depending on the installation of the
 * Imixs-Worklfow instance.
 * 
 * @see https://www.imixs.org/doc/engine/adapttext.html
 * @author rsoika
 *
 */
public class ImixsAIAdaptTextBuilder {

    private static Logger logger = Logger.getLogger(ImixsAIFileContextBuilder.class.getName());

    @Inject
    private WorkflowService workflowService;

    public void onEvent(@Observes ImixsAIPromptEvent event) {
        if (event.getWorkitem() == null) {
            return;
        }

        String prompt = event.getPromptTemplate();
        // Adapt text!
        try {
            prompt = workflowService.adaptText(prompt, event.getWorkitem());
        } catch (PluginException e) {
            logger.warning("Failed to adapt text to current prompt-template: " + e.getMessage());
        }

        // update the prompt tempalte
        event.setPromptTemplate(prompt);

    }

}
