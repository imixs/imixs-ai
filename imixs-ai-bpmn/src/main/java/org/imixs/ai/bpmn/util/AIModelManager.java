/*******************************************************************************
 *  Imixs Workflow
 *  Copyright (C) 2001, 2011 Imixs Software Solutions GmbH,
 *  http://www.imixs.com
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  General Public License for more details.
 *
 *  You can receive a copy of the GNU General Public
 *  License at http://www.gnu.org/licenses/gpl.html
 *
 *  Project:
 *  	http://www.imixs.org
 *  	http://java.net/projects/imixs-workflow
 *
 *  Contributors:
 *  	Imixs Software Solutions GmbH - initial API and implementation
 *  	Ralph Soika - Software Developer
 *******************************************************************************/
package org.imixs.ai.bpmn.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.ModelManager;
import org.imixs.workflow.engine.DocumentEvent;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.InvalidAccessException;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.faces.data.WorkflowController;
import org.openbpmn.bpmn.BPMNModel;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.ejb.Singleton;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

/**
 * The AIModelManager is an application-scoped singleton EJB that holds a
 * shared instance of the {@link ModelManager} POJO for read-only access to
 * BPMN model meta data (e.g. by AI BPMN Skill layer or Prompt Handlers).
 * <p>
 * In contrast to the ModelManager instances used by the WorkflowKernel during
 * processing - which are always session or request-local - this shared
 * instance is intentionally global and serves as a cache for model meta data
 * in the frontend.
 * <p>
 * The internal ModelManager can be reset at any time (e.g. after a model
 * upload or deletion) without affecting any ongoing workflow processing,
 * since the WorkflowKernel always holds its own local ModelManager instance
 * during a transaction.
 * <p>
 * This class is intentionally kept free of any consumer-specific logic (e.g.
 * skill tree building). Consumers that need to derive richer structures from
 * the model should build on top of this class instead of extending it.
 *
 * @see BPMNSkillTreeCache
 * @see ModelManager
 * @author rsoika
 */
@Singleton
public class AIModelManager {

    private static final Logger logger = Logger.getLogger(AIModelManager.class.getName());

    @Inject
    WorkflowService workflowService;

    // The shared ModelManager instance - volatile to ensure visibility across
    // threads
    private volatile ModelManager modelManager;

    // Collect model warnings for frontend display
    private final Set<String> modelWarnings = Collections.synchronizedSet(new LinkedHashSet<>());

    /**
     * Test-only constructor allowing to inject a pre-built ModelManager
     * directly, bypassing CDI and the @PostConstruct lifecycle. Intended to be
     * used together with a MockWorkflowEnvironment in unit/integration tests.
     *
     * @param modelManager a pre-configured ModelManager instance (e.g. from
     *                     MockWorkflowEnvironment.getModelManager())
     */
    public AIModelManager(ModelManager modelManager) {
        this.modelManager = modelManager;
    }

    /**
     * Initializes the shared ModelManager instance on startup.
     */
    @PostConstruct
    public void init() {
        modelManager = new ModelManager(workflowService);
        logger.info("├── SharedModelManager initialized.");
    }

    /**
     * Default no-arg constructor required by CDI.
     */
    public AIModelManager() {
    }

