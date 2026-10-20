/*******************************************************************************
 *  Imixs Workflow Technology
 *  Copyright (C) 2001, 2008 Imixs Software Solutions GmbH,
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
 *  Contributors:
 *  	Imixs Software Solutions GmbH - initial API and implementation
 *  	Ralph Soika
 *******************************************************************************/
package org.imixs.ai.rag.index;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.ai.rag.RAGUtil;
import org.imixs.ai.rag.metrics.MetricService;
import org.imixs.ai.rag.util.LogController;
import org.imixs.ai.rag.util.RestClientHelper;
import org.imixs.melman.DocumentClient;
import org.imixs.melman.EventLogClient;
import org.imixs.melman.RestAPIException;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.InvalidAccessException;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
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
 * The IndexService updates the RAG index based on the index events from the
 * event log. The service class runs a TimerService based on the given scheduler
 * configuration.
 * <p>
 * The service automatically starts during deployment.
 * <p>
 *
 * @version 1.0
 * @author rsoika
 */
@Singleton
@Startup
public class IndexScheduler {

    private static Logger logger = Logger.getLogger(IndexScheduler.class.getName());

    public static final String ENV_WORKFLOW_SERVICE_ENDPOINT = "workflow.service.endpoint";
    public static final String ENV_WORKFLOW_SERVICE_USER = "workflow.service.user";
    public static final String ENV_WORKFLOW_SERVICE_PASSWORD = "workflow.service.password";
    public static final String ENV_WORKFLOW_SERVICE_AUTHMETHOD = "workflow.service.authmethod";
    public static final String ENV_WORKFLOW_SYNC_INTERVAL = "workflow.sync.interval";
    public static final String ENV_WORKFLOW_SYNC_INITIALDELAY = "workflow.sync.initialdelay";
    public static final String ENV_WORKFLOW_SYNC_DEADLOCK = "workflow.sync.deadlock";

    // REST client
    public static final String ENV_WORKFLOW_REST_CONNECT_TIMEOUT = "workflow.rest.connecttimeout";
    public static final String ENV_WORKFLOW_REST_READ_TIMEOUT = "workflow.rest.readtimeout";

    // OIDC
    public static final String ENV_OIDC_AUTH_ENDPOINT = "oidc.auth.endpoint";
    public static final String ENV_OIDC_AUTH_CLIENT_ID = "oidc.auth.client.id";
    public static final String ENV_OIDC_AUTH_CLIENT_SECRET = "oidc.auth.client.secret";

    public static final String EVENTLOG_TOPIC_INDEX = "imix.ai.rag.index";
    public static final String INDEX_INDEX_DEADLOCK = "imixs.ai.rag.index.deadlock";

    public static final String TOPIC_INDEX = "INDEX";

    public final static String SNAPSHOT_RESOURCE = "snapshot/";

    @Inject
    LogController logController;

    public static final String METRIC_EVENTS_PROCESSED = "index_events_processed";
    public static final String METRIC_EVENTS_ERRORS = "index_events_errors";

    // timeout interval in ms
    @Inject
    @ConfigProperty(name = IndexScheduler.ENV_WORKFLOW_SYNC_INTERVAL, defaultValue = "10000")
    long interval;

    @Inject
    @ConfigProperty(name = IndexScheduler.ENV_WORKFLOW_SYNC_INITIALDELAY, defaultValue = "30000")
    long initialDelay;

    @Inject
    @ConfigProperty(name = IndexScheduler.ENV_WORKFLOW_SERVICE_ENDPOINT)
    Optional<String> workflowServiceEndpoint;

    @Inject
    @ConfigProperty(name = IndexScheduler.ENV_WORKFLOW_SERVICE_AUTHMETHOD)
    Optional<String> workflowServiceAuthMethod;

    @Inject
    @ConfigProperty(name = IndexScheduler.ENV_WORKFLOW_SERVICE_USER)
    Optional<String> workflowServiceUser;

    @Inject
    @ConfigProperty(name = IndexScheduler.ENV_WORKFLOW_SERVICE_PASSWORD)
    Optional<String> workflowServicePassword;

    // deadlock timeout interval in ms
    @Inject
    @ConfigProperty(name = INDEX_INDEX_DEADLOCK, defaultValue = "60000")
    long deadLockInterval;

