package org.imixs.ai.agent.skills;

/**
 * Represents a single input field from a workflow form definition.
 */
public class ItemSkill {

    private final String name;
    private final String type;
    private final String label;
    private final boolean required;

    public ItemSkill(String name, String type, String label, boolean required) {
        this.name = name;
        this.type = type;
        this.label = label;
        this.required = required;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getLabel() {
        return label;
    }

    public boolean isRequired() {
        return required;
    }
}