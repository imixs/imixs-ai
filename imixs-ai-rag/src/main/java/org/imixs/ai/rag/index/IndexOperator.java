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

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.imixs.ai.rag.cluster.ClusterException;
import org.imixs.ai.rag.cluster.ClusterService;
import org.imixs.ai.workflow.ImixsAIPromptService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.AsyncEventScheduler;
import org.imixs.workflow.engine.EventLogService;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.engine.jpa.EventLog;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RunAs;
import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;

/**
 * The IndexOperator is responsible to process Imixs-AI RAG events in an
 * asynchronous batch process. The IndexOperator automatically lookup eventLog
 * entries of the topic "rag.event.*". The IndexOperator is called only by the
 * IndexScheduler.
 * <p>
 * The IndexOperator reacts on events from the following types:
 * <ul>
 * <li>rag.event.index - index a new workitem</li>
 * <li>rag.event.update - update the meta data only</li>
 * <li>rag.event.prompt - call a llm completion process</li>
 * <li>rag.event.delete - delete an entry</li>
 * </ul>
 * <p>
 * To prevent concurrent processes to handle the same workitems the batch
 * process uses a Optimistic lock strategy. After fetching new event log entries
 * the processor updates the eventLog entry in a new transaction and set the
 * topic to 'batch.process.lock'. After that update we can be sure that no other
 * process is dealing with these entries. After completing the processing step
 * the eventlog entry will be removed.
 * <p>
 * To avoid a deadlock the processor set an expiration time on the lock, so the
 * lock will be auto-released after 1 minute (batch.processor.deadlock).
 * 
 * 
 * 
 * @see AsyncEventScheduler
 * @version 1.0
 * @author rsoika
 *
 */
@DeclareRoles({ "org.imixs.ACCESSLEVEL.MANAGERACCESS" })
@RunAs("org.imixs.ACCESSLEVEL.MANAGERACCESS")
@Stateless
@LocalBean
public class IndexOperator {

    public static final String EVENTLOG_TOPIC_RAG_EVENT_INDEX = "rag.event.index";
    public static final String EVENTLOG_TOPIC_RAG_EVENT_UPDATE = "rag.event.update";
    public static final String EVENTLOG_TOPIC_RAG_EVENT_PROMPT = "rag.event.prompt";
    public static final String EVENTLOG_TOPIC_RAG_EVENT_DELETE = "rag.event.delete";

    private static final Logger logger = Logger.getLogger(IndexOperator.class.getName());

    @Inject
    EventLogService eventLogService;

    @Inject
    private WorkflowService workflowService;

    @Inject
    private ClusterService clusterService;

    @Inject
    private IndexService indexService;

    @Inject
    protected ImixsAIPromptService imixsAIPromptService;

    /**
     * The method lookups for RAG event log entries and updates the RAG index
     * information. The method reacts on the following event type:
     * <ul>
     * <li>'rag.event.index'</li>
     * <li>'rag.event.update'</li>
     * <li>'rag.event.prompt'</li>
     * <li>'rag.event.delete'</li>
     * </ul>
     * <p>
     * Each eventLogEntry provides optional a prompt template.
     * <p>
     * The event 'rag.event.prompt' first calls the llm service and than creats an
     * index event
     * 
     */
    @TransactionAttribute(value = TransactionAttributeType.REQUIRES_NEW)
    public void processEventLog() {
        long l = System.currentTimeMillis();

        // test for new event log entries by timeout...
        List<EventLog> events = eventLogService.findEventsByTimeout(100,
                IndexOperator.EVENTLOG_TOPIC_RAG_EVENT_INDEX,
                IndexOperator.EVENTLOG_TOPIC_RAG_EVENT_UPDATE,
                IndexOperator.EVENTLOG_TOPIC_RAG_EVENT_PROMPT,
                IndexOperator.EVENTLOG_TOPIC_RAG_EVENT_DELETE);

        if (events.size() > 0) {
            logger.log(Level.INFO, "├── 🔃 processing {0} RAG events....", events.size());

            for (EventLog eventLogEntry : events) {
                try {

                    logger.log(Level.INFO,
                            "│   ├── Event: " + eventLogEntry.getTopic() + " - " + eventLogEntry.getRef());
                    // first try to lock the eventLog entry....
                    if (eventLogService.lock(eventLogEntry)) {

                        // Delete Event?
                        if (eventLogEntry.getTopic().equals(IndexOperator.EVENTLOG_TOPIC_RAG_EVENT_DELETE
                                + ".lock")) {
                            // remove embeddings
                            clusterService.removeAllEmbeddings(eventLogEntry.getRef());
                            // remove the event log entry...
                            eventLogService.removeEvent(eventLogEntry.getId());
                            continue;
                        }

                        // Index / Update event?
                        ItemCollection workitem = workflowService.getWorkItem(eventLogEntry.getRef());
                        if (workitem == null) {
                            logger.log(Level.WARNING, "│   ├── ⚠️ unable to read workitem: " + eventLogEntry.getRef());
                            eventLogService.removeEvent(eventLogEntry.getId());
                            continue;
                        }

                        // handle event types
                        ItemCollection indexDefinition = new ItemCollection(eventLogEntry.getData());
                        switch (eventLogEntry.getTopic()) {
                        case IndexOperator.EVENTLOG_TOPIC_RAG_EVENT_UPDATE + ".lock":
                            // update workflow status
                            indexService.updateMetadata(indexDefinition, workitem);
                            break;
                        case IndexOperator.EVENTLOG_TOPIC_RAG_EVENT_INDEX + ".lock":
                            indexService.indexWorkitem(indexDefinition, workitem);
                            break;

                        case IndexOperator.EVENTLOG_TOPIC_RAG_EVENT_PROMPT + ".lock":
                            indexService.promptWorkitem(indexDefinition, workitem);
                            break;
                        default:
                            // no op
                        }

                        // finally remove the event log entry...
                        eventLogService.removeEvent(eventLogEntry.getId());
                    }

                } catch (OptimisticLockException e) {
                    // lock was not possible - continue....
                    logger.log(Level.INFO, "│   ├── ⚠️ unable to lock AsyncEvent: {0}", e.getMessage());
                } catch (ClusterException e) {
                    logger.log(Level.WARNING, e.getErrorCode() + " - " + e.getMessage());
                    // remove the event log entry...
                    eventLogService.removeEvent(eventLogEntry.getId());
                } catch (PluginException | AdapterException e) {
                    logger.log(Level.WARNING, e.getErrorCode() + " - " + e.getMessage());
                    // remove the event log entry...
                    eventLogService.removeEvent(eventLogEntry.getId());
                }

            }

            logger.log(Level.INFO, "├── ✅ {0} RAGEvents processed in {1}ms",
                    new Object[] { events.size(), System.currentTimeMillis() - l });

        }
    }

}
