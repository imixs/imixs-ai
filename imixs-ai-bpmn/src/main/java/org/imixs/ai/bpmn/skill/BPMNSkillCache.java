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

package org.imixs.ai.bpmn.skill;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.imixs.ai.bpmn.util.HtmlStripper;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.ModelManager;
import org.imixs.workflow.WorkflowKernel;
import org.imixs.workflow.bpmn.BPMNUtil;
import org.imixs.workflow.engine.DocumentEvent;
import org.imixs.workflow.engine.ModelService;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.InvalidAccessException;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.faces.data.WorkflowController;
import org.openbpmn.bpmn.BPMNModel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.ejb.Singleton;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

/**
 * The BPMNSkillCache is a application-scoped singleton EJB that holds a
 * shared instance of the {@link ModelManager} POJO for use in the AI BPMN Skill
 * layer
 * (e.g. AIPromptHandlerBPMNSkills). Used instead of creating a local
 * ModelManager
 * instance.
 * <p>
 * In contrast to the ModelManager instances used by the WorkflowKernel during
 * processing - which are always session or request-local - this shared instance
 * is intentionally global and serves as a cache for model meta data in the
 * frontend.
 * <p>
 * The internal ModelManager can be reset at any time (e.g. after a model upload
 * or deletion) without affecting any ongoing workflow processing, since the
 * WorkflowKernel always holds its own local ModelManager instance during a
 * transaction.
 *
 * @see ModelController
 * @see ModelManager
 * @author rsoika
 */
@Singleton
public class BPMNSkillCache {

    private static final Logger logger = Logger.getLogger(BPMNSkillCache.class.getName());

    /**
     * Matches an explicit opt-out tag within a Model's, Process/Group's, Task's
     * or Event's documentation, e.g. <skill.ignore/> or <SKILL.IGNORE />.
     * Elements marked with this tag are always excluded from the skill tree,
     * regardless of the level they appear on.
     */
    private static final Pattern SKILL_IGNORE_PATTERN = Pattern.compile("<\\s*skill\\.ignore\\s*/?\\s*>",
            Pattern.CASE_INSENSITIVE);

    @Inject
    ModelService modelService;

    @Inject
    WorkflowService workflowService;

    // The shared ModelManager instance - volatile to ensure visibility across
    // threads
    private volatile ModelManager modelManager;

    // Collect model warnings for frontend display
    private final Set<String> modelWarnings = Collections.synchronizedSet(new LinkedHashSet<>());

    String skillTree = null;

    /**
     * Initializes the shared ModelManager instance on startup.
     */
    @PostConstruct
    public void init() {
        modelManager = new ModelManager(workflowService);
        logger.info("├── SkillModelManager initialized.");
    }

    /**
     * This method loads the BPMN skill tree if not yet cached. If the SkillCache
     * already contains a bpmn skillTree the method returns the stored version
     * <p>
     * A client can call resetBPMNSkillTree to reset the stored version
     * 
     * @param workitem - the current workitem
     * @throws PluginException
     */
    public String getBPMNSkillTree() {

        long l = System.currentTimeMillis();

        if (skillTree != null) {
            return skillTree; // already cached
        }
        // Load the BPMN event definition from the model.);

        // Build the skill snapshot within the active user session context.
        // BPMNSkillController is @SessionScoped and applies ACL filtering
        // automatically — the snapshot reflects exactly what the current user
        // is allowed to start at the time of submission.
        List<ModelSkill> modelSkills = loadSkills();
        skillTree = buildSkillTree(modelSkills);
        logger.info("└── BPMN skill tree written in  " + (System.currentTimeMillis() - l) + "ms "
                + skillTree.length() + " chars)");
        return skillTree;

    }

    // /**
    // * Reset the cached bpmn skill tree
    // */
    // public void resetBPMNSkillTree() {
    // skillTree = null;
    // }

