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
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.inject.Inject;

/**
 * The RAGIndexPlugin automatically index, updates or remove the workflow meta
 * data as embeddings. Embeddings are created by the {@link RAGEventService}.
 * <p>
 * The plugin can be controlled by its mode.
 * <ul>
 * <li>INDEX - index a workitem</li>
 * <li>UPDATE - default: update the workflow metadata only for a indexed
 * workitem</li>
 * <li>DELETE - remove the workitem from the index</li>
 * <li>DISABLED - no actaion</li>
 * 
 * Note, the UPDATE mode is the default mode. It can be deactivated with the
 * option 'DISABLED':
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
public class RAGIndexPlugin extends AbstractPlugin {

	private static final Logger logger = Logger.getLogger(RAGIndexPlugin.class.getName());

	@Inject
	private WorkflowService workflowService;

	@Inject
	private EventLogService eventLogService;

	@Inject
	private RAGService ragService;

	@Override
	public ItemCollection run(ItemCollection workitem, ItemCollection event) throws PluginException {

		// do not run on Snapshots!
		if (workitem.getType().startsWith("snapshot-")) {
			// no op!
			return workitem;
		}

		logger.finest("running RAGPlugin");

		// read optional configuration form the model or imixs.properties....
		List<ItemCollection> ragIndexDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-ai",
				RAGService.RAG_INDEX, workitem, false);
		List<ItemCollection> ragDisabledDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-ai",
				RAGService.RAG_DISABLED, workitem, false);
		// List<ItemCollection> ragUpdateDefinitions =
		// workflowService.evalWorkflowResultXML(event, "imixs-ai",
		// RAGService.RAG_UPDATE, workitem, false);
		List<ItemCollection> ragDeleteDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-ai",
				RAGService.RAG_DELETE, workitem, false);

		// disabled?
		if (ragDisabledDefinitions != null && ragDisabledDefinitions.size() > 0) {
			// Plugin is disabled
			return workitem;
		}

		try {
			// INDEX Mode?
			if (ragIndexDefinitions != null && ragIndexDefinitions.size() > 0) {
				ragService.createIndex(ragIndexDefinitions, workitem, event);
			}

			// DELETE mode?
			if (ragDeleteDefinitions != null && ragDeleteDefinitions.size() > 0) {
				eventLogService.createEvent(RAGEventService.EVENTLOG_TOPIC_RAG_EVENT_DELETE, workitem.getUniqueID());
				return workitem;
			}

			// Default: UPDATE
			eventLogService.createEvent(RAGEventService.EVENTLOG_TOPIC_RAG_EVENT_UPDATE, workitem.getUniqueID());
			return workitem;

		} catch (AdapterException e) {
			throw new PluginException(e);
		}

	}

}
