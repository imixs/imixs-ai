/****************************************************************************
 * Copyright (c) 2022-2025 Imixs Software Solutions GmbH and others.
 * https://www.imixs.com
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

package org.imixs.ai.tools;

/**
 * The ToolCallEvent provides a CDI event fired by the OpenAIAPIService EJB.
 * This even can be used in a observer pattern of a service EJB to react on the
 * life-cycle of a LLM tool call.
 * <p>
 * The ToolCallEvent defines the following event types:
 * <ul>
 * <li>BEFORE_TOOLCALL - is send immediately before a the tool call will be
 * processed
 * <li>AFTER_TOOLCALL - is send immediately after a tool call was processed
 * </ul>
 * 
 * 
 * @author Ralph Soika
 * @version 1.0
 * @see org.imixs.ai.api.OpenAIAPIService
 */
public class ToolCallEvent {

    public static final int BEFORE_TOOLCALL = 1;
    public static final int AFTER_TOOLCALL = 2;

    private int eventType;
    private ToolCallFunction _function;

    public ToolCallEvent(ToolCallFunction _function, int eventType) {
        this.eventType = eventType;
        this._function = _function;
    }

    public int getEventType() {
        return eventType;
    }

    public ToolCallFunction getFunction() {
        return _function;
    }

}
