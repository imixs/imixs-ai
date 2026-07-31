package org.imixs.ai.bpmn.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a deployed BPMN model version within a ProcessSkill.
 */
public class ModelSkill {

    private String modelVersion;
    private String documentation;
    private List<ProcessSkill> groups = new ArrayList<>();

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

    public List<ProcessSkill> getGroups() {
        return groups;
    }

    public void addGroup(ProcessSkill group) {
        groups.add(group);
    }
}