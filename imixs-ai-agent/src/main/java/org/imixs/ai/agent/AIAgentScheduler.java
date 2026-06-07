/****************************************************************************
 * Copyright (c) 2025 Imixs Software Solutions GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 ****************************************************************************/
package org.imixs.ai.agent;

import static org.imixs.workflow.engine.AsyncEventSchedulerConfig.ASYNCEVENT_PROCESSOR_DEADLOCK;
import static org.imixs.workflow.engine.AsyncEventSchedulerConfig.ASYNCEVENT_PROCESSOR_ENABLED;
import static org.imixs.workflow.engine.AsyncEventSchedulerConfig.ASYNCEVENT_PROCESSOR_INITIALDELAY;
import static org.imixs.workflow.engine.AsyncEventSchedulerConfig.ASYNCEVENT_PROCESSOR_INTERVAL;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
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
 * The AIAgentScheduler starts a timer-based scheduler to process AI agent
 * EventLog entries asynchronously by delegating to the AIAgentOperator.
 * <p>
 * The scheduler uses the same timer configuration as the AsyncEventProcessor so
 * that no additional environment variables are needed.
 * <p>
 * Parameters:
 * <ul>
 * <li>ASYNCEVENT_PROCESSOR_INTERVAL - scheduler interval in ms</li>
 * <li>ASYNCEVENT_PROCESSOR_INITIALDELAY - optional startup delay in ms</li>
 * <li>ASYNCEVENT_PROCESSOR_ENABLED - must be set to true (default=false)</li>
 * <li>ASYNCEVENT_PROCESSOR_DEADLOCK - deadlock timeout in ms</li>
 * </ul>
 * <p>
 * The timer is non-persistent and scales horizontally in clustered
 * environments.
 *
 * @author rsoika
 */
@DeclareRoles({ "org.imixs.ACCESSLEVEL.MANAGERACCESS" })
@RunAs("org.imixs.ACCESSLEVEL.MANAGERACCESS")
@Startup
@Singleton
public class AIAgentScheduler {

    @Inject
    @ConfigProperty(name = ASYNCEVENT_PROCESSOR_ENABLED, defaultValue = "false")
    boolean enabled;

    @Inject
    @ConfigProperty(name = ASYNCEVENT_PROCESSOR_INTERVAL, defaultValue = "1000")
    long interval;

    @Inject
    @ConfigProperty(name = ASYNCEVENT_PROCESSOR_INITIALDELAY, defaultValue = "0")
    long initialDelay;

    @Inject
    @ConfigProperty(name = ASYNCEVENT_PROCESSOR_DEADLOCK, defaultValue = "60000")
    long deadLockInterval;

    private static final Logger logger = Logger.getLogger(AIAgentScheduler.class.getName());

    @Resource
    TimerService timerService;

    @Inject
    AIAgentOperator agentOperator;

    @Inject
    EventLogService eventLogService;

    @PostConstruct
    public void init() {
        if (enabled) {
            final TimerConfig timerConfig = new TimerConfig();
            timerConfig.setInfo("Imixs-AI AIAgentScheduler");
            timerConfig.setPersistent(false);
            timerService.createIntervalTimer(initialDelay, interval, timerConfig);
            logger.log(Level.INFO, "├── ✅ Started AIAgentScheduler - initialDelay={0}  interval={1}",
                    new Object[] { initialDelay, interval });
        }
    }

    /**
     * Releases deadlocks for the agent topic and delegates event processing to the
     * AIAgentOperator. Both operations run in separate transactions.
     */
    @Timeout
    public void run(Timer timer) {
        eventLogService.releaseDeadLocks(deadLockInterval, AIAgentOperator.AGENT_TOPIC_PROCESS);
        agentOperator.processEventLog();
    }
}