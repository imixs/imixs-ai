package org.imixs.ai.rag.cluster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.datastax.oss.driver.api.core.data.CqlVector;

/**
 * Internal builder for constructing vector search queries. Handles parsing of
 * category, modelgroup patterns and task filters, builds the CQL query, and
 * manages the statement parameters.
 */
public class QueryBuilder {

    private final List<String> modelGroups = new ArrayList<>();
    private final List<String> categories = new ArrayList<>();
    private final List<Integer> taskIds = new ArrayList<>();
    private Integer taskRangeStart;
    private Integer taskRangeEnd;
    private final int searchLimit;

    /**
     * Creates a new query builder.
     *
     * @param maxResults  Maximum results requested (used to calculate DB limit)
     * @param category    Optional category filter (null = all categories, "" =
     *                    primary data)
     * @param modelgroups Comma-separated modelgroup patterns (e.g., "Invoice,
     *                    Credit Note")
     * @param tasks       Comma-separated task IDs and/or ranges (e.g., "1400, 1410,
     *                    1000:1300")
     */
    QueryBuilder(int maxResults, String category, String modelgroups, String tasks) throws ClusterException {
        this.searchLimit = Math.min(maxResults * 4, ClusterService.SEARCH_LIMIT_MAX);
        parseCategories(category);
        parseModels(modelgroups);
        parseTasks(tasks);
    }

    /**
     * Builds the CQL query string.
     */
    String buildQuery() {
        StringBuilder query = new StringBuilder();
        query.append("SELECT id, model_group, task_id, content_chunk, ")
                .append("similarity_cosine(content_vector, ?) ")
                .append("FROM document_vectors ");

        List<String> conditions = new ArrayList<>();

        // Category conditions (wie Model Groups!)
        if (!categories.isEmpty()) {
            List<String> categoryConditions = new ArrayList<>();
            for (String cat : categories) {
                categoryConditions.add("category = ?");
            }
            conditions.add("(" + String.join(" OR ", categoryConditions) + ")");
        }

        // Model Group conditions
        if (!modelGroups.isEmpty()) {
            List<String> modelConditions = new ArrayList<>();
            for (String modelgroup : modelGroups) {
                modelConditions.add("model_group = ?");
            }
            conditions.add("(" + String.join(" OR ", modelConditions) + ")");
        }

        // Task ID conditions
        if (!taskIds.isEmpty()) {
            conditions.add("task_id IN " + formatInClause(taskIds.size()));
        }
        if (taskRangeStart != null) {
            conditions.add("task_id >= ?");
        }
        if (taskRangeEnd != null) {
            conditions.add("task_id <= ?");
        }

        if (!conditions.isEmpty()) {
            query.append("WHERE ").append(String.join(" AND ", conditions)).append(" ");
        }

        query.append("ORDER BY content_vector ANN OF ? LIMIT ").append(searchLimit);
        return query.toString();
    }

    /**
     * Builds the parameter array for the prepared statement.
     */
    Object[] buildParams(CqlVector<Float> vector) {
        List<Object> params = new ArrayList<>();

        // First vector for similarity calculation
        params.add(vector);

        // Category parameters
        for (String category : categories) {
            params.add(category);
        }

        // Model parameters
        for (String modelgroup : modelGroups) {
            params.add(modelgroup);
        }

        // Task parameters
        params.addAll(taskIds);
        if (taskRangeStart != null) {
            params.add(taskRangeStart);
        }
        if (taskRangeEnd != null) {
            params.add(taskRangeEnd);
        }

        // Second vector for ANN ordering
        params.add(vector);

        return params.toArray();
    }

    // ==================== Parsing Methods ====================

    private void parseModels(String models) {
        if (models == null || models.isBlank()) {
            return;
        }
        Arrays.stream(models.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(modelGroups::add);
    }

    private void parseCategories(String categoryString) {
        if (categoryString == null) {
            return;  // null = search all categories
        }
        // Split and process - keep empty strings for primary data
        String[] parts = categoryString.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            categories.add(trimmed);  // Add even if empty string (for primary data)
        }
    }

    private void parseTasks(String tasks) throws ClusterException {
        if (tasks == null || tasks.isBlank()) {
            return;
        }
        for (String part : tasks.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.contains(":")) {
                parseTaskRange(trimmed);
            } else {
                parseTaskId(trimmed);
            }
        }
    }

    private void parseTaskId(String value) throws ClusterException {
        try {
            taskIds.add(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            throw new ClusterException(ClusterException.CLUSTER_ERROR,
                    "Invalid task ID: '" + value + "' - must be an integer");
        }
    }

    private void parseTaskRange(String range) throws ClusterException {
        String[] parts = range.split(":", -1);
        if (parts.length != 2) {
            throw new ClusterException(ClusterException.CLUSTER_ERROR,
                    "Invalid task range: '" + range + "' - expected format 'start:end'");
        }
        try {
            if (!parts[0].isBlank()) {
                taskRangeStart = Integer.parseInt(parts[0].trim());
            }
            if (!parts[1].isBlank()) {
                taskRangeEnd = Integer.parseInt(parts[1].trim());
            }
        } catch (NumberFormatException e) {
            throw new ClusterException(ClusterException.CLUSTER_ERROR,
                    "Invalid task range: '" + range + "' - values must be integers");
        }
        if (taskRangeStart != null && taskRangeEnd != null && taskRangeStart > taskRangeEnd) {
            throw new ClusterException(ClusterException.CLUSTER_ERROR,
                    "Invalid task range: start (" + taskRangeStart + ") must be <= end (" + taskRangeEnd + ")");
        }
    }

    /**
     * Helper method to prepare a IN clause
     * 
     * @param size
     * @return
     */
    private String formatInClause(int size) {
        return "(" + String.join(",", Collections.nCopies(size, "?")) + ")";
    }
}