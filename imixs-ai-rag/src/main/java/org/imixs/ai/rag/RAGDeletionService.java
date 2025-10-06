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

package org.imixs.ai.rag;

import org.imixs.ai.rag.events.RAGEventService;
import org.imixs.workflow.engine.DocumentEvent;
import org.imixs.workflow.engine.EventLogService;
import org.imixs.workflow.exceptions.AccessDeniedException;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RunAs;
import jakarta.ejb.Stateless;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * The RAGDeletionService reacts on Document Delete events an automatically
 * removes an existing index. The deletion is handled by the RAGEventService
 * 
 * @see RAGEventService
 * @see DocumentEvent
 * @version 1.0
 * @author rsoika
 *
 */
@DeclareRoles({ "org.imixs.ACCESSLEVEL.MANAGERACCESS" })
@RunAs("org.imixs.ACCESSLEVEL.MANAGERACCESS")
@Stateless
public class RAGDeletionService {

    @Inject
    EventLogService eventLogService;

    /**
     * DocumentEvent listener to delete an existing index. The method sends a RAG
     * delete event. The deletion is handled by the RAGEventService
     */
    public void onDocumentEvent(@Observes DocumentEvent documentEvent) throws AccessDeniedException {

        // do not run on snapshots
        if (documentEvent.getDocument().getType().startsWith("snapshot-")) {
            // no op!
            return;
        }

        if (DocumentEvent.ON_DOCUMENT_DELETE == documentEvent.getEventType()) {
            // do not run on Snapshots!
            eventLogService.createEvent(RAGEventService.EVENTLOG_TOPIC_RAG_EVENT_DELETE,
                    documentEvent.getDocument().getUniqueID());
        }
    }
}
