package org.imixs.ai.workflow;

public class PromptData {
    private String prompt;
    private String model;

    public String getPrompt() {
        return prompt;
    }

    public PromptData setPrompt(String prompt) {
        this.prompt = prompt;
        return this;
    }

    public String getModel() {
        return model;
    }

    public PromptData setModel(String model) {
        this.model = model;
        return this;
    }

    /**
     * Builds the XML structure
     * 
     * <?xml version="1.0" encoding="UTF-8"?>
     * <PromptData>
     * <model_id></model_id>
     * <prompt>What is the Imixs-Workflow engine?</prompt>
     * 
     * </PromptData>
     * 
     * @return
     */
    public String build() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
        xml = xml + "<PromptData><model_id>" + this.getModel() + "</model_id>";
        xml = xml + "<prompt><![CDATA[" + this.getPrompt() + "]]></prompt></PromptData>";

        return xml;
    }

}
