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
package org.imixs.ai.tools;

/**
 * Interface for a single tool call handler. Each implementation is responsible
 * for exactly one named tool.
 */
public interface ToolCallHandler {

    /**
     * @return the unique tool name this handler is responsible for
     */
    String getToolName();

    /**
     * Executes the tool call. The handler is expected to set the result or error on
     * the given event.
     */
    void handle(ImixsAIToolCallEvent event);
}