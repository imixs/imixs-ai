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

import org.imixs.ai.api.APIUtil;
import org.imixs.ai.rag.EmbeddingService;
import org.imixs.ai.rag.RAGUtil;
import org.imixs.ai.workflow.ImixsAIPromptService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.EventLogService;
import org.imixs.workflow.engine.plugins.AbstractPlugin;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.inject.Inject;

/**
 * The RAGIndexPlugin automatically index, updates or remove the workflow
 * data as embeddings. Embeddings are stored as vectors by the
 * imixs-workflow-index (Apache Lucene).
 * <p>
 * The plugin can be controlled by an `<imixs-rag>` definition in the following
 * modes.
 * <ul>
 * <li>INDEX - index a workitem</li>
 * <li>PROMPT - send an LLM completion request and index the result</li>
 * <li>DELETE - remove the workitem from the index</li>
 * </ul>
 * <p>
 * The plugin is mainly responsible to load the prompt definition optional from
 * the event. In case the prompt definition is defined only by a BPMN Data
 * object, the plugin stores the prompt template into definition.
 * After resolving the prompt definition the plugin generates a event log entry
 * which is processed assynchounsly by the remote RAG Service.
 * 
 * <pre>
  {@code
   <imixs-rag name="INDEX">
      <category>summary</category>
      <debug>false</debug>  
   </imixs-rag>

	<imixs-rag name="RETRIEVAL">
      <category>risk</category>
	  <query>($workflowgroup:"Claim") AND (type:workitem)</query>
	  <max-results>5</max-results>
      <debug>false</debug>  
   </imixs-rag>
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
	private EventLogService eventLogService;

	@Inject
	protected ImixsAIPromptService imixsAIPromptService;

	@Inject
	protected EmbeddingService embeddingService;

	@Override
	public ItemCollection run(ItemCollection workitem, ItemCollection event) throws PluginException {

		// do not run on Snapshots!
		if (workitem.getType().startsWith("snapshot-")) {
			// no op!
			return workitem;
		}

		logger.finest("running RAGPlugin");

		// read optional configuration form the model or imixs.properties....
		List<ItemCollection> ragIndexDefinitions = this.getWorkflowService().evalWorkflowResultXML(event, "imixs-rag",
				RAGUtil.RAG_INDEX, workitem, false);
		List<ItemCollection> ragDeleteDefinitions = this.getWorkflowService().evalWorkflowResultXML(event, "imixs-rag",
				RAGUtil.RAG_DELETE, workitem, false);
		List<ItemCollection> ragRetrievalDefinitions = this.getWorkflowService().evalWorkflowResultXML(event,
				"imixs-rag",
				RAGUtil.RAG_RETRIEVAL, workitem, false);

		// INDEX Mode?
		if (ragIndexDefinitions != null) {
			for (ItemCollection ragIndexDefinition : ragIndexDefinitions) {
				loadPromptDefinition(ragIndexDefinition, event);
				try {
					embeddingService.indexWorkitem(ragIndexDefinition, workitem);
				} catch (AdapterException e) {
					throw new PluginException(e);
				}
			}
		}

		// RETRIEVAL mode?
		if (ragRetrievalDefinitions != null) {
			for (ItemCollection ragPromptDefinition : ragRetrievalDefinitions) {
				loadPromptDefinition(ragPromptDefinition, event);
				eventLogService.createEvent(RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_RETRIEVAL, workitem.getUniqueID(),
						ragPromptDefinition);
			}
		}

		// DELETE mode?
		if (ragDeleteDefinitions != null) {
			for (ItemCollection radDeleteDefinition : ragDeleteDefinitions) {
				eventLogService.createEvent(RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_DELETE, workitem.getUniqueID(),
						radDeleteDefinition);
			}
		}

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
		String promptDefinition = imixsAIPromptService.loadPromptTemplate(indexDefinition, event);
		// update the prompt-template in the definition object!
		indexDefinition.setItemValue(APIUtil.ITEM_PROMPT_TEMPLATE, promptDefinition);

	}

}
