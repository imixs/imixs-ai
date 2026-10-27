package org.imixs.ai.rag.index;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.ai.api.LLMConfigService;
import org.imixs.ai.api.LLMOptions;
import org.imixs.ai.rag.cluster.ClusterService;
import org.imixs.ai.rag.metrics.MetricService;
import org.imixs.ai.rag.util.LogController;
import org.imixs.workflow.ItemCollection;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Backing bean for the dashboard page (index.xhtml). For now it only provides
 * some static information. Later it will show the index status and allow test
 * retrievals.
 */
@Named
@RequestScoped
public class DashboardController {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Logger logger = Logger.getLogger(DashboardController.class.getName());

    @Inject
    @ConfigProperty(name = IndexScheduler.ENV_WORKFLOW_SERVICE_ENDPOINT)
    Optional<String> instanceEndpoint;

    @Inject
    @ConfigProperty(name = "health.endpoint", defaultValue = "http://localhost:9990/health")
    String healthEndpoint;

    @Inject
    @ConfigProperty(name = "metrics.endpoint", defaultValue = "http://localhost:9990/metrics")
    String metricsEndpoint;

    // timeout interval in ms
    @Inject
    @ConfigProperty(name = IndexScheduler.ENV_WORKFLOW_SYNC_INTERVAL, defaultValue = "1000")
    long interval;

    @Inject
    @ConfigProperty(name = ClusterService.ENV_EMBEDDINGS_CLUSTER_CONTACTPOINTS)
    Optional<String> contactPoint;

    @Inject
    @ConfigProperty(name = ClusterService.ENV_EMBEDDINGS_CLUSTER_KEYSPACE)
    Optional<String> keySpace;

    @Inject
    @ConfigProperty(name = ClusterService.ENV_EMBEDDINGS_CLUSTER_REPLICATION_FACTOR, defaultValue = "1")
    String repFactor;

    @Inject
    @ConfigProperty(name = ClusterService.ENV_EMBEDDINGS_CLUSTER_REPLICATION_CLASS, defaultValue = "SimpleStrategy")
    String repClass;

    @Inject
    @ConfigProperty(name = IndexScheduler.ENV_WORKFLOW_SERVICE_ENDPOINT)
    Optional<String> workflowServiceEndpoint;

    @Inject
    ClusterService clusterService;

    @Inject
    IndexScheduler indexService;

    @Inject
    MetricService metricService;

    @Inject
    LogController logController;

    @Inject
    private RAGService ragService;

    @Inject
    private LLMConfigService llmConfigService;

    // Result of the last retrieval (items: id, similarity, contentChunk)
    private List<ItemCollection> results = new ArrayList<>();

    // Retrieval
    private String embeddingsEndpoint;
    private String prompt;
    private String workflowGroups;
    private String tasks;
    private String category;
    private int maxResults = 10;

    /**
     * @return the name of the service
     */
    public String getServiceName() {
        return "Imixs-AI RAG Index Service";
    }

    /**
     * @return the current server time as formatted string
     */
    public String getServerTime() {
        return LocalDateTime.now().format(FORMATTER);
    }

    public String getInstanceEndpoint() {
        return instanceEndpoint.orElse("");
    }

    public boolean isConnected() {
        return clusterService.isConnected();
    }

    public long getInterval() {
        return interval;
    }

    public String getContactPoints() {
        return contactPoint.get();
    }

    public String getKeySpace() {
        return keySpace.get();
    }

    public String getReplicationFactor() {
        return repFactor;

    }

    public String getReplicationClass() {
        return repClass;
    }

    public String getError() {
        return clusterService.getError();
    }

    public String getEmbeddingsEndpoint() {
        return embeddingsEndpoint;
    }

    public void setEmbeddingsEndpoint(String embeddingEdpoint) {
        this.embeddingsEndpoint = embeddingEdpoint;
    }

    public String getWorkflowGroups() {
        return workflowGroups;
    }

    public void setWorkflowGroups(String modelGroups) {
        this.workflowGroups = modelGroups;
    }

    public String getTasks() {
        return tasks;
    }

    public void setTasks(String tasks) {
        this.tasks = tasks;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public void prompt() {
        logger.info("Prompt : " + getPrompt());
    }

    /**
     * This is a debug method to test prompts directly against the RAG Index. Used
     * by `retrieval.xhtml`
     */
    public void retrieval() {
        results = new ArrayList<>();
        logger.info("├── 🔃 starting retrieval...");
        logger.info("│   ├── Endpoint: " + getEmbeddingsEndpoint());
        logger.info("│   ├── Prompt: " + getPrompt());
        try {
            String endpoint = getEmbeddingsEndpoint();
            LLMOptions options = llmConfigService.getOptions(endpoint);
            // blank filter fields in the form mean 'no filter' (null)
            results = ragService.retrieval(getPrompt(), endpoint, options, getMaxResults(),
                    blankToNull(getCategory()), blankToNull(getWorkflowGroups()), blankToNull(getTasks()),
                    false);
        } catch (Exception e) {
            logController.warning(IndexScheduler.TOPIC_RETRIEVAL, "retrieval failed: " + e.getMessage());
        }
    }

    public List<ItemCollection> getResults() {
        return results;
    }

    /**
     * Returns the REST API endpoint for a given uniqueID pointing to the remote
     * workflow instance.
     * 
     * @param uniqueid
     * @return
     */
    public String getWorkitemLink(String uniqueid) {
        String apiEndpoint = workflowServiceEndpoint.orElse("");
        return apiEndpoint + "/documents/" + uniqueid;

    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * Starts the timer service
     */
    public void start() {
        try {
            indexService.startScheduler(true);
        } catch (IndexException e) {

            logController.warning(IndexScheduler.TOPIC_INDEX, e.getMessage());
        }
    }

    /**
     * Stop the timer service
     */
    public void stop() {
        try {
            indexService.stopScheduler();
        } catch (IndexException e) {
            logController.warning(IndexScheduler.TOPIC_INDEX, e.getMessage());
        }
    }

    /**
     * This method returns the current event processing counter
     *
     * @return
     */
    public long getCounterByName(String name) {
        return metricService.getCounterByName(name);
    }

    /**
     * This method returns the current value of a registered gauge metric by name
     *
     * @return
     */
    public long getGaugeByName(String name) {
        return metricService.getGaugeByName(name);
    }
}
