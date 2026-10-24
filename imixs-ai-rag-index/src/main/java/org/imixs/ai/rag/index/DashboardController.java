package org.imixs.ai.rag.index;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.ai.rag.cluster.ClusterService;
import org.imixs.ai.rag.metrics.MetricService;
import org.imixs.ai.rag.util.LogController;

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
    ClusterService clusterService;

    @Inject
    IndexScheduler indexService;

    @Inject
    MetricService metricService;

    @Inject
    LogController logController;

    private String prompt;

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