    @Resource
    TimerService timerService;

    @Inject
    RestClientHelper restClientHelper;

    @Inject
    IndexStatusHandler indexStatusHandler;

    @Inject
    MetricService metricService;

    @Inject
    IndexOperator indexOperator;

    DocumentClient documentClient = null;
    EventLogClient eventLogClient = null;

    @PostConstruct
    public void init() {

        metricService.init();

        // init timer....
        if (workflowServiceEndpoint.isPresent()) {
            logger.info("init workflow instance endpoint: " + workflowServiceEndpoint.get().toString());
            // Registering a non-persistent Timer Service.
            try {
                startScheduler(true);
            } catch (IndexException e) {
                logController.warning(TOPIC_INDEX, "Failed to init scheduler: " + e.getMessage());
            }
        } else {
            logController.warning(TOPIC_INDEX,
                    "Missing environment param 'WORKFLOW_SERVICE_ENDPOINT' - please verify configuration!");
        }
    }

    /**
     * This is the method which processes the timeout event depending on the running
     * timer settings. The method lookups the event log entries and pushes new
     * snapshots into the archive service.
     * <p>
     * Each eventLogEntry is locked to guaranty exclusive processing.
     *
     * @throws RestAPIException
     **/
    @SuppressWarnings({ "unused" })
    @Timeout
    public void onTimeout(jakarta.ejb.Timer _timer) {
        String topic = null;
        String id = null;
        String ref = null;
        int total = 0;
        int success = 0;
        int errors = 0;
        long duration = System.currentTimeMillis();
        try {
            indexStatusHandler.setTimer(_timer);
            logger.fine("├── Processing RAG index events...");
            // init rest clients....
            DocumentClient documentClient = restClientHelper.createDocumentClient();

            EventLogClient eventLogClient = restClientHelper.createEventLogClient(documentClient);
            if (documentClient == null || eventLogClient == null) {
                logController.warning(TOPIC_INDEX,
                        "├── Unable to connect to workflow instance endpoint - verify configuration!");
                try {
                    stopScheduler();
                } catch (IndexException e) {
                }
                return;
            }

            indexStatusHandler.setStatus(IndexStatusHandler.STATUS_RUNNING);

            logger.finest("......release dead locks....");
            // release dead locks...
            releaseDeadLocks(eventLogClient);

            // total unprocessed metrics
            long totalUnprocessed = eventLogClient.countEventLogEntries(getEventLogTopic());
            metricService.setUnprocessedEventLogEntries(totalUnprocessed);

            // max 100 entries per iteration
            eventLogClient.setPageSize(100);
            List<ItemCollection> events = eventLogClient.searchEventLog(getEventLogTopic());

            indexOperator.processEventLog(eventLogClient);

            indexStatusHandler.setStatus(IndexStatusHandler.STATUS_SCHEDULED);

        } catch (InvalidAccessException | EJBException | RestAPIException e) {
            // In case of a exception during processing the event log
            // the timer service will automatically restarted. This is important
            // to resolve restarts of the workflow engine.
            logController.warning(TOPIC_INDEX, "processing EventLog failed: " + e.getMessage());
            // e.printStackTrace();
            try {
                restartScheduler();
            } catch (IndexException e1) {
                logController.warning(TOPIC_INDEX, "Failed to restart RAG Index scheduler: " + e.getMessage());
            }
        }
    }

    public long getUnprocessedEventLogEntries() {

        long result = -1;
        try {
            DocumentClient documentClient = restClientHelper.createDocumentClient();
            EventLogClient eventLogClient = restClientHelper.createEventLogClient(documentClient);
            logger.info("count event log entries: " + getEventLogTopic());
            result = eventLogClient.countEventLogEntries(getEventLogTopic());

        } catch (RestAPIException e) {
            logController.warning(TOPIC_INDEX, "Failed to count unprocessed event log: " + e.getMessage());
        }

        return result;

    }

    /**
     * Asynchronous method to release dead locks
     *
     * @param eventLogClient
     * @param deadLockInterval
     * @param topic
     * @throws RestAPIException
     */
    @TransactionAttribute(value = TransactionAttributeType.REQUIRES_NEW)
    public void releaseDeadLocks(EventLogClient eventLogClient) throws RestAPIException {
        if (eventLogClient == null) {
            // no client object
            logger.fine("...no eventLogClient available!");
            return;
        }

        eventLogClient.releaseDeadLocks(deadLockInterval, getEventLogTopic());
    }

