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

package org.imixs.ai.rag.workflow;

import java.util.List;
import java.util.logging.Logger;

import org.imixs.ai.rag.index.IndexOperator;
import org.imixs.ai.rag.index.IndexService;
import org.imixs.ai.workflow.ImixsAIPromptService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.EventLogService;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.engine.plugins.AbstractPlugin;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.inject.Inject;

/**
 * The RAGIndexPlugin automatically index, updates or remove the workflow meta
 * data as embeddings. Embeddings are created by the {@link IndexOperator}.
 * <p>
 * The plugin can be controlled by an `<imixs-ai>` definition in the following
 * modes.
 * <ul>
 * <li>INDEX - index a workitem</li>
 * <li>UPDATE - default: update the workflow metadata only for a indexed
 * workitem</li>
 * <li>PROMPT - send an LLM completion request and index the result</li>
 * <li>DELETE - remove the workitem from the index</li>
 * <li>DISABLED - no actaion</li>
 * 
 * Note, the UPDATE mode is the default mode. It can be deactivated with the
 * option 'DISABLED':
 * <p>
 * 
 * <pre>
  {@code
   <imixs-ai name="DISABLE">
      <debug>false</debug>  
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
public class RAGIndexPlugin extends AbstractPlugin {

	private static final Logger logger = Logger.getLogger(RAGIndexPlugin.class.getName());

	@Inject
	private WorkflowService workflowService;

	@Inject
	private EventLogService eventLogService;

	@Inject
	protected ImixsAIPromptService imixsAIPromptService;

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
				IndexService.RAG_INDEX, workitem, false);
		List<ItemCollection> ragDisabledDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-ai",
				IndexService.RAG_DISABLED, workitem, false);
		List<ItemCollection> ragDeleteDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-ai",
				IndexService.RAG_DELETE, workitem, false);
		List<ItemCollection> ragPromptDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-ai",
				IndexService.RAG_PROMPT, workitem, false);

		// disabled?
		if (ragDisabledDefinitions != null && ragDisabledDefinitions.size() > 0) {
			// Plugin is disabled
			return workitem;
		}

		// INDEX Mode?
		if (ragIndexDefinitions != null) {
			for (ItemCollection ragIndexDefinition : ragIndexDefinitions) {
				loadPromptDefinition(ragIndexDefinition, event);
				eventLogService.createEvent(IndexOperator.EVENTLOG_TOPIC_RAG_EVENT_INDEX, workitem.getUniqueID(),
						ragIndexDefinition);
			}
		}

		// PROMPT mode?
		if (ragPromptDefinitions != null) {
			for (ItemCollection ragPromptDefinition : ragPromptDefinitions) {
				loadPromptDefinition(ragPromptDefinition, event);
				eventLogService.createEvent(IndexOperator.EVENTLOG_TOPIC_RAG_EVENT_PROMPT, workitem.getUniqueID(),
						ragPromptDefinition);
			}
		}

		// DELETE mode?
		if (ragDeleteDefinitions != null) {
			for (ItemCollection radDeleteDefinition : ragDeleteDefinitions) {
				eventLogService.createEvent(IndexOperator.EVENTLOG_TOPIC_RAG_EVENT_DELETE, workitem.getUniqueID(),
						radDeleteDefinition);
			}
		}

		// Default: UPDATE meta information
		eventLogService.createEvent(IndexOperator.EVENTLOG_TOPIC_RAG_EVENT_UPDATE, workitem.getUniqueID());
		return workitem;

	}

	/**
	 * This helper method verifies if the indexDefinition contains a prompt
	 * definition. If not the method loads the prompt definition from the dataObject
	 * associated with the event.
	 * 
	 * @param indexDefinition
	 * @return
	 * @throws PluginException
	 */
	public void loadPromptDefinition(ItemCollection indexDefinition, ItemCollection event) throws PluginException {
		// load the prompt template from the index definition!
		String promptDefinition = indexDefinition.getItemValueString("PromptDefinition");
		if (promptDefinition.isBlank()) {
			// try to load template from model....
			promptDefinition = imixsAIPromptService.loadPromptTemplateByModelElement(event);
			indexDefinition.setItemValue("PromptDefinition", promptDefinition);
		}

	}

}
