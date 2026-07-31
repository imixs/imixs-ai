package org.imixs.ai.bpmn.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a BPMN Pool (Workflow Group) within a ModelSkill. A workflow group
 * defines a self-contained process flow within a BPMN model.
 */
public class ProcessSkill {

    private String name;
    private String documentation;
    private List<TaskSkill> tasks = new ArrayList<>();

    public ProcessSkill(String name, String documentation) {
        this.name = name;
        this.documentation = documentation;
    }

    public String getName() {
        return name;
    }

    public String getDocumentation() {
        return documentation;
    }

    public List<TaskSkill> getTasks() {
        return tasks;
    }

    public void addTask(TaskSkill task) {
        tasks.add(task);
    }
}