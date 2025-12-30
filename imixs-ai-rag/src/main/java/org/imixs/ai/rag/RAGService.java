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
import java.util.stream.Collectors;

import org.imixs.ai.rag.cluster.ClusterException;
import org.imixs.ai.rag.cluster.ClusterService;
import org.imixs.ai.rag.cluster.RetrievalResult;
import org.imixs.ai.rag.events.RAGEventService;
import org.imixs.ai.workflow.ImixsAIPromptService;
import org.imixs.ai.workflow.OpenAIAPIService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.DocumentEvent;
import org.imixs.workflow.engine.EventLogService;
import org.imixs.workflow.exceptions.AccessDeniedException;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RunAs;
import jakarta.ejb.Stateless;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * The RAGService provides methods to index, update or delete or retrieval data
 * from the vector database.
 * <p>
 * The service reacts on Document Delete events and automatically removes an
 * existing index. The deletion is handled by the RAGEventService
 * 
 * @see RAGEventService
 * @see DocumentEvent
 * @version 1.0
 * @author rsoika
 *
 */
@DeclareRoles({ "org.imixs.ACCESSLEVEL.MANAGERACCESS" })
@RunAs("org.imixs.ACCESSLEVEL.MANAGERACCESS")
@Stateless
public class RAGService {

    private static Logger logger = Logger.getLogger(RAGService.class.getName());

    public static final String API_ERROR = "API_ERROR";

    public static final String RAG_INDEX = "INDEX";
    public static final String RAG_UPDATE = "UPDATE";
    public static final String RAG_DELETE = "DELETE";
    public static final String RAG_RETRIEVAL = "RETRIEVAL";
    public static final String RAG_DISABLED = "DISABLED";

    @Inject
    private ClusterService clusterService;

    @Inject
    private EventLogService eventLogService;

    @Inject
    private OpenAIAPIService llmService;

    @Inject
    protected ImixsAIPromptService imixsAIPromptService;

    /**
     * Creates a RAG Index for a Workitem based on IndexDefinitions
     * 
     * @param llmIndexDefinitions
     * @param workitem
     * @param event
     * @throws PluginException
     * @throws AdapterException
     */
    public void createIndex(List<ItemCollection> llmIndexDefinitions, ItemCollection workitem, ItemCollection event)
            throws PluginException, AdapterException {
        long processingTime = System.currentTimeMillis();
        String llmAPIEndpoint = null;
        boolean llmAPIDebug = false;
        try {
            for (ItemCollection indexDefinition : llmIndexDefinitions) {
                llmAPIEndpoint = imixsAIPromptService.parseLLMEndpointByBPMN(indexDefinition);
                // do we have a valid endpoint?
                if (llmAPIEndpoint == null || llmAPIEndpoint.isEmpty()) {
                    throw new PluginException(RAGRetrievalAdapter.class.getSimpleName(), API_ERROR,
                            "imixs-ai configuration error: no llm service endpoint defined!");
                }
                if ("true".equalsIgnoreCase(indexDefinition.getItemValueString("debug"))) {
                    llmAPIDebug = true;
                }

                logger.info("├── post RAG index request: " + llmAPIEndpoint);

                indexDefinition.setItemValue("debug", llmAPIDebug);
                indexDefinition.setItemValue("endpoint", llmAPIEndpoint);

                // load the prompt template if no template is defined by the index definition!
                String promptTemplate = indexDefinition.getItemValueString("promptTemplate");
                if (promptTemplate.isBlank()) {
                    // try to load template from model....
                    promptTemplate = imixsAIPromptService.loadPromptTemplateByModelElement(event);
                    indexDefinition.setItemValue("promptTemplate", promptTemplate);
                }

                if (llmAPIDebug) {
                    logger.info("│   ├── PromptTemplate: ");
                    logger.info(promptTemplate);
                }

                // Build the prompt template...
                String llmPrompt = llmService.buildPrompt(promptTemplate, workitem);
                if (llmPrompt.isBlank()) {
                    throw new PluginException(
                            RAGRetrievalAdapter.class.getSimpleName(), API_ERROR,
                            "Unable to parse prompt for RAG indexing");
                }
                indexDefinition.setItemValue("prompt", llmPrompt);

                // send eventLog ....
                eventLogService.createEvent(
                        RAGEventService.EVENTLOG_TOPIC_RAG_EVENT_INDEX,
                        workitem.getUniqueID(),
                        indexDefinition);

                if (llmAPIDebug) {
                    logger.info(
                            "├── ✅ Total processing time: " + (System.currentTimeMillis() - processingTime) + "ms");
                }
            }
        } catch (PluginException e) {
            logger.severe("├── ⚠️ Failed to post embeddings: " + e.getMessage());
            throw e;
        }
    }

