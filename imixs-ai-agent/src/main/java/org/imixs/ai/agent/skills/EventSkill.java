package org.imixs.ai.agent.skills;

/**
 * Represents a BPMN Event within a TaskSkill. For the agent, events define the
 * concrete action that can be triggered on a task — including the initial event
 * used to start a new process instance.
 *
 * The documentation of an event gives the LLM the semantic context to select
 * the correct event when multiple events are available on the same task.
 */
public class EventSkill {

    private int eventId;
    private String name;
    private String documentation;

    public EventSkill(int eventId, String name, String documentation) {
        this.eventId = eventId;
        this.name = name;
        this.documentation = documentation;
    }

    public int getEventId() {
        return eventId;
    }

    public String getName() {
        return name;
    }

    public String getDocumentation() {
        return documentation;
    }
}