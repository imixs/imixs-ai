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

import java.util.List;
import java.util.logging.Logger;

import org.imixs.ai.rag.events.RAGEventService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.EventLogService;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.engine.plugins.AbstractPlugin;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.inject.Inject;

/**
 * The RAGIndexPlugin updates the workflow meta data for existing embeddings.
 * The plugin can be deactivated with:
 * <p>
 * 
 * <pre>
  {@code
   <rag-index name="DISABLE">
      <debug>false</debug>  
   </rag-index>
    }
 * </pre>
 * <p>
 * The tag 'debug' is optional
 * 
 * 
 * @author rsoika
 * 
 */
public class RAGPlugin extends AbstractPlugin {

	private static final Logger logger = Logger.getLogger(RAGPlugin.class.getName());

	@Inject
	private WorkflowService workflowService;

	@Inject
	EventLogService eventLogService;

	@Override
	public ItemCollection run(ItemCollection workitem, ItemCollection event) throws PluginException {
		List<ItemCollection> deleteDefinitions = null;
		List<ItemCollection> disableDefinitions = null;

		logger.finest("running TaxonomyPlugin");
		String workflowResult = event.getItemValueString("workflow.result");
		if (workflowResult.contains("<rag-index")) {
			deleteDefinitions = workflowService.evalWorkflowResultXML(event, "rag-index",
					"DELETE", workitem, false);

			disableDefinitions = workflowService.evalWorkflowResultXML(event,
					"rag-index",
					"DISABLE", workitem, false);
		}

		// disabled?
		if (disableDefinitions != null && disableDefinitions.size() > 0) {
			// no op!
			return workitem;
		}

		// delete mode?
		if (deleteDefinitions != null && deleteDefinitions.size() > 0) {
			eventLogService.createEvent(RAGEventService.EVENTLOG_TOPIC_RAG_EVENT_DELETE, workitem.getUniqueID());
			return workitem;
		}

		// default update meta information
		eventLogService.createEvent(RAGEventService.EVENTLOG_TOPIC_RAG_EVENT_UPDATE, workitem.getUniqueID());

		return workitem;

	}

}
