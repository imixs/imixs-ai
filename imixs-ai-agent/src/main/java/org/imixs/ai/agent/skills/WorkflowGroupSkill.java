package org.imixs.ai.agent.skills;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a BPMN Pool (Workflow Group) within a ModelSkill. A workflow group
 * defines a self-contained process flow within a BPMN model.
 */
public class WorkflowGroupSkill {

    private String group;
    private String documentation;
    private List<TaskSkill> tasks = new ArrayList<>();

    public WorkflowGroupSkill(String group, String documentation) {
        this.group = group;
        this.documentation = documentation;
    }

    public String getGroup() {
        return group;
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