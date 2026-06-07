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

package org.imixs.ai.agent;

import java.util.List;
import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.engine.plugins.AbstractPlugin;
import org.imixs.workflow.exceptions.AccessDeniedException;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.exceptions.ProcessingErrorException;

import jakarta.inject.Inject;

/**
 * The AIAgentPlugin can be used to start a new AI Agent process. The plugin
 * creates a new Agent-Workitem and connects it with the current workitem
 * <p>
 * The plugin can be configured in the BPMN event workflow config:
 * 
 * <pre>
  {@code
   <imixs-ai name="AGENT">
      <debug>false</debug>  
      <agent.model>ai-agent-calculator-de-1.0</agent.model>
      <agent.init.task>100</agent.init.task>
      <agent.init.event>100</agent.init.event>
   </imixs-ai>
    }
 * </pre>
 * <p>
 * The tag 'debug' is optional
 * 
 * 
 * @author rsoika
 * 
 */
public class AIAgentPlugin extends AbstractPlugin {

	public static final String AGENT = "AGENT";

	private static final Logger logger = Logger.getLogger(AIAgentPlugin.class.getName());

	@Inject
	private WorkflowService workflowService;

	@Inject
	private AIAgentOperator aiAgentOperator;

	@Override
	public ItemCollection run(ItemCollection workitem, ItemCollection event) throws PluginException {

		// do not run on Snapshots!
		if (workitem.getType().startsWith("snapshot-")) {
			// no op!
			return workitem;
		}

		logger.finest("running RAGPlugin");

		// read optional configuration form the model or imixs.properties....
		List<ItemCollection> agentDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-ai",
				AGENT, workitem, true);

		// AGENT Mode?
		if (agentDefinitions != null) {
			for (ItemCollection agentConfig : agentDefinitions) {
				try {
					ItemCollection agentWorkitem = aiAgentOperator.createAgent(agentConfig, workitem);
					// set reference
					workitem.setItemValue("$workitemref", agentWorkitem.getUniqueID());
				} catch (AccessDeniedException | ProcessingErrorException | PluginException | ModelException e) {
					throw new PluginException(AIAgentPlugin.class.getSimpleName(),
							"AGENT_ERROR", "Unable to create new Agent: " + e.getMessage());
				}
			}
		}

		return workitem;

	}

}
