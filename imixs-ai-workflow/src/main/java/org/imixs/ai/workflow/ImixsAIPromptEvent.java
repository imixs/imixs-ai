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

import org.imixs.workflow.ItemCollection;

/**
 * The ImixsAIPromptEvent is fired by the
 * {@link org.imixs.ai.workflow.OpenAIAPIService}
 * before a prompt is processed.
 * 
 * The ImixsAIPromptEvent contains the prompt template and the workitem. An
 * observer CDI Bean can update and extend the given prompt.
 * 
 * 
 * @author Ralph Soika
 * @version 1.0
 * @see org.imixs.workflow.engine.WorkflowService
 */
public class ImixsAIPromptEvent {
    private ItemCollection workitem;

    private String promptTemplate;

    public ImixsAIPromptEvent(String promptTemplate, ItemCollection workitem) {
        this.workitem = workitem;
        this.promptTemplate = promptTemplate;
    }

    public ItemCollection getWorkitem() {
        return workitem;
    }

    public void setWorkitem(ItemCollection workitem) {
        this.workitem = workitem;
    }

    public String getPromptTemplate() {
        return promptTemplate;
    }

    public void setPromptTemplate(String promptTemplate) {
        this.promptTemplate = promptTemplate;
    }

}