    /**
     * In case no WorkflowController was used we observer also the before Save event
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
                logger.info("└── Reset BPMN Skills");
                // resetBPMNSkillTree();
                this.reset();
            }
        }
    }

    /**
     * Returns the shared ModelManager instance.
     * <p>
     * Note: this instance is shared across all active user sessions. It is suitable
     * for read-only UI operations (e.g. resolving model groups, start tasks,
     * process descriptions). It must not be used inside workflow processing
     * transactions.
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
     * bpmnElementCache, groupCache) and forces a fresh reload of model data on the
     * next access.
     * <p>
     * This method should be called after a model upload or deletion to ensure all
     * active user sessions see the updated model data immediately.
     * <p>
     * Ongoing workflow processing is not affected since the WorkflowKernel holds
     * its own local ModelManager instance.
     */
    public synchronized void reset() {
        modelManager = new ModelManager(workflowService);
        modelWarnings.clear();
        skillTree = null;
        logger.info("├── SkillModelManager reset - all model caches cleared.");
    }

    public void addModelWarning(String message) {
        modelWarnings.add(message);
    }

    public Set<String> getModelWarnings() {
        return modelWarnings;
    }

    /**
     * Returns the documentation of the initial task for a given workflow group,
     * with textblock entries resolved.
     * <p>
     * A dummy workitem is created to resolve the correct textblock context.
     *
     * @param initialTask   - the initial task entity
     * @param modelVersion  - the model version
     * @param workflowGroup - the workflow group name
     * @return resolved description string, or empty string if not found
     */
    private String getProcessDescriptionByInitialTask(ItemCollection initialTask, String modelVersion,
            String workflowGroup) {
        String result = "";
        if (initialTask != null) {
            // Create a dummy workitem to resolve the correct textblock context
            ItemCollection dummy = new ItemCollection();
            dummy.setItemValue(WorkflowKernel.WORKFLOWSTATUS, initialTask.getItemValueString("name"));
            dummy.setItemValue(WorkflowKernel.WORKFLOWGROUP, workflowGroup);
            result = getProcessDescription(initialTask.getItemValueInteger("taskid"), modelVersion, dummy);
        }
        return result;
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
        String desc = pe.getItemValueString(BPMNUtil.TASK_ITEM_DOCUMENTATION);
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
     * 
     * The form definition is read from an optional <code>bpmn:DataObject</code>
     * associated with the current task element. A <code>bpmn:DataObject</code> must
     * contain a `form-tag` containing the form definition. If not matching
     * <code>bpmn:DataObject</code> is defined the method returns an empty string.
     * 
     * @param workitem
     * @return
     */
    @SuppressWarnings("unchecked")
    public String fetchFormDefinitionByTask(ItemCollection task) {

        // return if no modelversion is defined
        if (task == null) {
            return "";
        }

        List<List<String>> dataObjects = task.getItemValue("dataObjects");
        for (List<String> dataObject : dataObjects) {
            // there can be more than one dataOjects be attached.
            // We need the one with the tag <imixs-form>
            String templateName = dataObject.get(0);
            String content = dataObject.get(1);
            // we expect that the content contains at least one occurrence of <imixs-form>
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

    /**
     * Checks whether the given documentation text explicitly opts out of the
     * skill tree via the ignore tag.
     *
     * @param documentation the documentation text to check, may be null
     * @return true if the ignore tag is present
     */
    private boolean isIgnored(String documentation) {
        return documentation != null && SKILL_IGNORE_PATTERN.matcher(documentation).find();
    }

    /**
     * Builds the skill tree from the BPMN models.
     * Iterates over all BPMN Model Groups, their tasks and events, and
     * stores the result in the skillCache.
     * <p>
     * Model and Process/Group level are excluded (together with everything
     * beneath them) if their documentation is blank or explicitly marked with
     * the ignore tag. Task and Event level tolerate blank documentation and are
     * only excluded if explicitly marked with the ignore tag.
     * 
     * @throws ModelException
     */
    private List<ModelSkill> loadSkills() {
        logger.info("├── loading bpmn skills...");
        List<ModelSkill> modelSkills = new ArrayList<>();

        List<String> workflowGroups = modelService.findAllWorkflowGroups();
        // Group workflow groups by model version
        Map<String, List<String>> groupsByModelVersion = new LinkedHashMap<>();
        for (String group : workflowGroups) {
            // String modelVersion = modelController.getVersionByGroup(group);
            String modelVersion;
            try {
                modelVersion = modelService.findVersionByGroup(group);
                if (modelVersion != null) {
                    groupsByModelVersion.computeIfAbsent(modelVersion, k -> new ArrayList<>()).add(group);
                }
            } catch (ModelException e) {
                logger.warning("Model for group '" + group + "' not found: " + e.getMessage());
            }

        }

        for (Map.Entry<String, List<String>> entry : groupsByModelVersion.entrySet()) {

            try {
                String modelVersion = entry.getKey();

                List<String> groups = entry.getValue();

                BPMNModel model;

                model = this.getModelManager().getModel(modelVersion);

                String modelDoc = this.getModelManager()
                        .loadDefinition(model)
                        .getItemValueString("documentation");

                // Cascade rule: without model-level documentation, or if explicitly
                // marked with the ignore tag, the whole branch beneath this model
                // is excluded from the skill tree.
                if (modelDoc == null || modelDoc.isBlank() || isIgnored(modelDoc)) {
                    logger.info("│   ├── skip model '" + modelVersion
                            + "' — no documentation or marked with ignore tag.");
                    continue;
                }
                logger.info("│   ├── adding model " + modelVersion);

                ModelSkill modelSkill = new ModelSkill(modelVersion, modelDoc);

                for (String group : groups) {
                    List<ItemCollection> startTasks = this.findAllStartTasksByGroup(modelVersion,
                            group);
                    if (startTasks.isEmpty()) {
                        continue;
                    }

                    ItemCollection initialTask = startTasks.get(0);
                    int taskId = initialTask.getItemValueInteger("taskid");
                    String taskName = initialTask.getItemValueString("name");

                    String poolDoc = this.getModelManager()
                            .loadProcess(group, model)
                            .getItemValueString("documentation");

                    // Cascade rule: without process/group-level documentation, or if
                    // explicitly marked with the ignore tag, this workflow group
                    // (and everything beneath it) is excluded from the skill tree.
                    if (poolDoc == null || poolDoc.isBlank() || isIgnored(poolDoc)) {
                        logger.info("Skipping workflow group '" + group + "' in model '"
                                + modelVersion + "' — no documentation or marked with ignore tag.");
                        continue;
                    }

                    String taskDescription = HtmlStripper.stripHtml(
                            this.getProcessDescriptionByInitialTask(
                                    initialTask, modelVersion, group));

                    // Explicit opt-out: a task can be hidden from the skill tree via the
                    // ignore tag. Blank documentation on task level is tolerated and does
                    // NOT lead to exclusion by itself.
                    if (isIgnored(taskDescription)) {
                        logger.info("Skipping task '" + taskName + "' (taskid: " + taskId
                                + ") in group '" + group + "' — marked with ignore tag.");
                        continue;
                    }

                    TaskSkill initialTaskSkill = new TaskSkill(taskId, taskName, taskDescription);

                    // Load all events for the initial task and add them as EventSkills.
                    List<ItemCollection> taskEvents = this.getModelManager()
                            .findEventsByTask(model, taskId);
                    for (ItemCollection eventDoc : taskEvents) {
                        int eventId = eventDoc.getItemValueInteger("eventid");
                        String eventName = eventDoc.getItemValueString("name");
                        String eventDoc2 = HtmlStripper.stripHtml(
                                eventDoc.getItemValueString("documentation"));

                        // Explicit opt-out: technical events not intended for the LLM can be
                        // hidden via the ignore tag. Blank documentation on event level is
                        // tolerated and does NOT lead to exclusion by itself.
                        if (isIgnored(eventDoc2)) {
                            logger.info("Skipping event '" + eventName + "' (eventid: " + eventId
                                    + ") on task '" + taskName + "' — marked with ignore tag.");
                            continue;
                        }

                        initialTaskSkill.addEvent(new EventSkill(eventId, eventName, eventDoc2));
                    }

                    // Load Form description...
                    String formXml = this.fetchFormDefinitionByTask(initialTask);
                    List<ItemSkill> formItems = parseFormFields(formXml);
                    initialTaskSkill.setItems(formItems);

                    ProcessSkill groupSkill = new ProcessSkill(group, poolDoc);
                    groupSkill.addTask(initialTaskSkill);
                    modelSkill.addGroup(groupSkill);
                }

                if (!modelSkill.getGroups().isEmpty()) {

                    modelSkills.add(modelSkill);

                }
            } catch (ModelException e) {
                logger.warning("Failed to compute model : " + e.getMessage());
            }

        }

        return modelSkills;

    }

    /**
     * Renders the skill cache as a Markdown-formatted string for use in the agent
     * system prompt. Events are rendered beneath their task with eventid so the LLM
     * can select the correct one.
     */
    private String buildSkillTree(List<ModelSkill> modelSkills) {
        StringBuilder result = new StringBuilder();

        for (ModelSkill modelSkill : modelSkills) {
            result.append("# Model: ").append(modelSkill.getModelVersion()).append("\n");
            if (!modelSkill.getDocumentation().isBlank()) {
                result.append(modelSkill.getDocumentation()).append("\n");
            }
            result.append("\n");
            // Process List
            for (ProcessSkill groupSkill : modelSkill.getGroups()) {
                result.append("## Process: ").append(groupSkill.getName()).append("\n");
                if (!groupSkill.getDocumentation().isBlank()) {
                    result.append(groupSkill.getDocumentation()).append("\n\n");
                }

                // Inital Task
                if (groupSkill.getTasks().size() > 0) {
                    TaskSkill initialTaskSkill = groupSkill.getTasks().get(0);
                    result.append("### Task: ").append(groupSkill.getName()).append("\n");

                    if (!initialTaskSkill.getDocumentation().isBlank()) {
                        result.append(initialTaskSkill.getDocumentation()).append("\n");
                    }
                    result.append("\n");

                    // Render events beneath the task
                    result.append("**Events:** ").append("\n");
                    for (EventSkill eventSkill : initialTaskSkill.getEvents()) {
                        result.append("  - ").append(eventSkill.getName()).append("\n");
                        if (!eventSkill.getDocumentation().isBlank()) {
                            result.append("    ").append(eventSkill.getDocumentation()).append("\n");
                        }

                        result.append("    (modelversion: ").append(modelSkill.getModelVersion())
                                .append(", taskid: ").append(initialTaskSkill.getTaskId()).append(", eventid: ")
                                .append(eventSkill.getEventId()).append(")\n");
                    }
                    if (!initialTaskSkill.getEvents().isEmpty()) {
                        result.append("\n");
                    }

                    // Render form fields beneath the task
                    String formSection = renderFormFields(initialTaskSkill.getItems());
                    if (!formSection.isBlank()) {
                        result.append(formSection).append("\n");
                    }

                }
            }

        }
        return result.toString();
    }

    /**
     * Parses an imixs-form XML definition and extracts all field descriptors
     * relevant for the agent skill description. Layout information (sections,
     * columns) is intentionally ignored.
     *
     * @param formXml the raw XML string of the form definition, may be null or
     *                blank
     * @return list of FormFieldSkill objects, empty if formXml is
     *         null/blank/invalid
     */
    private List<ItemSkill> parseFormFields(String formXml) {
        List<ItemSkill> fields = new ArrayList<>();

        if (formXml == null || formXml.isBlank()) {
            return fields;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(formXml.getBytes(StandardCharsets.UTF_8)));

            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String name = item.getAttribute("name");
                String type = item.getAttribute("type");
                String label = item.getAttribute("label");
                boolean required = "true".equalsIgnoreCase(item.getAttribute("required"));

                if (!name.isBlank()) {
                    fields.add(new ItemSkill(name, type, label, required));
                }
            }
        } catch (IOException | ParserConfigurationException | SAXException e) {
            logger.warning("Failed to parse form definition: " + e.getMessage());
        }

        return fields;
    }

    /**
     * Renders a list of FormFieldSkills as a Markdown snippet for use in the agent
     * skill description.
     *
     * @param fields the list of form fields to render
     * @return formatted Markdown string, empty string if fields list is empty
     */
    private String renderFormFields(List<ItemSkill> fields) {
        if (fields.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**Form Fields:**\n");
        for (ItemSkill field : fields) {
            sb.append("- `").append(field.getName()).append("`");
            sb.append(" (type=").append(field.getType());
            if (field.isRequired()) {
                sb.append(", required");
            }
            sb.append("): ").append(field.getLabel()).append("\n");
        }
        return sb.toString();
    }
}