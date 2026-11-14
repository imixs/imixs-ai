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

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.ai.rag.RAGService;
import org.imixs.ai.rag.metrics.MetricService;
import org.imixs.ai.rag.util.LogController;
import org.imixs.workflow.engine.EventLogService;
import org.imixs.workflow.index.vectors.LuceneVectorIndexManager;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RunAs;
import jakarta.ejb.EJBException;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.Timeout;
import jakarta.ejb.Timer;
import jakarta.ejb.TimerConfig;
import jakarta.ejb.TimerService;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

/**
 * The IndexScheduler processes the RAG events of the EventLog periodically. The
 * events are processed by the {@link IndexOperator}.
 * <p>
 * The scheduler is started only if the vector index of the imixs-workflow-index
 * is enabled.
 * <p>
 * The timeout method runs without a database transaction. Each step of the
 * IndexOperator runs in its own short transaction, and the LLM calls run
 * outside of any transaction.
 * <p>
 * Events locked longer than the deadlock interval are unlocked at the beginning
 * of each run. This releases events of a crashed run and retries events which
 * failed temporarily (e.g. LLM endpoint not reachable).
 *
 * @version 2.0
 * @author rsoika
 */
@Singleton
@Startup
@DeclareRoles({ "org.imixs.ACCESSLEVEL.MANAGERACCESS" })
@RunAs("org.imixs.ACCESSLEVEL.MANAGERACCESS")
public class IndexScheduler {

    private static Logger logger = Logger.getLogger(IndexScheduler.class.getName());

    public static final String CONFIG_INTERVAL = "rag.scheduler.interval";
    public static final String CONFIG_INITIALDELAY = "rag.scheduler.initialdelay";
    public static final String CONFIG_DEADLOCK = "rag.scheduler.deadlock";

    // maximum number of events processed per run
    public static final int MAX_EVENTS_PER_RUN = 100;

    public static final String TOPIC_INDEX = "INDEX";

    @Inject
    LogController logController;

    // timeout interval in ms
    @Inject
    @ConfigProperty(name = CONFIG_INTERVAL, defaultValue = "10000")
    long interval;

    @Inject
    @ConfigProperty(name = CONFIG_INITIALDELAY, defaultValue = "30000")
    long initialDelay;

    // deadlock interval in ms - a locked event older than this is retried
    @Inject
    @ConfigProperty(name = CONFIG_DEADLOCK, defaultValue = "60000")
    long deadLockInterval;

    @Resource
    TimerService timerService;

    @Inject
    IndexStatusHandler indexStatusHandler;

    @Inject
    MetricService metricService;

    @Inject
    IndexOperator indexOperator;

    @Inject
    EventLogService eventLogService;

    @Inject
    LuceneVectorIndexManager vectorIndexManager;

    @Inject
    RAGService ragService;

    @PostConstruct
    public void init() {
        if (!vectorIndexManager.isEnabled()) {
            logger.log(Level.INFO, "├── RAG index scheduler not started - vector index is disabled");
            return;
        }
        // a missing embedding configuration aborts the deployment
        ragService.verifyConfiguration();

        metricService.init();
        try {
            startScheduler(true);
        } catch (IndexException e) {
            logController.warning(TOPIC_INDEX, "Failed to init scheduler: " + e.getMessage());
        }
    }

    /**
     * Processes the next RAG events. Each event is locked, processed and removed
     * by the IndexOperator. The method itself runs without a transaction.
     */
    @Timeout
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void onTimeout(Timer timer) {
        long l = System.currentTimeMillis();
        int done = 0;
        int failed = 0;
        int retry = 0;
        try {
            indexStatusHandler.setTimer(timer);
            indexStatusHandler.setStatus(IndexStatusHandler.STATUS_RUNNING);

            // unlock events of a crashed run or a temporary error
            eventLogService.releaseDeadLocks(deadLockInterval, IndexOperator.getTopics());

            List<String> ids = indexOperator.findEvents(MAX_EVENTS_PER_RUN);
            if (!ids.isEmpty()) {
                logger.log(Level.INFO, "├── 🔃 processing {0} RAG event(s)...", ids.size());
                for (String id : ids) {
                    RAGEvent event = indexOperator.lockEvent(id);
                    if (event == null) {
                        // already processed or locked by another process
                        continue;
                    }
                    IndexOperator.Result result = indexOperator.processEvent(event);
                    switch (result) {
                        case DONE:
                            indexOperator.removeEvent(id);
                            done++;
                            break;
                        case FAILED:
                            indexOperator.removeEvent(id);
                            failed++;
                            break;
                        case RETRY:
                            // keep the lock - the event is unlocked by the deadlock release
                            retry++;
                            break;
                    }
                }
                logger.log(Level.INFO, "│   └── ✅ {0} processed, {1} failed, {2} retry in {3}ms",
                        new Object[] { done, failed, retry, System.currentTimeMillis() - l });
            }

            metricService.setUnprocessedEventLogEntries(getUnprocessedEventLogEntries());
            indexStatusHandler.setStatus(IndexStatusHandler.STATUS_SCHEDULED);

        } catch (EJBException e) {
            // In case of an exception the timer service is restarted. This is
            // important to resolve restarts of the workflow engine.
            logController.warning(TOPIC_INDEX, "processing EventLog failed: " + e.getMessage());
            try {
                restartScheduler();
            } catch (IndexException e1) {
                logController.warning(TOPIC_INDEX, "Failed to restart RAG index scheduler: " + e1.getMessage());
            }
        }
    }

    /**
     * Returns the number of unprocessed RAG events.
     */
    public long getUnprocessedEventLogEntries() {
        return eventLogService.countEventsByTopic(IndexOperator.getTopics());
    }

    /**
     * Stops and restarts the timer.
     *
     * @throws IndexException
     */
    public void restartScheduler() throws IndexException {
        stopScheduler();
        startScheduler(false);
    }

    /**
     * Starts a non-persistent interval timer.
     *
     * @param clearLog - if true, the current log will be reset
     * @throws IndexException
     */
    public void startScheduler(boolean clearLog) throws IndexException {
        try {
            if (clearLog) {
                logController.reset(TOPIC_INDEX);
            }
            logController.info(TOPIC_INDEX, "Starting RAG index scheduler - initialDelay=" + initialDelay
                    + "ms interval=" + interval + "ms ....");
            final TimerConfig timerConfig = new TimerConfig();
            timerConfig.setInfo(""); // empty info string indicates no JSESSIONID!
            timerConfig.setPersistent(false);
            Timer timer = timerService.createIntervalTimer(initialDelay, interval, timerConfig);
            indexStatusHandler.setTimer(timer);
        } catch (IllegalArgumentException | IllegalStateException | EJBException e) {
            throw new IndexException("TIMER_EXCEPTION", "Failed to init scheduler ", e);
        }
        indexStatusHandler.setStatus(IndexStatusHandler.STATUS_SCHEDULED);
    }

    /**
     * Stops the scheduler.
     *
     * @throws IndexException
     */
    public boolean stopScheduler() throws IndexException {
        Timer timer = indexStatusHandler.getTimer();
        if (timer != null) {
            try {
                logController.info(TOPIC_INDEX, "Stopping the RAG index scheduler...");
                timer.cancel();
            } catch (IllegalArgumentException | IllegalStateException | EJBException e) {
                throw new IndexException("TIMER_EXCEPTION", "Failed to stop scheduler ", e);
            }
            logController.info(TOPIC_INDEX, "Timer stopped.");
        }
        indexStatusHandler.setStatus(IndexStatusHandler.STATUS_STOPPED);
        return true;
    }

}