    /**
     * In case no WorkflowController was used we observe also the before Save
     * event
     *
     * @param documentEvent
     * @throws PluginException
     */
    public void onDocumentEvent(@Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) DocumentEvent documentEvent)
            throws PluginException {

        int eventType = documentEvent.getEventType();
        // before the workitem is saved we update the field txtOrderItems
        if (DocumentEvent.ON_DOCUMENT_SAVE == eventType) {
            if (documentEvent.getDocument() != null && "model".equals(documentEvent.getDocument().getType())) {
                logger.info("└── Reset SharedModelManager");
                this.reset();
            }
        }
    }

    /**
     * Returns the shared ModelManager instance.
     * <p>
     * Note: this instance is shared across all active user sessions. It is
     * suitable for read-only UI operations (e.g. resolving model groups, start
     * tasks, process descriptions). It must not be used inside workflow
     * processing transactions.
     *
     * @return the shared {@link ModelManager} instance
     */
    public ModelManager getModelManager() {
        return modelManager;
    }

    /**
     * Resets the shared ModelManager by creating a new instance.
     * <p>
     * This clears all internal caches (modelStore, bpmnEntityCache,
     * bpmnElementCache, groupCache) and forces a fresh reload of model data on
     * the next access.
     * <p>
     * This method should be called after a model upload or deletion to ensure
     * all active user sessions see the updated model data immediately.
     * <p>
     * Ongoing workflow processing is not affected since the WorkflowKernel
     * holds its own local ModelManager instance.
     */
    public synchronized void reset() {
        modelManager = new ModelManager(workflowService);
        modelWarnings.clear();
        logger.info("├── SharedModelManager reset - all model caches cleared.");
    }

    public void addModelWarning(String message) {
        modelWarnings.add(message);
    }

    public Set<String> getModelWarnings() {
        return modelWarnings;
    }

    /**
     * Returns the documentation of a process entity identified by its process ID
     * and model version. Dynamic text replacement is applied using the given
     * document context.
     *
     * @param processid       - the process ID
     * @param modelversion    - the model version
     * @param documentContext - the workitem used for text replacement
     * @return resolved description string, or empty string if not found
     */
    public String getProcessDescription(int processid, String modelversion, ItemCollection documentContext) {
        ItemCollection pe = null;
        try {
            BPMNModel model = modelManager.getModel(modelversion);
            pe = modelManager.findTaskByID(model, processid);
        } catch (ModelException | InvalidAccessException e1) {
            logger.warning("Unable to load task " + processid + " in model version '" + modelversion + "' - "
                    + e1.getMessage());
        }
        if (pe == null) {
            return "";
        }
        String desc = pe.getItemValueString(org.imixs.workflow.bpmn.BPMNUtil.TASK_ITEM_DOCUMENTATION);
        try {
            desc = workflowService.adaptText(desc, documentContext);
        } catch (PluginException e) {
            logger.warning("Unable to update processDescription: " + e.getMessage());
        }
        return desc;
    }

    /**
     * Returns a list of all valid Imixs Start Tasks for a given workflow group.
     * <p>
     * The method validates the structure of each start task. A task with an
     * unexpected type is logged as a warning and excluded from the result.
     *
     * @param version - the model version
     * @param group   - the workflow group name
     * @return list of valid start task entities
     */
    public List<ItemCollection> findAllStartTasksByGroup(String version, String group) {
        List<ItemCollection> result = new ArrayList<>();
        try {
            BPMNModel model = modelManager.getModel(version);
            List<ItemCollection> _result = modelManager.findStartTasks(model, group);

            // Validate each start task - type is a mandatory field
            for (ItemCollection task : _result) {
                String type = task.getItemValueString("txttype");
                if (!type.isEmpty() && !WorkflowController.DEFAULT_TYPE.equals(type)) {
                    String msg = "Invalid initial task in model='" + version + "' workflowGroup='"
                            + group + "' task=" + task.getItemValueString("numProcessID")
                            + " wrong type='" + type + "' -> expected: '" + WorkflowController.DEFAULT_TYPE + "'";
                    logger.warning(msg);
                    addModelWarning(msg);
                    continue;
                }
                result.add(task);
            }
        } catch (ModelException e) {
            logger.severe(
                    "Failed to find start tasks for workflow group '" + group + "' : " + e.getMessage());
        }
        return result;
    }

    /**
     * Returns a BPMN form definition associated with a given task ItemCollection.
     * <p>
     * The form definition is read from an optional <code>bpmn:DataObject</code>
     * associated with the current task element. A <code>bpmn:DataObject</code>
     * must contain a `form-tag` containing the form definition. If no matching
     * <code>bpmn:DataObject</code> is defined the method returns an empty
     * string.
     *
     * @param task
     * @return
     */
    @SuppressWarnings("unchecked")
    public String fetchFormDefinitionByTask(ItemCollection task) {

        if (task == null) {
            return "";
        }

        List<List<String>> dataObjects = task.getItemValue("dataObjects");
        for (List<String> dataObject : dataObjects) {
            // there can be more than one dataObject attached.
            // We need the one with the tag <imixs-form>
            String templateName = dataObject.get(0);
            String content = dataObject.get(1);
            // we expect that the content contains at least one occurrence of
            // <imixs-form>
            if (content.contains("<imixs-form>")) {
                logger.finest("......DataObject name=" + templateName);
                logger.finest("......DataObject content=" + content);
                return content;
            } else {
                // seems not to be a imixs-form definition!
            }
        }
        // nothing found!
        return "";
    }
}