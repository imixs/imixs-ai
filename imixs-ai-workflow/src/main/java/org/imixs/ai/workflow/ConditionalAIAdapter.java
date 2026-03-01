package org.imixs.ai.workflow;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.imixs.ai.ImixsAIContextHandler;
import org.imixs.ai.api.OpenAIAPIService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.ConditionalExpressionEvent;
import org.imixs.workflow.engine.WorkflowService;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.transaction.TransactionScoped;

/**
 * The ConditionalAIAdapter reacts on CDI Events of type BPMNConditionEvent and
 * evaluates a condition against an LLM
 * <p>
 * The Adapter defines a Default Expression template for LLMs. The BPMN
 * Configuration must only include the user prompt. See the following example:
 * <p>
 * 
 * <pre>
* {@code
<imixs-ai name="CONDITION">
    <debug>true</debug>
    <endpoint><propertyvalue>llm.service.endpoint</propertyvalue></endpoint>
    <result-item>my.condition</result-item>
    <prompt><![CDATA[
       Is Germany an EU member country? ]]>
    </prompt>
</imixs-ai>

* }
* </pre>
 * 
 * Caching: The ConditionalAIAdapter implements a caching mechnaism. The adapter
 * class stores the hash value in to the item <result-item>.hash to avoid
 * duplicate calls against the llm with the same prompt in one processing cycle!
 */
@TransactionScoped
public class ConditionalAIAdapter implements Serializable {

    private static final Logger logger = Logger.getLogger(ConditionalAIAdapter.class.getName());

    // Transaction-scoped cache - survives across multiple eval() calls
    private Map<Long, String> promptResultCache = new HashMap<>();

    @Inject
    protected WorkflowService workflowService;

    @Inject
    protected ImixsAIPromptService imixsAIPromptService;

    @Inject
    protected OpenAIAPIService llmService;

    @Inject
    ImixsAIContextHandler imixsAIContextHandler;

    public static String DEFAULT_EXPRESSION_TEMPLATE = "<imixs-ai name=\"CONDITION\">\n" + //
            "  <debug>true</debug>\n" + //
            "  <endpoint><propertyvalue>llm.service.endpoint</propertyvalue></endpoint>\n" + //
            "  <result-event>BOOLEAN</result-event>\n" + //
            "  <PromptDefinition>\n" + //
            "    <prompt_options>{\"n_predict\": 16, \"temperature\": 0 }</prompt_options>\n" + //
            "    <prompt role=\"system\"><![CDATA[\n" + //
            "       Evaluate the following condition to 'true' or 'false'. Do only answer with 'true' or 'false'. Do not add any additional context into your answer.]]>\n"
            + //
            "    </prompt>\n" + //
            "  </PromptDefinition>\n" + //
            "</imixs-ai>";

    /**
     * Test boolean condition method
     * 
     * @param conditionEvent
     * @throws PluginException
     * @throws AdapterException
     */
    public void onConditionEvent(@Observes ConditionalExpressionEvent conditionalEvent)
            throws PluginException, AdapterException {

        // run only if we have a prompt definition
        String condition = conditionalEvent.getCondition();
        if (condition != null && condition.contains("<imixs-ai")) {
            long l = System.currentTimeMillis();
            List<ItemCollection> llmConditionDefinitions;
            boolean llmAPIDebug = false;

            condition = condition.trim();
            llmConditionDefinitions = workflowService.evalXMLExpressionList(
                    conditionalEvent.getCondition(), "imixs-ai", "CONDITION", conditionalEvent.getWorkitem(), true);

            for (ItemCollection promptDefinition : llmConditionDefinitions) {
                // set DEFAULT_EXPRESSION_TEMPLATE.
                imixsAIContextHandler.setWorkItem(conditionalEvent.getWorkitem());
                imixsAIContextHandler.addPromptDefinition(DEFAULT_EXPRESSION_TEMPLATE);

                String llmAPIEndpoint = imixsAIPromptService.parseEndpointByBPMN(promptDefinition);
                String userPrompt = promptDefinition.getItemValueString("prompt");
                // add the user prompt!
                imixsAIContextHandler.addMessage(ImixsAIContextHandler.ROLE_USER, userPrompt,
                        workflowService.getSessionContext().getCallerPrincipal().getName(), null);

                JsonObject jsonPrompt = imixsAIContextHandler.getOpenAIMessageObject();

                // Skip if the message object contains no messages
                JsonArray messages = jsonPrompt.getJsonArray("messages");
                if (messages == null || messages.isEmpty()) {
                    logger.fine("├── ⚠ skipping empty prompt - no messages defined");
                    conditionalEvent.setCondition("false");
                    continue;
                }
                // Skip if this is a cached condition event:
                long hashPrompt = jsonPrompt.hashCode();
                if (promptResultCache.containsKey(hashPrompt)) {
                    // jsonPrompt (hash) was already stored, we do not call the LLM
                    if (llmAPIDebug) {
                        logger.fine("├── reuse cached result from transaction cache - hash=" + hashPrompt);
                        logger.fine("└── ✓ evaluation time: " + (System.currentTimeMillis() - l) + "ms");
                    }
                    conditionalEvent.setCondition(promptResultCache.get(hashPrompt));
                    return;
                }

                if ("true".equalsIgnoreCase(promptDefinition.getItemValueString("debug"))) {
                    llmAPIDebug = true;
                }
                if (llmAPIDebug) {
                    logger.info("├── evaluate conditional expression");
                }

                if (llmAPIDebug) {
                    logger.info("│   ├── conditional prompt:");
                    logger.info(jsonPrompt.toString());
                }

                String completionResult = llmService.postPromptCompletion(imixsAIContextHandler, llmAPIEndpoint,
                        llmAPIDebug);
                if (llmAPIDebug) {
                    logger.info("│   ├── ⚙ Completion Request conditional expression...");
                }
                String conditionalExpressionResult = llmService.processPromptResult(completionResult, "BOOLEAN",
                        conditionalEvent.getWorkitem());

                // store the result message
                if (conditionalExpressionResult != null && !conditionalExpressionResult.isBlank()) {
                    // trim result
                    conditionalExpressionResult = conditionalExpressionResult.trim().toLowerCase();

                    if (!"true".equals(conditionalExpressionResult) && !"false".equals(conditionalExpressionResult)) {
                        logger.warning(
                                "│   ├── ⚠ Unexpected conditional expression result: " + conditionalExpressionResult);
                        throw new PluginException(OpenAIAPIService.class.getSimpleName(),
                                OpenAIAPIService.ERROR_PROMPT_INFERENCE,
                                "Conditional expression should result into 'true' or 'false' only");
                    }
                    if (llmAPIDebug) {
                        logger.info("│   ├── ⚐ Conditional expression result: " + conditionalExpressionResult);
                    }
                    boolean booleanExpressionResult = Boolean.parseBoolean(conditionalExpressionResult.trim());
                    // Store in transaction-scoped cache
                    promptResultCache.put(hashPrompt, "" + booleanExpressionResult);
                    conditionalEvent.setCondition("" + booleanExpressionResult);
                    logger.info("└── ✓ evaluation time: " + (System.currentTimeMillis() - l) + "ms");
                }
            }
        }

    }

}
