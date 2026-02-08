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

import static org.imixs.workflow.engine.AsyncEventSchedulerConfig.ASYNCEVENT_PROCESSOR_DEADLOCK;
import static org.imixs.workflow.engine.AsyncEventSchedulerConfig.ASYNCEVENT_PROCESSOR_ENABLED;
import static org.imixs.workflow.engine.AsyncEventSchedulerConfig.ASYNCEVENT_PROCESSOR_INITIALDELAY;
import static org.imixs.workflow.engine.AsyncEventSchedulerConfig.ASYNCEVENT_PROCESSOR_INTERVAL;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.workflow.engine.AsyncEventService;
import org.imixs.workflow.engine.EventLogService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RunAs;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.Timeout;
import jakarta.ejb.Timer;
import jakarta.ejb.TimerConfig;
import jakarta.ejb.TimerService;
import jakarta.inject.Inject;

/**
 * The IndexScheduler starts a scheduler service to process RAG events in an
 * asynchronous way by calling the IndexOperator.
 * <p>
 * The IndexScheduler runs on a non-persistent ejb timer with the same interval
 * settings as the AsyncEventProcessor
 * <p>
 * Parameters:
 * <ul>
 * <li>'ASYNCEVENT_PROCESSOR_INTERVAL' - scheduler interval</li>
 * <li>'ASYNCEVENT_PROCESSOR_INITIALDELAY' -optional delay defined by.</li>
 * <li>'ASYNCEVENT_PROCESSOR_ENABLED' - must be set to true to enable</li>
 * (default=false).
 * <li>'ASYNCEVENT_PROCESSOR_DEADLOCK' - the processor deadlock timeout</li>
 * </ul>
 * <p>
 * In a clustered environment this timer runs in each cluster member that
 * contains the EJB. So this means the non-persistent EJB Timer scales
 * horizontal within a clustered environment – e.g. a Kubernetes cluster.
 *
 * @see AsyncEventService
 * @version 1.1
 * @author rsoika
 *
 */
@DeclareRoles({ "org.imixs.ACCESSLEVEL.MANAGERACCESS" })
@RunAs("org.imixs.ACCESSLEVEL.MANAGERACCESS")
@Startup
@Singleton
public class IndexScheduler {

    // enabled
    @Inject
    @ConfigProperty(name = ASYNCEVENT_PROCESSOR_ENABLED, defaultValue = "false")
    boolean enabled;

    // timeout interval in ms
    @Inject
    @ConfigProperty(name = ASYNCEVENT_PROCESSOR_INTERVAL, defaultValue = "1000")
    long interval;

    // initial delay in ms
    @Inject
    @ConfigProperty(name = ASYNCEVENT_PROCESSOR_INITIALDELAY, defaultValue = "0")
    long initialDelay;

    // deadlock timeout interval in ms
    @Inject
    @ConfigProperty(name = ASYNCEVENT_PROCESSOR_DEADLOCK, defaultValue = "60000")
    long deadLockInterval;

    private static final Logger logger = Logger.getLogger(IndexScheduler.class.getName());

    @Resource
    TimerService timerService;

    @Inject
    IndexOperator indexOperator;

    @Inject
    EventLogService eventLogService;

    @PostConstruct
    public void init() {
        if (enabled) {
            // Registering a non-persistent Timer Service.
            final TimerConfig timerConfig = new TimerConfig();
            timerConfig.setInfo("Imixs-Workflow IndexScheduler");
            timerConfig.setPersistent(false);
            timerService.createIntervalTimer(initialDelay, interval, timerConfig);
            logger.log(Level.INFO, "├── ✅ Started IndexScheduler - initalDelay={0}  inverval={1}",
                    new Object[] { initialDelay, interval });
        }
    }

    /**
     * The method delegates the event processing to the IndexOperator (stateless
     * ejb).
     * <p>
     * Before processing the eventLog the method releases possible dead locks first.
     * Both methods are running in separate transactions
     * 
     */
    @Timeout
    public void run(Timer timer) {
        eventLogService.releaseDeadLocks(deadLockInterval,
                IndexOperator.EVENTLOG_TOPIC_RAG_EVENT_INDEX,
                IndexOperator.EVENTLOG_TOPIC_RAG_EVENT_UPDATE,
                IndexOperator.EVENTLOG_TOPIC_RAG_EVENT_PROMPT,
                IndexOperator.EVENTLOG_TOPIC_RAG_EVENT_DELETE);
        indexOperator.processEventLog();
    }

}
