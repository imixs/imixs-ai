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
import org.imixs.ai.rag.RetrievalService;
import org.imixs.ai.workflow.ImixsAIPromptService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.EventLogService;
import org.imixs.workflow.engine.plugins.AbstractPlugin;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.inject.Inject;

/**
 * The RAGIndexPlugin maintains the RAG index of a workitem and executes
 * retrievals. Embeddings are stored as vectors by the imixs-workflow-index
 * (Apache Lucene).
 * <p>
 * The plugin is controlled by an {@code <imixs-rag>} definition in the
 * following modes:
 * <ul>
 * <li>INDEX - index the text of the workitem in a category</li>
 * <li>RETRIEVAL - find similar documents</li>
 * <li>DELETE - remove all vectors of the workitem</li>
 * </ul>
 * <p>
 * For INDEX the plugin builds the text from the PromptDefinition and creates an
 * EventLog entry. The embeddings are computed asynchronously, so the processing
 * cycle never waits for an LLM call.
 * <p>
 * A RETRIEVAL runs synchronously, so the result is available in the same
 * workflow step. The plugin uses the same implementation as the
 * {@link RAGRetrievalAdapter} - the modeler decides which one to use.
 *
 * <pre>
 * {@code
 * <imixs-rag name="INDEX">
 *    <category>order.summary</category>
 *    <debug>false</debug>
 * </imixs-rag>
 *
 * <imixs-rag name="RETRIEVAL">
 *    <reference-item>similar.orders</reference-item>
 *    <categories>order.summary</categories>
 *    <query>($workflowgroup:"Auftrag")</query>
 *    <max-results>5</max-results>
 * </imixs-rag>
 *
 * <imixs-rag name="DELETE"></imixs-rag>
 * }
 * </pre>
 *
 * @author rsoika
 */
public class RAGIndexPlugin extends AbstractPlugin {

	private static final Logger logger = Logger.getLogger(RAGIndexPlugin.class.getName());

	@Inject
	private EventLogService eventLogService;

	@Inject
	protected ImixsAIPromptService imixsAIPromptService;

	@Inject
	protected EmbeddingService embeddingService;

	@Inject
	protected RetrievalService retrievalService;

	@Override
	public ItemCollection run(ItemCollection workitem, ItemCollection event) throws PluginException {

		// do not run on Snapshots!
		if (workitem.getType().startsWith("snapshot-")) {
			// no op!
			return workitem;
		}

		logger.finest("running RAGPlugin");

		List<ItemCollection> ragIndexDefinitions = this.getWorkflowService().evalWorkflowResultXML(event, "imixs-rag",
				RAGUtil.RAG_INDEX, workitem, false);
		List<ItemCollection> ragRetrievalDefinitions = this.getWorkflowService().evalWorkflowResultXML(event,
				"imixs-rag", RAGUtil.RAG_RETRIEVAL, workitem, false);
		List<ItemCollection> ragDeleteDefinitions = this.getWorkflowService().evalWorkflowResultXML(event, "imixs-rag",
				RAGUtil.RAG_DELETE, workitem, false);

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

		// RETRIEVAL mode? - runs synchronously, the result is stored in the
		// reference-item of each definition
		if (ragRetrievalDefinitions != null && !ragRetrievalDefinitions.isEmpty()) {
			try {
				retrievalService.createRetrieval(ragRetrievalDefinitions, workitem, event);
			} catch (AdapterException e) {
				throw new PluginException(e);
			}
		}

		// DELETE mode?
		if (ragDeleteDefinitions != null) {
			for (ItemCollection ragDeleteDefinition : ragDeleteDefinitions) {
				eventLogService.createEvent(RAGUtil.EVENTLOG_TOPIC_RAG_EVENT_DELETE, workitem.getUniqueID(),
						ragDeleteDefinition);
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
	 * @param event
	 * @throws PluginException
	 */
	public void loadPromptDefinition(ItemCollection indexDefinition, ItemCollection event) throws PluginException {
		// load the prompt template from the index definition!
		String promptDefinition = imixsAIPromptService.loadPromptTemplate(indexDefinition, event);
		// update the prompt-template in the definition object!
		indexDefinition.setItemValue(APIUtil.ITEM_PROMPT_TEMPLATE, promptDefinition);
	}

}