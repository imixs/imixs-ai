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

import org.imixs.ai.rag.RAGUtil;
import org.imixs.ai.rag.RetrievalService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.SignalAdapter;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.inject.Inject;

/**
 * The RAGRetrievalAdapter finds documents similar to the current workitem in
 * the RAG index. The retrieval runs synchronously within the processing cycle,
 * so the result is available in the same workflow step.
 * <p>
 * The adapter is configured by one or many RETRIEVAL definitions:
 *
 * <pre>
 * {@code
 * <imixs-rag name="RETRIEVAL">
 *    <reference-item>similar.orders</reference-item>
 *    <categories>order.summary</categories>
 *    <query>($workflowgroup:"Auftrag")</query>
 *    <max-results>5</max-results>
 *    <debug>true</debug>
 * </imixs-rag>
 * }
 * </pre>
 *
 * The query text is defined by a PromptDefinition in a BPMN DataObject
 * associated with the event. The ids of the matching documents are stored in
 * the reference-item. The retrieval runs with the rights of the current user.
 *
 * @see RetrievalService#createRetrieval
 * @author Ralph Soika
 * @version 2.0
 */
public class RAGRetrievalAdapter implements SignalAdapter {

    private static Logger logger = Logger.getLogger(RAGRetrievalAdapter.class.getName());

    @Inject
    private WorkflowService workflowService;

    @Inject
    private RetrievalService retrievalService;

    /**
     * Default Constructor
     */
    public RAGRetrievalAdapter() {
        super();
    }

    /**
     * CDI Constructor to inject WorkflowService
     *
     * @param workflowService
     */
    @Inject
    public RAGRetrievalAdapter(WorkflowService workflowService) {
        super();
        this.workflowService = workflowService;
    }

    public void setWorkflowService(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /**
     * Executes all RETRIEVAL definitions of the current event.
     */
    @Override
    public ItemCollection execute(ItemCollection workitem, ItemCollection event)
            throws AdapterException, PluginException {

        logger.finest("...running rag retrieval adapter...");

        List<ItemCollection> ragRetrievalDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-rag",
                RAGUtil.RAG_RETRIEVAL, workitem, false);

        if (ragRetrievalDefinitions != null && !ragRetrievalDefinitions.isEmpty()) {
            retrievalService.createRetrieval(ragRetrievalDefinitions, workitem, event);
        }

        return workitem;
    }

}