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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.imixs.ai.bpmn.skill.EventSkill;
import org.imixs.ai.bpmn.skill.ItemSkill;
import org.imixs.ai.bpmn.skill.ModelSkill;
import org.imixs.ai.bpmn.skill.ProcessSkill;
import org.imixs.ai.bpmn.skill.TaskSkill;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.DocumentEvent;
import org.imixs.workflow.engine.ModelService;
import org.imixs.workflow.exceptions.ModelException;
import org.openbpmn.bpmn.BPMNModel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import jakarta.annotation.Priority;
import jakarta.ejb.Singleton;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

/**
 * The BPMNSkillTreeCache is an application-scoped singleton EJB that builds
 * and caches a Markdown-formatted "skill tree" representation of all BPMN
 * models, for use in the AI BPMN Skill layer (e.g. AIPromptHandlerBPMNSkills).
 * <p>
 * The skill tree describes, per model, workflow group and start task, the
 * available events and form fields, so an LLM-based agent can decide which
 * business process to start and which data to collect.
 * <p>
 * This class builds on top of {@link SharedModelManager} for read-only access
 * to BPMN model meta data. It does not access the ModelManager directly.
 *
 * @see SharedModelManager
 * @author rsoika
 */
@Singleton
public class BPMNSkillTreeCache {

    private static final Logger logger = Logger.getLogger(BPMNSkillTreeCache.class.getName());

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
    SharedModelManager sharedModelManager;

    String skillTree = null;

    /**
     * Default no-arg constructor required by CDI.
     */
    public BPMNSkillTreeCache() {
    }

    /**
     * Test-only constructor allowing to inject pre-built dependencies directly,
     * bypassing CDI. Intended to be used together with a
     * MockWorkflowEnvironment in unit/integration tests.
     *
     * @param modelService       a ModelService instance
     * @param sharedModelManager a pre-configured SharedModelManager instance
     */
    BPMNSkillTreeCache(ModelService modelService, SharedModelManager sharedModelManager) {
        this.modelService = modelService;
        this.sharedModelManager = sharedModelManager;
    }

    /**
     * This method loads the BPMN skill tree if not yet cached. If the cache
     * already contains a bpmn skillTree the method returns the stored version.
     * <p>
     * A client can call reset() to force a rebuild on the next access.
     *
     * @return the cached or freshly built skill tree as Markdown text
     */
    public String getBPMNSkillTree() {

        long l = System.currentTimeMillis();

        if (skillTree != null) {
            return skillTree; // already cached
        }

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

    /**
     * Resets the cached skill tree, forcing a rebuild on the next access.
     */
    public synchronized void reset() {
        skillTree = null;
        logger.info("├── BPMNSkillTreeCache reset - skill tree cache cleared.");
    }

    /**
     * Model uploads/deletions invalidate the cached skill tree.
     *
     * @param documentEvent
     */
    public void onDocumentEvent(@Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) DocumentEvent documentEvent) {

        int eventType = documentEvent.getEventType();
        if (DocumentEvent.ON_DOCUMENT_SAVE == eventType) {
            if (documentEvent.getDocument() != null && "model".equals(documentEvent.getDocument().getType())) {
                logger.info("└── Reset BPMNSkillTreeCache");
                this.reset();
            }
        }
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
     * Iterates over all BPMN Model Groups, their tasks and events, and stores
     * the result in the skill cache.
     * <p>
     * Model and Process/Group level are excluded (together with everything
     * beneath them) if their documentation is blank or explicitly marked with
     * the ignore tag. Task and Event level tolerate blank documentation and are
     * only excluded if explicitly marked with the ignore tag.
     */
    private List<ModelSkill> loadSkills() {
        logger.info("├── loading bpmn skills...");
        List<ModelSkill> modelSkills = new ArrayList<>();

        List<String> workflowGroups = modelService.findAllWorkflowGroups();
        // Group workflow groups by model version
        Map<String, List<String>> groupsByModelVersion = new LinkedHashMap<>();
        for (String group : workflowGroups) {
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

                BPMNModel model = sharedModelManager.getModelManager().getModel(modelVersion);

                String modelDoc = sharedModelManager.getModelManager()
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
                    List<ItemCollection> startTasks = sharedModelManager.findAllStartTasksByGroup(modelVersion,
                            group);
                    if (startTasks.isEmpty()) {
                        continue;
                    }

                    ItemCollection initialTask = startTasks.get(0);
                    int taskId = initialTask.getItemValueInteger("taskid");
                    String taskName = initialTask.getItemValueString("name");

                    String poolDoc = sharedModelManager.getModelManager()
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

                    String taskDescription = initialTask.getItemValueString("documentation");

                    // Explicit opt-out: a task can be hidden from the skill tree via the
                    // ignore tag. Blank documentation on task level is tolerated and does
                    // NOT lead to exclusion by itself.
                    if (isIgnored(taskDescription)) {
                        logger.info("Skipping task '" + taskName + "' (taskid: " + taskId
                                + ") in group '" + group + "' — marked with ignore tag.");
                        continue;
                    }

                    TaskSkill initialTaskSkill = new TaskSkill(taskId, taskName,
                            HtmlStripper.stripHtml(taskDescription));

                    // Load all events for the initial task and add them as EventSkills.
                    List<ItemCollection> taskEvents = sharedModelManager.getModelManager()
                            .findEventsByTask(model, taskId);
                    for (ItemCollection event : taskEvents) {
                        int eventId = event.getItemValueInteger("eventid");
                        String eventName = event.getItemValueString("name");
                        String eventDocumentation = event.getItemValueString("documentation");

                        // Explicit opt-out: technical events not intended for the LLM can be
                        // hidden via the ignore tag. Blank documentation on event level is
                        // tolerated and does NOT lead to exclusion by itself.
                        if (isIgnored(eventDocumentation)) {
                            logger.info("Skipping event '" + eventName + "' (eventid: " + eventId
                                    + ") on task '" + taskName + "' — marked with ignore tag.");
                            continue;
                        }

                        initialTaskSkill.addEvent(
                                new EventSkill(eventId, eventName, HtmlStripper.stripHtml(eventDocumentation)));
                    }

                    // Load Form description...
                    String formXml = sharedModelManager.fetchFormDefinitionByTask(initialTask);
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
     * Renders the skill cache as a Markdown-formatted string for use in the
     * agent system prompt. Events are rendered beneath their task with eventid
     * so the LLM can select the correct one.
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

                // Initial Task
                if (groupSkill.getTasks().size() > 0) {
                    TaskSkill initialTaskSkill = groupSkill.getTasks().get(0);
                    result.append("### Task: ").append(initialTaskSkill.getName()).append("\n");

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
     * @return list of ItemSkill objects, empty if formXml is null/blank/invalid
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
     * Renders a list of ItemSkills as a Markdown snippet for use in the agent
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