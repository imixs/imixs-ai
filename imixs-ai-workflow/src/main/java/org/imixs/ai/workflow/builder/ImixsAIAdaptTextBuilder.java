package org.imixs.ai.workflow.builder;

import java.util.logging.Logger;

import org.imixs.ai.workflow.ImixsAIPromptEvent;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * The ImixsAIAdaptTextBuilder adapts text item values into the
 * prompt template.
 * 
 * The template must provide corresponding Text Adapter Tags e.g.
 * 
 * <itemvalue>$workflowgroup</itemvalue>
 * 
 * The supported text adapters are depending on the installation of the
 * Imixs-Worklfow instance.
 * 
 * @see https://www.imixs.org/doc/engine/adapttext.html
 * @author rsoika
 *
 */
public class ImixsAIAdaptTextBuilder {

    private static Logger logger = Logger.getLogger(ImixsAIFileContextBuilder.class.getName());

    @Inject
    private WorkflowService workflowService;

    public void onEvent(@Observes ImixsAIPromptEvent event) {
        if (event.getWorkitem() == null) {
            return;
        }

        String prompt = event.getPromptTemplate();
        // Adapt text!
        try {
            prompt = workflowService.adaptText(prompt, event.getWorkitem());
        } catch (PluginException e) {
            logger.warning("Failed to adapt text to current prompt-template: " + e.getMessage());
        }

        // update the prompt tempalte
        event.setPromptTemplate(prompt);

    }

}
