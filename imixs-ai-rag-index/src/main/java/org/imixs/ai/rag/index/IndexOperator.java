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
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.imixs.ai.rag.RAGUtil;
import org.imixs.ai.rag.cluster.ClusterException;
import org.imixs.ai.rag.cluster.ClusterService;
import org.imixs.melman.EventLogClient;
import org.imixs.melman.RestAPIException;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.PluginException;

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
 * the operator locks the eventLog entry. After that update we can be sure that
 * no other process is dealing with these entries. After completing the
 * processing step the eventlog entry will be removed.
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
@Stateless
@LocalBean
public class IndexOperator {

    private static final Logger logger = Logger.getLogger(IndexOperator.class.getName());

    @Inject
    private ClusterService clusterService;

    @Inject
    private RAGService ragService;

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
     * @throws RestAPIException
     * 
     */
    @TransactionAttribute(value = TransactionAttributeType.REQUIRES_NEW)
    public void processEventLog(EventLogClient eventLogClient) throws RestAPIException {
        long l = System.currentTimeMillis();

        // test for new event log entries by timeout...

        eventLogClient.setPageSize(100);
        List<ItemCollection> events = eventLogClient.searchEventLog(RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_INDEX,
                RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_UPDATE,
                RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_PROMPT,
                RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_DELETE);

        if (events.size() > 0) {
            logger.log(Level.INFO, "├── 🔃 processing {0} RAG events....", events.size());

            for (ItemCollection eventLogEntry : events) {
                String eventTopic = eventLogEntry.getItemValueString("topic");
                String eventId = eventLogEntry.getItemValueString("id");
                String eventRef = eventLogEntry.getItemValueString("ref");
                ItemCollection workitem = null;

                if (eventLogEntry.hasItem("data")) {
                    List<?> dataList = eventLogEntry.getItemValue("data");
                    if (dataList != null && dataList.size() > 0) {
                        workitem = new ItemCollection((Map<String, List<Object>>) dataList.get(0));
                    }
                }

                try {

                    logger.log(Level.INFO,
                            "│   ├── Event: " + eventTopic + " - " + eventRef);
                    // first try to lock the eventLog entry....

                    eventLogClient.lockEventLogEntry(eventId);

                    // Delete Event?
                    if (eventTopic.equals(RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_DELETE)) {
                        // remove embeddings
                        clusterService.removeAllEmbeddings(eventRef);
                        // remove the event log entry...
                        eventLogClient.deleteEventLogEntry(eventRef);
                        continue;
                    }

                    // Index / Update event?
                    if (workitem == null) {
                        logger.log(Level.WARNING, "│   ├── ⚠️ unable to read workitem: " + eventRef);
                        eventLogClient.deleteEventLogEntry(eventId);
                        continue;
                    }

                    // handle event types
                    switch (eventTopic) {
                    case RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_UPDATE:
                        // update workflow status
                        ragService.updateMetadata(workitem);
                        break;
                    case RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_INDEX:
                        ragService.indexWorkitem(workitem);
                        break;

                    default:
                        // no op
                    }

                    // finally remove the event log entry...
                    eventLogClient.deleteEventLogEntry(eventId);

                } catch (OptimisticLockException e) {
                    // lock was not possible - continue....
                    logger.log(Level.INFO, "│   ├── ⚠️ unable to lock AsyncEvent: {0}", e.getMessage());
                } catch (ClusterException e) {
                    logger.log(Level.WARNING, e.getErrorCode() + " - " + e.getMessage());
                    // remove the event log entry...
                    eventLogClient.deleteEventLogEntry(eventId);
                } catch (PluginException e) {
                    logger.log(Level.WARNING, e.getErrorCode() + " - " + e.getMessage());
                    // remove the event log entry...
                    eventLogClient.deleteEventLogEntry(eventId);
                }

            }

            logger.log(Level.INFO, "├── ✅ {0} RAGEvents processed in {1}ms",
                    new Object[] { events.size(), System.currentTimeMillis() - l });

        }
    }

}