    /**
     * DocumentEvent listener to delete an existing index. The method sends a RAG
     * delete event. The deletion is handled by the RAGEventService
     */
    public void onDocumentEvent(@Observes DocumentEvent documentEvent) throws AccessDeniedException {

        // do not run on snapshots
        if (documentEvent.getDocument().getType().startsWith("snapshot-")) {
            // no op!
            return;
        }

        if (DocumentEvent.ON_DOCUMENT_DELETE == documentEvent.getEventType()) {
            // do not run on Snapshots!
            eventLogService.createEvent(RAGEventService.EVENTLOG_TOPIC_RAG_EVENT_DELETE,
                    documentEvent.getDocument().getUniqueID());
        }
    }

    /**
     * Creates a RAG retrieval for a WorkItem based on RetrievalDefinitions
     * 
     * @param llmRetrievalDefinitions
     * @param workitem
     * @param event
     * @throws PluginException
     * @throws AdapterException
     */
    public void createRetrieval(List<ItemCollection> llmRetrievalDefinitions, ItemCollection workitem,
            ItemCollection event) throws PluginException, AdapterException {
        long processingTime = System.currentTimeMillis();
        String llmAPIEndpoint = null;
        boolean llmAPIDebug = false;
        try {
            for (ItemCollection indexDefinition : llmRetrievalDefinitions) {
                llmAPIEndpoint = imixsAIPromptService.parseLLMEndpointByBPMN(indexDefinition);
                logger.info("├── post RAG Retrieval request: " + llmAPIEndpoint);
                String itemRef = indexDefinition.getItemValueString("reference-item");
                String modelGroups = indexDefinition.getItemValueString("modelgroups");
                String tasks = indexDefinition.getItemValueString("tasks");
                int maxResults = indexDefinition.getItemValueInteger("max-results");
                if (maxResults <= 0) {
                    // default
                    logger.warning("│   ├── max-results is not set, default=1");
                    maxResults = 1;
                }
                // do we have a valid endpoint?
                if (llmAPIEndpoint == null || llmAPIEndpoint.isEmpty()) {
                    throw new PluginException(RAGRetrievalAdapter.class.getSimpleName(), API_ERROR,
                            "imixs-ai configuration error: no llm service endpoint defined!");
                }
                if (itemRef.isEmpty()) {
                    throw new PluginException(RAGRetrievalAdapter.class.getSimpleName(), API_ERROR,
                            "imixs-ai configuration error: no reference-item defined!");
                }
                if ("true".equalsIgnoreCase(indexDefinition.getItemValueString("debug"))) {
                    llmAPIDebug = true;
                }

                logger.info("│   ├── reference-item: " + itemRef);
                logger.info("│   ├── max-results: " + maxResults);
                logger.info("│   ├── modelgroups: " + modelGroups);
                logger.info("│   ├── tasks: " + tasks);
                String promptTemplate = imixsAIPromptService.loadPromptTemplateByModelElement(event);
                logger.info("│   ├── PromptTemplate: ");
                logger.info(promptTemplate);
                String llmPrompt = llmService.buildPrompt(promptTemplate, workitem);
                if (llmPrompt.isBlank()) {
                    throw new PluginException(
                            RAGRetrievalAdapter.class.getSimpleName(), RAGService.API_ERROR,
                            "Unable to parse prompt for RAG indexing");
                }
                if (llmAPIDebug) {
                    logger.info("│   ├── Total Prompt Length = " + llmPrompt.length());
                    logger.info("│   ├── Prompt: ");
                    logger.info(llmPrompt);
                }

                // retrieve prompt....
                List<Float> embeddings = llmService.postEmbedding(llmPrompt, llmAPIEndpoint, llmAPIDebug);
                if (llmAPIDebug) {
                    logger.info("├── ⇨ " + embeddings.size() + " floats stored in RAG db.");
                }
                // search cassandra
                List<RetrievalResult> retrievalResultList = clusterService.searchEmbeddings(embeddings, maxResults,
                        modelGroups, tasks);
                List<String> listOfIds = retrievalResultList.stream()
                        .map(RetrievalResult::getUniqueId)
                        .collect(Collectors.toList());
                workitem.setItemValue(itemRef, listOfIds);
                if (llmAPIDebug) {
                    logger.info(
                            "├── found " + retrievalResultList.size() + " matches");
                    logger.info(
                            "├── ✅ Total processing time: " + (System.currentTimeMillis() - processingTime) + "ms");
                }
            }
        } catch (PluginException e) {
            logger.severe("├── ⚠️ Failed to post embeddings: " + e.getMessage());
            throw e;
        } catch (ClusterException e) {
            throw new PluginException(RAGRetrievalAdapter.class.getSimpleName(), API_ERROR, e.getMessage(), e);
        }
    }

