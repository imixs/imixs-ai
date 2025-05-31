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
 * The ImixsAIResultEvent is fired by the
 * {@link org.imixs.ai.workflow.OpenAIAPIService}
 * after a prompt was processed.
 * A CDI bean can observe this event to adapt the AI result stored in the item
 * 'ai.result'.
 * 
 * For example an Observer bean can transfer an xml result tree
 * form a completion request into items of the current workitem.
 * 
 * 
 * @author Ralph Soika
 * @version 1.0
 * @see org.imixs.ai.workflow.OpenAIAPIService
 * @see org.imixs.workflow.engine.WorkflowService
 */
public class ImixsAIResultEvent {
    private ItemCollection workitem;
    private String eventType;
    private String promptResult;

    public ImixsAIResultEvent(String promptResult, String eventType, ItemCollection workitem) {
        this.workitem = workitem;
        this.eventType = eventType;
        this.promptResult = promptResult;
    }

    public ItemCollection getWorkitem() {
        return workitem;
    }

    public void setWorkitem(ItemCollection workitem) {
        this.workitem = workitem;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPromptResult() {
        return promptResult;
    }

    public void setPromptResult(String promptResult) {
        this.promptResult = promptResult;
    }

}
