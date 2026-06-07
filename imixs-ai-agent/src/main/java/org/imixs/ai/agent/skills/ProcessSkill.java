package org.imixs.ai.agent.skills;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents an Imixs Process Document — an organizational unit that groups
 * related BPMN workflow groups and defines user access rights. NOT the same as
 * a BPMN process/pool.
 */
public class ProcessSkill {

    private String name;
    private String description;
    private String processRef;
    private List<ModelSkill> models = new ArrayList<>();

    public ProcessSkill(String name, String description, String processRef) {
        this.name = name;
        this.description = description;
        this.processRef = processRef;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getProcessRef() {
        return processRef;
    }

    public List<ModelSkill> getModels() {
        return models;
    }

    public void addModel(ModelSkill model) {
        models.add(model);
    }
}