    /**
     * This method generates a default RAG Prompt Template to index a workitem. The
     * prompt will include: Status, Group, Model Summary Abstract, File Content
     * 
     * @param workitem
     * @return
     */
    // public String createDefaultPromptTemplate(ItemCollection workitem) {
    // String result = "";

    // result = result + workitem.getWorkflowGroup() + ": "
    // + workitem.getItemValueString(WorkflowKernel.WORKFLOWSTATUS) + "\n\n";

    // result = result + workitem.getItemValueString("") + "\n\n";
    // result = result + workitem.getItemValueString("") + "\n\n";

    // String fileContent = "";
    // List<FileData> fileDataList = workitem.getFileData();
    // for (FileData fileData : fileDataList) {
    // String content = (String) fileData.getAttribute("text");
    // if (content != null && !content.isBlank()) {
    // fileContent = fileContent + fileData.getName() + " : \n\n";
    // fileContent = fileContent + content + "\n\n";

    // }
    // }

    // if (!fileContent.isBlank()) {
    // result = result + "Documents: \n\n" + fileContent;
    // }

    // // collect history
    // result = result + "Processing History: \n\n" + fileContent;
    // List<List<?>> history =
    // workitem.getItemValue(HistoryPlugin.ITEM_HISTORY_LOG);
    // // Collections.reverse(history);
    // // do we have real history entries?
    // if (history.size() > 0 && history.get(0) instanceof List) {
    // for (List<?> entries : history) {
    // String entry = "";
    // Date date = (Date) entries.get(0);
    // String message = (String) entries.get(1);
    // String user = (String) entries.get(2);
    // result = result + "Date: " + date + " User: " + user + " : " + message;
    // }
    // }

    // /* collect comments */
    // List<Map<String, List<Object>>> comments =
    // workitem.getItemValue("txtCommentLog");
    // for (Map<String, List<Object>> comment : comments) {

    // ItemCollection itemCol = new ItemCollection(comment);
    // Date date = itemCol.getItemValueDate("datcomment");

    // String message = itemCol.getItemValueString("txtcomment");
    // String user = itemCol.getItemValueString("nameditor");

    // ItemCollection entry = new ItemCollection();
    // entry.replaceItemValue("date", date);
    // entry.replaceItemValue("user", user);
    // entry.replaceItemValue("message", message);
    // entry.replaceItemValue("type", "comment");

    // addChronicleEntry(originChronicleList, entry);
    // }
    // //

    // // <itemvalue
    // // ></itemvalue>: <itemvalue></itemvalue>
    // // <itemvalue></itemvalue>

    // // # Summary
    // // <itemvalue>$workflowabstract</itemvalue>

    // return result;

    // }
}
