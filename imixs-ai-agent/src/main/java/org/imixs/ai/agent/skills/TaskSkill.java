package org.imixs.ai.agent.skills;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a concrete BPMN Task within a WorkflowGroupSkill. For startable
 * processes this is always the initial task. A task can carry multiple events
 * that define the concrete actions available — the LLM uses the event
 * documentation to select the most appropriate one.
 */
public class TaskSkill {

    private int taskId;
    private String name;
    private String documentation;
    private List<EventSkill> events = new ArrayList<>();
    private List<ItemSkill> items = new ArrayList<>();

    public TaskSkill(int taskId, String name, String documentation) {
        this.taskId = taskId;
        this.name = name;
        this.documentation = documentation;
    }

    public int getTaskId() {
        return taskId;
    }

    public String getName() {
        return name;
    }

    public String getDocumentation() {
        return documentation;
    }

    public List<EventSkill> getEvents() {
        return events;
    }

    public void addEvent(EventSkill event) {
        events.add(event);
    }

    public List<ItemSkill> getItems() {
        return items;
    }

    public void setItems(List<ItemSkill> items) {
        this.items = items;
    }
}