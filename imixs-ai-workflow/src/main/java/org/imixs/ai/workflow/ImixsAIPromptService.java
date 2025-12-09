package org.imixs.ai.workflow;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

/**
 * The ImixsAIPromptService EJB provides helper methods to parse a Imixs AI
 * Prompt definition
 */
@Stateless
@LocalBean
public class ImixsAIPromptService implements Serializable {

    private static final long serialVersionUID = 1L;

    private static Logger logger = Logger.getLogger(ImixsAIPromptService.class.getName());

    @Inject
    protected WorkflowService workflowService;

    @Inject
    @ConfigProperty(name = OpenAIAPIConnector.ENV_LLM_SERVICE_ENDPOINT)
    Optional<String> serviceEndpoint;

    /**
     * This helper method parses the ml api endpoint either provided by a model
     * definition or a imixs.property or an environment variable.
     * <p>
     * If not api endpoint is defined by the model the adapter uses the default api
     * endpoint.
     * 
     * @param llmPrompt
     * @return
     * @throws PluginException
     */
    public String parseLLMEndpointByBPMN(ItemCollection llmPrompt) throws PluginException {
        boolean debug = logger.isLoggable(Level.FINE);
        String llmAPIEndpoint = null;

        // Test if the model provides a API Endpoint.
        llmAPIEndpoint = null;
        if (llmPrompt != null) {
            llmAPIEndpoint = llmPrompt.getItemValueString("endpoint");
        }

        // switch to default api endpoint?
        if (llmAPIEndpoint == null || llmAPIEndpoint.isEmpty()) {
            // set defautl api endpoint if defined
            if (serviceEndpoint.isPresent() && !serviceEndpoint.get().isEmpty()) {
                llmAPIEndpoint = serviceEndpoint.get();
            }
        }
        if (debug) {
            logger.info("......llm api endpoint " + llmAPIEndpoint);
        }

        // adapt text...
        llmAPIEndpoint = workflowService.adaptText(llmAPIEndpoint, null);

        if (!llmAPIEndpoint.endsWith("/")) {
            llmAPIEndpoint = llmAPIEndpoint + "/";
        }

        return llmAPIEndpoint;

    }

    /**
     * This method returns the prompt template form a BPMN DataObject associated
     * with the current Event or Task object.
     *
     * @param element
     * @return
     */
    @SuppressWarnings("unchecked")
    public String loadPromptTemplateByModelElement(ItemCollection element) {
        List<List<String>> dataObjects = element.getItemValue("dataObjects");

        if (dataObjects == null || dataObjects.size() == 0) {
            logger.warning("No data object for prompt template found");
        }

        // take the first data object with a prompt definition....
        for (List<String> dataObject : dataObjects) {
            String name = "" + dataObject.get(0);
            String _prompt = "" + dataObject.get(1);
            if (_prompt.contains("<PromptDefinition>")) {
                return _prompt;

            }
        }

        return null;
    }
}
