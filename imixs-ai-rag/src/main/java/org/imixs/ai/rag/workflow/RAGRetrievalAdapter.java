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

import org.imixs.ai.rag.index.IndexService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.SignalAdapter;
import org.imixs.workflow.engine.EventLogService;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.inject.Inject;

/**
 * The RAGAdapter is used for Retrieval-Augmented Generation workflows.
 * <p>
 * The adapter operates in tue so called 'RETRIEVAL' mode.
 * <p>
 * In the 'RETRIEVAL' mode, the adapter evaluates an associated imixs-ai prompt
 * definition to get retrieval embeddings from the RAG database. The parameters
 * models and tasks are optional.
 * 
 * <pre>
 * {@code
<imixs-ai name="RETRIEVAL">
  <endpoint><propertyvalue>llm.service.endpoint</propertyvalue></endpoint>
  <reference-item>customer.ref</reference-item>
  <max-results>3</max-results>
  <models>customer*, rechnung-1.0</models>
  <tasks>1400, 1410, 1420, 1000:1300</tasks>
  <debug>true</debug>

</imixs-ai>
 * }
 * </pre>
 * 
 * The Endpoint defines the Rest API endpoint of the llama-cpp http server or
 * any compatible OpenAI / Open API rest service endpoint.
 * <p>
 * The Prompt Template is based on the concepts of Imixs-AI:
 * 
 * <pre>
 * {@code
<?xml version="1.0" encoding="UTF-8"?>
<IndexDefinition>
    < index_options>{"n_predict": 4096, "temperature": 0}< /index_options>
    <![CDATA[<text><itemvalue>$workflowgroup</itemvalue>: <itemvalue>$workflowstatus</itemvalue>
        # Summary
        <itemvalue>$workflowsummary</itemvalue>
        <itemvalue>offer.summary</itemvalue>
    ]]></text>
</IndexDefinition> 
 * }
 * </pre>
 * 
 * The index Process is
 * 
 * @author Ralph Soika
 * @version 1.0
 *
 */
public class RAGRetrievalAdapter implements SignalAdapter {

    private static Logger logger = Logger.getLogger(RAGRetrievalAdapter.class.getName());

    @Inject
    private WorkflowService workflowService;

    @Inject
    EventLogService eventLogService;

    @Inject
    private IndexService ragService;

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
     * This method parses the LLM Event definitions.
     * 
     * For each PROMPT the method posts a context data (e.g text from an attachment)
     * to the Imixs-AI Analyse service endpoint
     * 
     * @throws PluginException
     */
    public ItemCollection execute(ItemCollection workitem, ItemCollection event)
            throws AdapterException, PluginException {

        logger.finest("...running rag adapter...");

        // read optional configuration form the model or imixs.properties....

        List<ItemCollection> ragRetrievalDefinitions = workflowService.evalWorkflowResultXML(event, "imixs-rag",
                IndexService.RAG_RETRIEVAL, workitem, false);

        // verify if we have an RETRIEVAL configuration
        if (ragRetrievalDefinitions != null && ragRetrievalDefinitions.size() > 0) {
            ragService.createRetrieval(ragRetrievalDefinitions, workitem, event);
        }

        return workitem;
    }

}
