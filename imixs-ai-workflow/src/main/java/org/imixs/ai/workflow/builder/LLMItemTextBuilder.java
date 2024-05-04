package org.imixs.ai.workflow.builder;

import java.util.logging.Logger;

import org.imixs.ai.workflow.LLMPromptEvent;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * The LLMItemTextBuilder addapts text item values into the
 * prompt template.
 * 
 * The template must provide corresponding Text Adapter Tags e.g.
 * 
 * <itemvalue>$workflowgroup</itemvalue>
 * 
 * @author rsoika
 *
 */
public class LLMItemTextBuilder {

    public static final String API_ERROR = "API_ERROR";
    public static final String PROMPT_CONTEXT = "<<CONTEXT>>";

    private static Logger logger = Logger.getLogger(LLMFileContextBuilder.class.getName());

    @Inject
    private WorkflowService workflowService;

    public void onEvent(@Observes LLMPromptEvent event) {
        if (event.getWorkitem() == null) {
            return;
        }

        String prompt = event.getPromptTemplate();
        // Adapt text!
        try {
            prompt = workflowService.adaptText(prompt, event.getWorkitem());
        } catch (PluginException e) {
            logger.warning("Failed to adapt text to current promtp-template: " + e.getMessage());
        }

        // update the prompt tempalte
        event.setPromptTemplate(prompt);

    }

}
