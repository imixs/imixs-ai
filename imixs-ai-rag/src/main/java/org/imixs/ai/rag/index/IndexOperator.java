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

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.imixs.ai.rag.RAGService;
import org.imixs.ai.rag.RAGUtil;
import org.imixs.ai.rag.metrics.MetricService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.EventLogService;
import org.imixs.workflow.engine.jpa.EventLog;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.IndexException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.index.vectors.LuceneVectorIndexService;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

/**
 * The IndexOperator processes RAG events from the EventLog. It is called by the
 * IndexScheduler only.
 * <p>
 * The operator reacts on the following topics:
 * <ul>
 * <li>rag.event.index - compute the embeddings and store them in the vector
 * index</li>
 * <li>rag.event.delete - remove all vectors of a document</li>
 * </ul>
 * <p>
 * Computing embeddings requires LLM calls, which can take several seconds. To
 * avoid long running database transactions, an event is processed in three
 * steps:
 * <ol>
 * <li>{@link #lockEvent} - locks the EventLog entry in a short transaction. The
 * topic is suffixed with '.lock', so the event is not processed twice.</li>
 * <li>{@link #processEvent} - computes the embeddings and updates the vector
 * index - <b>without</b> a database transaction.</li>
 * <li>{@link #removeEvent} - removes the EventLog entry in a short
 * transaction.</li>
 * </ol>
 * If the processing fails temporarily (e.g. the LLM endpoint is not reachable),
 * the event stays locked. The deadlock release of the IndexScheduler unlocks it
 * after the deadlock interval, so the event is retried automatically.
 *
 * @see IndexScheduler
 * @version 2.0
 * @author rsoika
 */
@Stateless
@LocalBean
public class IndexOperator {

    /**
     * Result of processing a single event.
     */
    public enum Result {
        /** the event was processed - remove the EventLog entry */
        DONE,
        /** the event is invalid and can never be processed - remove the entry */
        FAILED,
        /** a temporary error occurred - keep the lock, the event is retried */
        RETRY
    }

    private static final Logger logger = Logger.getLogger(IndexOperator.class.getName());

    @Inject
    private EventLogService eventLogService;

    @Inject
    private RAGService ragService;

    @Inject
    private LuceneVectorIndexService vectorIndexService;

    @Inject
    private MetricService metricService;

    /**
     * Returns the ids of the next unprocessed RAG events, ordered by creation date.
     *
     * @param maxCount - maximum number of events
     * @return list of EventLog ids
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public List<String> findEvents(int maxCount) {
        List<String> result = new ArrayList<>();
        List<EventLog> events = eventLogService.findEventsByTopic(maxCount, RAGUtil.getTopics());
        for (EventLog event : events) {
            result.add(event.getId());
        }
        return result;
    }

    /**
     * Locks an EventLog entry and returns a detached copy of its data. Returns
     * null if the entry no longer exists or is already locked by another process.
     *
     * @param id - the EventLog id
     * @return the locked event or null
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public RAGEvent lockEvent(String id) {
        EventLog eventLog;
        try {
            eventLog = eventLogService.getEvent(id);
        } catch (IllegalArgumentException e) {
            // getEvent() fails on detach if the entry no longer exists
            return null;
        }
        if (eventLog == null || eventLog.getTopic().endsWith(".lock")) {
            // already processed or locked by another process
            return null;
        }
        // read the data before locking - the topic changes with the lock
        RAGEvent ragEvent = new RAGEvent(eventLog.getId(), eventLog.getTopic(), eventLog.getRef(),
                new ItemCollection(eventLog.getData()));
        if (!eventLogService.lock(eventLog)) {
            return null;
        }
        return ragEvent;
    }

    /**
     * Removes an EventLog entry.
     *
     * @param id - the EventLog id
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void removeEvent(String id) {
        eventLogService.removeEvent(id);
    }

    /**
     * Processes a locked event. The method runs without a database transaction,
     * because computing embeddings can take several seconds.
     *
     * @param event - the locked event
     * @return the result of the processing
     */
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public Result processEvent(RAGEvent event) {
        long l = System.currentTimeMillis();
        logger.log(Level.FINE, "│   ├── {0} ⇢ {1}", new Object[] { event.topic(), event.ref() });
        try {
            switch (event.topic()) {
                case RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_INDEX:
                    indexDocument(event);
                    break;
                case RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_DELETE:
                    vectorIndexService.removeVectors(event.ref());
                    break;
                default:
                    logger.log(Level.WARNING, "│   ├── ⚠️ unsupported topic ''{0}'' - event removed", event.topic());
                    return Result.FAILED;
            }
            metricService.countMetric(MetricService.METRIC_EVENTS_PROCESSED);
            logger.log(Level.FINE, "│   ├── ✅ {0} processed in {1}ms",
                    new Object[] { event.ref(), System.currentTimeMillis() - l });
            return Result.DONE;

        } catch (AdapterException | IndexException e) {
            // temporary error (LLM endpoint or index not available) - retry later
            logger.log(Level.WARNING, "│   ├── ⚠️ processing {0} failed - will be retried: {1}",
                    new Object[] { event.ref(), e.getMessage() });
            metricService.countMetric(MetricService.METRIC_EVENTS_ERRORS);
            return Result.RETRY;

        } catch (PluginException | IllegalArgumentException e) {
            // permanent error (invalid event data) - the event can never be processed
            logger.log(Level.WARNING, "│   ├── ⚠️ invalid event for {0} - event removed: {1}",
                    new Object[] { event.ref(), e.getMessage() });
            metricService.countMetric(MetricService.METRIC_EVENTS_ERRORS);
            return Result.FAILED;
        }
    }

    /**
     * Computes the embeddings of the event text and replaces the vectors of the
     * category in the vector index.
     */
    private void indexDocument(RAGEvent event) throws PluginException, AdapterException {
        ItemCollection data = event.data();
        String category = data.getItemValueString(RAGUtil.ITEM_RAG_CATEGORY);
        String text = data.getItemValueString(RAGUtil.ITEM_RAG_TEXT);
        boolean debug = data.getItemValueBoolean(RAGUtil.ITEM_DEBUG);

        // compute one embedding per chunk
        List<float[]> vectors = ragService.createEmbeddings(text, debug);
        // replace the vectors of this category
        vectorIndexService.indexVectors(event.ref(), category, vectors);
    }

}