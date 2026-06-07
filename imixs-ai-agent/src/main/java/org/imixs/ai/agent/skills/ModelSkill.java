package org.imixs.ai.agent.skills;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a deployed BPMN model version within a ProcessSkill.
 */
public class ModelSkill {

    private String modelVersion;
    private String documentation;
    private List<WorkflowGroupSkill> groups = new ArrayList<>();

    public ModelSkill(String modelVersion, String documentation) {
        this.modelVersion = modelVersion;
        this.documentation = documentation;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public String getDocumentation() {
        return documentation;
    }

    public List<WorkflowGroupSkill> getGroups() {
        return groups;
    }

    public void addGroup(WorkflowGroupSkill group) {
        groups.add(group);
    }
}