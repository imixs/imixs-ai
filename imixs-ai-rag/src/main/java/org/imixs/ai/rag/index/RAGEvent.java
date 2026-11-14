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

package org.imixs.ai.rag.index;

import org.imixs.workflow.ItemCollection;

/**
 * A detached copy of a locked RAG EventLog entry. The record is used to process
 * an event outside of a database transaction.
 *
 * @param id    - the id of the EventLog entry
 * @param topic - the original topic (without the '.lock' suffix)
 * @param ref   - the $uniqueid of the business document
 * @param data  - the event data (category, text, debug flag)
 */
public record RAGEvent(String id, String topic, String ref, ItemCollection data) {
}