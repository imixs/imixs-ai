/****************************************************************************
 * Copyright (c) 2025 Dynamixs Software Solutions GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 ****************************************************************************/
package org.imixs.ai.agent;

import org.imixs.workflow.engine.EventLogService;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

/**
 * Helper EJB used exclusively by {@link AIAgentOperator} to guarantee removal
 * of an EventLog entry even when the enclosing transaction has already been
 * marked for rollback (e.g. after a failed processWorkItem() call due to a BPMN
 * model error).
 * <p>
 * This bean intentionally does NOT change the shared EventLogService's default
 * transaction behavior, since that service is used by many other components
 * (e.g. AsyncEventService) that rely on removeEvent() running in the same
 * transaction as EventLogService.lock().
 */
@Stateless
@LocalBean
public class AIAgentEventLogCleanupService {

    @Inject
    EventLogService eventLogService;

    /**
     * Removes an EventLog entry in a guaranteed fresh transaction, independent of
     * the state of any enclosing (possibly rollback-only) transaction.
     *
     * @param id the EventLog entry id
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void removeEventInNewTransaction(String id) {
        eventLogService.removeEvent(id);
    }
}