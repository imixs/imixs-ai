/*  
 *  Imixs-Workflow 
 *  
 *  Copyright (C) 2001-2020 Imixs Software Solutions GmbH,  
 *  http://www.imixs.com
 *  
 *  This program is free software; you can redistribute it and/or 
 *  modify it under the terms of the GNU General Public License 
 *  as published by the Free Software Foundation; either version 2 
 *  of the License, or (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful, 
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of 
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU 
 *  General Public License for more details.
 *  
 *  You can receive a copy of the GNU General Public
 *  License at http://www.gnu.org/licenses/gpl.html
 *  
 *  Project: 
 *      https://www.imixs.org
 *      https://github.com/imixs/imixs-workflow
 *  
 *  Contributors:  
 *      Imixs Software Solutions GmbH - Project Management
 *      Ralph Soika - Software Developer
 */

package org.imixs.ai.workflow;

import org.imixs.workflow.ItemCollection;

/**
 * The LLMPromptEvent is fired by the {@link org.imixs.ai.workflow.LLMService}
 * before a prompt is processed.
 * 
 * The LLMPromptEvent contains the prompt template and the workitem
 * 
 * 
 * @author Ralph Soika
 * @version 1.0
 * @see org.imixs.workflow.engine.WorkflowService
 */
public class LLMPromptEvent {
    private ItemCollection workitem;

    private String promptTemplate;

    public LLMPromptEvent(String promptTemplate, ItemCollection workitem) {
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