    /**
     * This helper method returns all RAG Index topics
     * 
     * @return string list of all topices
     */
    public String[] getEventLogTopic() {
        return new String[] { RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_INDEX,
                RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_UPDATE,
                RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_PROMPT,
                RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_DELETE };
    }

    /**
     * This method loads a snapshot from the workflow instance.
     * <p>
     * The method returns null if the snapshot no longer exists. In this case the
     * method automatically deletes the outdated event log entry.
     *
     * @throws IndexException
     */
    public ItemCollection pullSnapshot(ItemCollection eventLogEntry, DocumentClient documentClient,
            EventLogClient eventLogClient) throws IndexException {
        String ref = null;
        String id = null;
        try {
            if (eventLogEntry == null || documentClient == null || eventLogClient == null) {
                // no client object
                logger.fine("...no eventLogClient available!");
                return null;
            }

            ref = eventLogEntry.getItemValueString("ref");
            id = eventLogEntry.getItemValueString("id");
            logger.fine("......pullSnapshot ref " + ref + "...");
            // lookup the snapshot...
            ItemCollection snapshot;
            // reset items
            documentClient.setItems(null);
            snapshot = documentClient.getDocument(ref);
            if (snapshot != null) {
                logger.fine("...write snapshot into index store...");
                return snapshot;
            }
        } catch (RestAPIException e) {
            Throwable cause = e.getCause();

            if (cause instanceof jakarta.ws.rs.NotFoundException) {
                // snapshot no longer exists on the workflow instance -> safe to remove the
                // event log entry
                logController.warning(TOPIC_INDEX,
                        "Snapshot " + ref + " no longer exists - removing EventLogEntry " + id);
                try {
                    eventLogClient.deleteEventLogEntry(id);
                } catch (RestAPIException e1) {
                    throw new IndexException("REMOTE_EXCEPTION", "Unable to delete eventLogEntry: " + id, e1);
                }
            } else {
                // technical error (e.g. timeout, connection issue) -> keep the event log entry
                // so it will be retried
                logController.warning(TOPIC_INDEX,
                        "Snapshot " + ref + " pull failed (will be retried): " + e.getMessage());
                throw new IndexException("REMOTE_EXCEPTION", "Failed to pull Snapshot " + ref, e);
            }
        } catch (RuntimeException e) {
            // can occur in rare cases on the ejb container
            throw new IndexException("REMOTE_EXCEPTION", "Failed to pull Snapshot " + ref + " -> " + e.getMessage(),
                    e);
        }
        return null;
    }

    /**
     * Stops and restarts the timer. The log will be prevented.
     *
     * @throws IndexException
     */
    public void restartScheduler() throws IndexException {
        stopScheduler();
        startScheduler(false);
    }

    /**
     * This method initializes the scheduler.
     * <p>
     * The method also verifies the existence of the archive keyspace by loading the
     * archive session object.
     *
     * @param clearLog - if true, the current log will be reset
     * @throws IndexException
     */
    public void startScheduler(boolean clearLog) throws IndexException {
        try {
            restClientHelper.reset();
            if (clearLog) {
                // clear log in case of a normal start
                logController.reset(TOPIC_INDEX);
            }
            logController.info(TOPIC_INDEX, "Starting index scheduler - initialDelay=" + initialDelay
                    + "ms  inverval=" + interval + "ms ....");
            // start archive schedulers....
            // Registering a non-persistent Timer Service.
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
     * This method stops the scheduler.
     *
     *
     * @throws IndexException
     */
    public boolean stopScheduler() throws IndexException {
        Timer timer = indexStatusHandler.getTimer();
        if (timer != null) {
            try {
                logController.info(TOPIC_INDEX, "Stopping the index scheduler...");
                timer.cancel();
            } catch (IllegalArgumentException | IllegalStateException | EJBException e) {
                throw new IndexException("TIMER_EXCEPTION", "Failed to stop scheduler ", e);
            }
            // update status message
            logController.info(TOPIC_INDEX, "Timer stopped. ");
        }
        indexStatusHandler.setStatus(IndexStatusHandler.STATUS_STOPPED);
        return true;
    }

}
