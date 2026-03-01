package org.imixs.ai.workflow;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.ai.ImixsAIContextHandler;
import org.imixs.ai.api.OpenAIAPIConnector;
import org.imixs.ai.api.OpenAIAPIService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.PluginException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

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

    public static final String ITEM_PROMPT_TEMPLATE = "prompt-template";

    @Inject
    protected WorkflowService workflowService;

    @Inject
    @ConfigProperty(name = OpenAIAPIConnector.ENV_LLM_SERVICE_ENDPOINT)
    Optional<String> serviceEndpoint;

    /**
     * This helper method parses the api completion endpoint either provided by a
     * model definition or a imixs.property or an environment variable.
     * <p>
     * If no api endpoint is defined by the model the adapter uses the default api
     * endpoint.
     * 
     * @param aiWorkflowDefinition - contains the endpoint information
     * @return - endpoint resolved either by the workflow definition or a default
     *         param
     * @throws PluginException
     */
    public String parseEndpointByBPMN(ItemCollection aiWorkflowDefinition) throws PluginException {
        return parseEndpointByBPMN(aiWorkflowDefinition, null);
    }

    /**
     * This helper method parses an specific api endpoint either provided by a model
     * definition or a imixs.property or an environment variable.
     * <p>
     * The parameter 'type' defines the api endpoint type.
     * <p>
     * If no api endpoint is defined by the model the adapter uses the default api
     * endpoint.
     * 
     * @param aiWorkflowDefinition - contains the endpoint information
     * @param type                 - optional specification type (e.g. 'completion'
     *                             or 'embeddings')
     * @return - endpoint resolved either by the workflow definition or a default
     *         param
     * @throws PluginException
     */
    public String parseEndpointByBPMN(ItemCollection aiWorkflowDefinition, String type) throws PluginException {
        String apiEndpoint = null;

        // Test if the model provides a API Endpoint.
        if (aiWorkflowDefinition != null) {
            if (type == null || type.isBlank()) {
                apiEndpoint = aiWorkflowDefinition.getItemValueString("endpoint");
            } else {
                apiEndpoint = aiWorkflowDefinition.getItemValueString("endpoint-" + type);
            }
        }

        // switch to default api endpoint?
        if (apiEndpoint == null || apiEndpoint.isEmpty()) {
            // set default api endpoint if defined
            if (serviceEndpoint.isPresent() && !serviceEndpoint.get().isEmpty()) {
                apiEndpoint = serviceEndpoint.get();
            }
        }

        logger.fine("......api endpoint " + apiEndpoint);

        // adapt text...
        apiEndpoint = workflowService.adaptText(apiEndpoint, null);

        if (!apiEndpoint.endsWith("/")) {
            apiEndpoint = apiEndpoint + "/";
        }

        return apiEndpoint;

    }

    /**
     * Convenient method to load a prompt template either from a promptDefinition or
     * a dataObject associated with a bpmn element.
     * 
     * @param promptDefItemCollection
     * @param bpmnElement
     * @return
     * @throws PluginException
     */
    public String loadPromptTemplate(ItemCollection promptDefItemCollection, ItemCollection bpmnElement)
            throws PluginException {
        String result = null;
        if (promptDefItemCollection != null) {
            result = loadPromptTemplateByDefinition(promptDefItemCollection);
        }
        if (result == null && bpmnElement != null) {
            return loadPromptTemplateByModelElement(bpmnElement);
        }
        return null;
    }

    /**
     * This method returns the prompt template defined in a workflow result
     * 
     * *
     * 
     * <pre>
    * {@code
    <imixs-ai name="PROMPT">
    <debug>true</debug>
    <endpoint>.....</endpoint>
    <prompt-template>
        <PromptDefinition>
            <prompt_options>{}</prompt_options>
            <prompt role="system">... </prompt>
        </PromptDefinition>
    </prompt-template>
    </imixs-ai>
    * }
    * </pre>
     * 
     * 
     * 
     * 
     * @param promptTemplate
     * @param bpmnElement
     * @return the prompt template or null if not defined
     * @throws PluginException
     */
    public String loadPromptTemplateByDefinition(ItemCollection promptTemplate)
            throws PluginException {
        String content = promptTemplate.getItemValueString(ITEM_PROMPT_TEMPLATE);
        if (!content.isBlank()) {
            return content;
        } else {
            return null;
        }
    }

    /**
     * This method returns the prompt template form a BPMN DataObject associated
     * with the current Event or Task object.
     *
     * @param element
     * @return
     * @throws PluginException
     */
    @SuppressWarnings("unchecked")
    public String loadPromptTemplateByModelElement(ItemCollection element) throws PluginException {
        List<List<String>> dataObjects = element.getItemValue("dataObjects");

        if (dataObjects == null || dataObjects.size() == 0) {
            logger.warning("No data object for prompt template found");
        }

        // take the first data object with a prompt definition....
        for (List<String> dataObject : dataObjects) {
            String name = "" + dataObject.get(0);
            String _prompt = "" + dataObject.get(1);
            if (_prompt.contains("<PromptDefinition>")) {
                // validate prompt template
                validatePromptTemplate(_prompt);
                return _prompt;
            }
        }
        return null;
    }

    /**
     * This method validates a given prompt definition. The method is parsing a list
     * of role based prompt tags e.g. `<prompt role='user'>...</prompt>` and the
     * optional tag `<prompt_options>`.
     * 
     * @param _prompt
     */
    public void validatePromptTemplate(String promptTemplate) throws PluginException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc;

            doc = builder.parse(new java.io.ByteArrayInputStream(promptTemplate.getBytes()));

            // validate prompt tags
            NodeList modelNodes = doc.getElementsByTagName("prompt");
            if (modelNodes.getLength() == 0) {
                throw new PluginException(OpenAIAPIAdapter.class.getSimpleName(),
                        OpenAIAPIService.ERROR_PROMPT_TEMPLATE,
                        "Invalid Prompt Template - at least one 'prompt' tag is expected!");
            } else {
                // validate if each prompt tag has a role attribute
                for (int i = 0; i < modelNodes.getLength(); i++) {
                    Node modelNode = modelNodes.item(i);
                    if (!modelNode.hasAttributes()) {
                        logger.warning(
                                "Deprecated prompt template - the  'prompt' should define the attribute 'role'!");
                    } else {
                        Node role = modelNode.getAttributes().getNamedItem("role");
                        if (role == null) {
                            logger.warning(
                                    "Deprecated prompt template - the  'prompt' should define the attribute 'role'!");
                        } else {
                            // validate role
                            String sRole = role.getTextContent();
                            if (!ImixsAIContextHandler.ROLE_SYSTEM.equals(sRole)
                                    && !ImixsAIContextHandler.ROLE_ASSISTANT.equals(sRole)
                                    && !ImixsAIContextHandler.ROLE_USER.equals(sRole)) {
                                logger.warning(
                                        "Invalid prompt template - the  'prompt' attribute 'role' must match one of 'system' | 'user' | 'assistant'!");
                            }
                        }
                    }
                }
            }
        } catch (SAXException | IOException | ParserConfigurationException e) {
            throw new PluginException(OpenAIAPIAdapter.class.getSimpleName(), OpenAIAPIService.ERROR_PROMPT_TEMPLATE,
                    "Invalid Prompt Template: " + e.getMessage(), e);

        }

    }
}
