/****************************************************************************
 * Copyright (c) 2022-2025 Imixs Software Solutions GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * This Source Code may also be made available under the terms of the
 * GNU General Public License, version 2 or later (GPL-2.0-or-later),
 * which is available at https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
 ****************************************************************************/

package org.imixs.ai.rag.cluster;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.InvalidKeyspaceException;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.data.CqlVector;
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.Timeout;
import jakarta.ejb.Timer;
import jakarta.ejb.TimerConfig;
import jakarta.ejb.TimerService;
import jakarta.inject.Inject;

/**
 * The ClusterService provides methods to persist the content of a Imixs
 * Document into a Cassandra keystore.
 * <p>
 * The service saves the content in XML format. The size of an XML
 * representation of a Imixs document is only slightly different in size from
 * the serialized map object. This is the reason why we do not store the
 * document map in a serialized object format.
 * <p>
 * The ClusterService creates a Core-KeySpace automatically which is used for
 * the internal management.
 * 
 * @author rsoika
 * 
 */
@Singleton
@Startup
public class ClusterService {

    public static final String KEYSPACE_REGEX = "^[a-z_]*[^-]$";
    public static final int SEARCH_LIMIT_MAX = 100;

    // mandatory environment settings
    public static final String ENV_EMBEDDINGS_CLUSTER_CONTACTPOINTS = "EMBEDDINGS_CLUSTER_CONTACTPOINTS";
    public static final String ENV_EMBEDDINGS_CLUSTER_KEYSPACE = "EMBEDDINGS_CLUSTER_KEYSPACE";

    public static final int DIMENSIONS = 768;
    // public static final int DIMENSIONS = 384;

    // optional environment settings
    public static final String ENV_EMBEDDINGS_CLUSTER_AUTH_USER = "EMBEDDINGS_CLUSTER_AUTH_USER";
    public static final String ENV_EMBEDDINGS_CLUSTER_AUTH_PASSWORD = "EMBEDDINGS_CLUSTER_AUTH_PASSWORD";
    public static final String ENV_EMBEDDINGS_CLUSTER_SSL = "EMBEDDINGS_CLUSTER_SSL";
    public static final String ENV_EMBEDDINGS_CLUSTER_SSL_TRUSTSTOREPATH = "EMBEDDINGS_CLUSTER_SSL_TRUSTSTOREPATH";
    public static final String ENV_EMBEDDINGS_CLUSTER_SSL_TRUSTSTOREPASSWORD = "EMBEDDINGS_CLUSTER_SSL_TRUSTSTOREPASSWORD";
    public static final String ENV_EMBEDDINGS_CLUSTER_SSL_KEYSTOREPATH = "EMBEDDINGS_CLUSTER_SSL_KEYSTOREPATH";
    public static final String ENV_EMBEDDINGS_CLUSTER_SSL_KEYSTOREPASSWORD = "EMBEDDINGS_CLUSTER_SSL_KEYSTOREPASSWORD";

    public static final String ENV_EMBEDDINGS_CLUSTER_REPLICATION_FACTOR = "EMBEDDINGS_CLUSTER_REPLICATION_FACTOR";
    public static final String ENV_EMBEDDINGS_CLUSTER_REPLICATION_CLASS = "EMBEDDINGS_CLUSTER_REPLICATION_CLASS";

    // workflow rest service endpoint
    public static final String ENV_WORKFLOW_SERVICE_ENDPOINT = "WORKFLOW_SERVICE_ENDPOINT";
    public static final String ENV_WORKFLOW_SERVICE_USER = "WORKFLOW_SERVICE_USER";
    public static final String ENV_WORKFLOW_SERVICE_PASSWORD = "WORKFLOW_SERVICE_PASSWORD";
    public static final String ENV_WORKFLOW_SERVICE_AUTHMETHOD = "WORKFLOW_SERVICE_AUTHMETHOD";

    private static Logger logger = Logger.getLogger(ClusterService.class.getName());

    @Inject
    @ConfigProperty(name = ENV_EMBEDDINGS_CLUSTER_REPLICATION_FACTOR, defaultValue = "1")
    String repFactor;

    @Inject
    @ConfigProperty(name = ENV_EMBEDDINGS_CLUSTER_REPLICATION_CLASS, defaultValue = "SimpleStrategy")
    String repClass;

    @Inject
    @ConfigProperty(name = ENV_EMBEDDINGS_CLUSTER_CONTACTPOINTS)
    Optional<String> contactPoint;

    @Inject
    @ConfigProperty(name = ENV_EMBEDDINGS_CLUSTER_KEYSPACE)
    Optional<String> keySpace;

    @Inject
    @ConfigProperty(name = ENV_EMBEDDINGS_CLUSTER_AUTH_USER)
    Optional<String> userid;

    @Inject
    @ConfigProperty(name = ENV_EMBEDDINGS_CLUSTER_AUTH_PASSWORD)
    Optional<String> password;

    @Inject
    @ConfigProperty(name = ENV_EMBEDDINGS_CLUSTER_SSL, defaultValue = "false")
    boolean bUseSSL;

    @Inject
    @ConfigProperty(name = ENV_EMBEDDINGS_CLUSTER_SSL_TRUSTSTOREPATH)
    Optional<String> truststorePath;

    @Inject
    @ConfigProperty(name = ENV_EMBEDDINGS_CLUSTER_SSL_TRUSTSTOREPASSWORD)
    Optional<String> truststorePwd;

    @Inject
    @ConfigProperty(name = ENV_EMBEDDINGS_CLUSTER_SSL_KEYSTOREPATH)
    Optional<String> keystorePath;

    @Inject
    @ConfigProperty(name = ENV_EMBEDDINGS_CLUSTER_SSL_KEYSTOREPASSWORD)
    Optional<String> keystorePwd;

    private CqlSession session;

    private PreparedStatement insertVectorStmt;
    // private PreparedStatement searchVectorStmt;
    private final Map<String, PreparedStatement> searchVectorStatements = new HashMap<>();
    private PreparedStatement selectVectorStmt;
    private PreparedStatement removeVectorStmt;
    private PreparedStatement selectChunksStmt = null;
    private PreparedStatement updateChunkStmt = null;

    @Resource
    private TimerService timerService;

    @PostConstruct
    public void init() {
        scheduleClusterCheck();
    }

    /**
     * Versucht, die Session herzustellen. Bei Fehler wird ein neuer Timer geplant.
     */
    private void scheduleClusterCheck() {
        try {
            logger.log(Level.INFO, "├── connecting Cassandra cluster...");
            getSession();
            logger.log(Level.INFO, "├── ✅ successfully connected to cassandra!");
        } catch (ClusterException e) {
            logger.log(Level.WARNING, "├── " + e.getMessage());
            logger.log(Level.WARNING, "├── ⚠️ cluster not ready yet. retrying in 10 seconds...");
            // retry in 10 sec
            timerService.createSingleActionTimer(10000, new TimerConfig());
        }
    }

    /**
     * called by timer. Tries to connect cassandra clauster
     */
    @Timeout
    public void retryClusterCheck(Timer timer) {
        scheduleClusterCheck(); // retry Check
    }

    @PreDestroy
    private void tearDown() {
        // close session and cluster object
        if (session != null) {
            session.close();
        }
    }

    /**
     * Returns the Cassandra Session object. If the session is not yet created, the
     * method tries to init the session the first time. If initialization failed the
     * method throws a ClusterException
     * <p>
     * Note: the initialization is only successful if the cassandra cluster is fully
     * started.
     * <p>
     * The method also verifies the keyspace and creates a table schema if not yet
     * available.
     * 
     * @return
     */
    public CqlSession getSession() throws ClusterException {
        if (session == null) {
            try {
                logger.info("├── initializing cluster and keyspace...");
                session = initSessionWithKeyspace();
                logger.info("│   ├── ✅ schema status = OK");

                createSchema();

            } catch (Exception e) {
                throw new ClusterException(ClusterException.CLUSTER_ERROR,
                        "Failed to init cassandra session: " + e.getMessage(), e);
            }
        }
        return session;
    }

    /**
     * This method opens the configured keyspace. If the keyspace does no yet exist
     * it creates a new keyspace with the table schema
     * 
     * @return
     * @throws ClusterException
     */
    private CqlSession initSessionWithKeyspace() throws ClusterException {
        // validate keyspace name
        String keySpacename = keySpace.orElseThrow(
                () -> new ClusterException(ClusterException.INVALID_KEYSPACE, "EMBEDDINGS_CLUSTER_KEYSPACE not set!"));

        if (!isValidKeyspaceName(keySpacename)) {
            throw new ClusterException(ClusterException.INVALID_KEYSPACE,
                    "keyspace '" + keySpacename + "' name invalid.");
        }

        try {
            // try to connect keyspace ...
            session = createSession(keySpacename);
            logger.info("│   ├── ✅ keyspace '" + keySpacename + "' status = OK");
            return session;
        } catch (InvalidQueryException | InvalidKeyspaceException e) {
            logger.warning("│   ├── ⚠️ Keyspace does not yet exist, creating new keyspace...");
            // Create a basic session object to create the keyspace....
            session = createSession(null);
            createKeySpace(keySpacename);
            // close and reconnect session...
            session.close();
            session = createSession(keySpacename);

            return session;
        }
    }

    /**
     * This method creates a Cassandra Cluster object. The cluster is defined by
     * ContactPoints provided in the environmetn variable
     * 'EMBEDDINGS__CLUSTER_CONTACTPOINTS' or in the imixs.property
     * 'archive.cluster.contactpoints'
     * 
     * @return Cassandra Cluster instacne
     */

    private CqlSession createSession(String keyspace) throws ClusterException {
        CqlSessionBuilder builder = CqlSession.builder();
        // Boolean used to check if at least one host could be resolved
        boolean found = false;

        if (!contactPoint.isPresent() || contactPoint.get().isEmpty()) {
            throw new ClusterException(ClusterException.MISSING_CONTACTPOINT,
                    "missing cluster contact points - verify configuration!");
        }

        logger.info("│   ├── cluster connecting: " + contactPoint.get());
        String[] hosts = contactPoint.get().split(",");
        for (String host : hosts) {
            try {
                logger.info("│   ├── adding host: " + host + ":9042");
                builder.addContactPoint(new InetSocketAddress(host, 9042));
                // One host could be resolved
                found = true;
            } catch (IllegalArgumentException e) {
                // This host could not be resolved so we log a message and keep going
                logger.warning("...the host '" + host + "' is unknown so it will be ignored");
            }
        }
        if (!found) {
            // No host could be resolved so we throw an exception
            throw new IllegalStateException("All provided hosts are unknown - check cluster status and configuration!");
        }
        // set optional credentials...
        if (userid.isPresent() && !userid.get().isEmpty()) {
            builder = builder.withAuthCredentials(userid.get(), password.get());
        }

        if (keyspace != null) {
            builder.withKeyspace(keyspace);
        }
        builder.withLocalDatacenter("datacenter1");
        return builder.build();
    }

    /**
     * Test if the keyspace name is valid.
     * 
     * @param keySpace
     * @return
     */
    public boolean isValidKeyspaceName(String keySpace) {
        if (keySpace == null || keySpace.isEmpty()) {
            return false;
        }
        return keySpace.matches(KEYSPACE_REGEX);
    }

    /**
     * This method creates a cassandra keySpace
     * 
     * @param cluster
     */
    private void createKeySpace(String keySpace) throws ClusterException {
        logger.info("│   ├── creating new keyspace '" + keySpace + "'...");

        String statement = "CREATE KEYSPACE IF NOT EXISTS " + keySpace + " WITH replication = {'class': '" + repClass
                + "', 'replication_factor': " + repFactor + "};";

        session.execute(statement);
        logger.info("│   ├── ✅ keyspace '" + keySpace + "' created.");

    }

    /**
     * This method creates the keySpace schema.
     * 
     * CREATE TABLE embeddings.document_vectors ( business_document_id uuid,
     * chunk_id text, chunk_text text, content_vector VECTOR <FLOAT, 768>, PRIMARY
     * KEY (business_document_id, chunk_id) ); );
     * 
     * @param cluster
     */
    private void createSchema() throws ClusterException {

        logger.info("│   ├── verify schema...");
        // now create table schemas
        String query = "CREATE TABLE IF NOT EXISTS document_vectors (\n" + //
                "  id text,\n" + //
                "  chunk_id uuid,\n" + //
                "  model_version text,\n" + //
                "  task_id int,\n" + //
                "  content_chunk text,\n" + //
                "  content_vector VECTOR <FLOAT, " + DIMENSIONS + ">,\n" + //
                "  PRIMARY KEY (id, chunk_id)\n" + //
                ");";
        session.execute(query);

        logger.info("│   ├── verify index...");
        query = "CREATE INDEX IF NOT EXISTS edv_ann_index\n" + //
                "  ON document_vectors(content_vector) USING 'sai';";
        session.execute(query);

        // Additional indexes for metadata filtering
        query = "CREATE INDEX IF NOT EXISTS idx_model_version " + //
                " ON document_vectors(model_version) USING 'sai';";
        session.execute(query);
        query = "CREATE INDEX IF NOT EXISTS idx_task_id  " + //
                " ON document_vectors(task_id) USING 'sai';";
        session.execute(query);

        logger.info("│   ├── ✅ database schema OK.");
    }

    /**
     * Inserts an embedding together with a content chunk into the database. The
     * uniqueID is the reference to the corresponding workitem holding the full meta
     * data.
     * 
     * @param uniqueID
     * @param modelVersion
     * @param taskId
     * @param content
     * @param vector
     * @throws ClusterException
     */
    public void insertVector(String uniqueID, String modelVersion, int taskId, String content, List<Float> vector)
            throws ClusterException {

        // Prepare statement?
        if (insertVectorStmt == null) {
            String insertQuery = "INSERT INTO document_vectors "
                    + "(id, chunk_id, model_version, task_id, content_chunk, content_vector) VALUES (?, ?, ?, ?, ?, ?)";
            insertVectorStmt = session.prepare(insertQuery);
        }

        try {
            // Generiere UUID
            UUID chunk_id = UUID.randomUUID();
            // CqlVector
            CqlVector<Float> cqlVector = CqlVector.newInstance(vector);

            BoundStatement boundStmt = insertVectorStmt.bind(uniqueID, chunk_id, modelVersion, taskId, content,
                    cqlVector);
            session.execute(boundStmt);

        } catch (Exception e) {
            throw new ClusterException(ClusterException.CLUSTER_ERROR,
                    "Failed to insert embeddings into keyspace: " + e.getMessage(), e);
        }
    }

    /**
     * Removes all embeddings for a given uniqueID. The method returns true if
     * entries have existed.
     * 
     * @param uniqueID
     * @param content
     * @param vector
     * @throws ClusterException
     */
    public boolean removeAllEmbeddings(String uniqueID) throws ClusterException {
        // Prepare statement if not already done
        if (removeVectorStmt == null) {
            String deleteQuery = "DELETE FROM document_vectors WHERE id = ?";
            removeVectorStmt = session.prepare(deleteQuery);
        }

        try {
            // test if entries exist
            long count = countIndexEntries(uniqueID);
            if (count > 0) {
                // remove all
                BoundStatement deleteBoundStmt = removeVectorStmt.bind(uniqueID);
                session.execute(deleteBoundStmt);
                logger.info("│   ├── ✅ Removed " + count + " entries for uniqueID: " + uniqueID);
                return true;
            } else {
                // logger.info("│ ├── ℹ️ No entries found for uniqueID: " + uniqueID);
            }

        } catch (Exception e) {
            throw new ClusterException(ClusterException.CLUSTER_ERROR,
                    "Failed to remove entries for uniqueID '" + uniqueID + "': " + e.getMessage(), e);
        }
        return false;
    }

    /**
     * This method returns the number of index entries with the given uniqueId. The
     * method can be used to test if index data is available.
     * 
     * @param uniqueId
     * @return - count of entries, or 0 if no entries exist
     * @throws ClusterException
     */
    public long countIndexEntries(String uniqueId) throws ClusterException {
        if (selectVectorStmt == null) {
            String selectQuery = "SELECT COUNT(*) FROM document_vectors WHERE id = ?";
            selectVectorStmt = session.prepare(selectQuery);
        }
        try {
            // test if entries exist
            BoundStatement selectBoundStmt = selectVectorStmt.bind(uniqueId);
            ResultSet resultSet = session.execute(selectBoundStmt);
            Row row = resultSet.one();

            if (row != null) {
                long count = row.getLong(0);
                return count;
            }
        } catch (Exception e) {
            throw new ClusterException(ClusterException.CLUSTER_ERROR,
                    "Failed to lookup entries for uniqueID '" + uniqueId + "': " + e.getMessage(), e);
        }
        return 0;
    }

    /**
     * Performs a semantic search on the document vector database using cosine
     * similarity.
     * <p>
     * This method searches for the most relevant document chunks based on the
     * provided query embedding. If multiple chunks exist for the same document ID,
     * only the chunk with the highest similarity score is returned. The results are
     * sorted by relevance score in descending order.
     * <p>
     * The params 'modelVrsions' and 'taskIds' are optional and can be used to
     * refine the search result to specific process instances only.
     * 
     * @param queryEmbedding The embedding vector of the search query as a list of
     *                       float values. The dimensionality must match the
     *                       DIMENSIONS constant used in the database schema.
     * @return A list of {@link RetrievalResult} objects containing the most
     *         relevant documents, limited to a maximum of 5 results. Each result
     *         includes the document ID, the best matching content chunk, and the
     *         similarity score. The list is sorted by similarity score in
     *         descending order (most relevant first).
     * @throws ClusterException if the database query fails or if there's an error
     *                          processing the embedding vector
     * 
     * @see RetrievalResult
     * @since 1.0
     */
    public List<RetrievalResult> searchEmbeddings(List<Float> embedding,
            int maxResults,
            List<String> modelVersions,
            List<Integer> taskIds,
            Integer taskIdRangeStart,
            Integer taskIdRangeEnd) throws ClusterException {

        if (maxResults <= 0) {
            throw new ClusterException(ClusterException.CLUSTER_ERROR,
                    "maxResults must be greater than 0, but was: " + maxResults);
        }
        // Calculate a reasonable DB limit based on maxResults
        // Factor 3-4 times to have enough chunks for deduplication.
        int searchLimit = Math.min(maxResults * 4, SEARCH_LIMIT_MAX); // Max 100

        StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append("SELECT id, model_version, task_id, content_chunk, ")
                .append("similarity_cosine(content_vector, ?) ")
                .append("FROM document_vectors ");

        List<String> whereConditions = new ArrayList<>();
        // Model version filtering (with wildcard support)
        if (modelVersions != null && !modelVersions.isEmpty()) {
            List<String> modelConditions = new ArrayList<>();
            for (String modelVersion : modelVersions) {
                if (modelVersion.endsWith("*")) {
                    // Wildcard search using LIKE
                    modelConditions.add("model_version LIKE ?");
                } else {
                    // Exact match
                    modelConditions.add("model_version = ?");
                }
            }
            whereConditions.add("(" + String.join(" OR ", modelConditions) + ")");
        }

        // Task ID filtering - either list or range
        if (taskIds != null && !taskIds.isEmpty()) {
            whereConditions.add("task_id IN " + formatInClause(taskIds.size()));
        } else if (taskIdRangeStart != null && taskIdRangeEnd != null) {
            whereConditions.add("task_id >= ? AND task_id <= ?");
        } else if (taskIdRangeStart != null) {
            whereConditions.add("task_id >= ?");
        } else if (taskIdRangeEnd != null) {
            whereConditions.add("task_id <= ?");
        }

        if (!whereConditions.isEmpty()) {
            queryBuilder.append("WHERE ").append(String.join(" AND ", whereConditions)).append(" ");
        }

        queryBuilder.append("ORDER BY content_vector ANN OF ? LIMIT ").append(searchLimit);
        // Prepare statement (with caching)
        String statementKey = buildStatementKey(searchLimit, modelVersions, taskIds,
                taskIdRangeStart, taskIdRangeEnd);
        PreparedStatement searchStmt = searchVectorStatements.get(statementKey);
        if (searchStmt == null) {
            searchStmt = session.prepare(queryBuilder.toString());
            searchVectorStatements.put(statementKey, searchStmt);
        }

        try {
            // use CqlVector
            CqlVector<Float> cqlVector = CqlVector.newInstance(embedding);
            // Build parameters list
            List<Object> params = new ArrayList<>();
            params.add(cqlVector);  // First parameter for similarity calculation

            // Add model version parameters
            if (modelVersions != null && !modelVersions.isEmpty()) {
                for (String modelVersion : modelVersions) {
                    if (modelVersion.endsWith("*")) {
                        // Convert wildcard to LIKE pattern
                        params.add(modelVersion.substring(0, modelVersion.length() - 1) + "%");
                    } else {
                        params.add(modelVersion);
                    }
                }
            }

            // Add task ID parameters
            if (taskIds != null && !taskIds.isEmpty()) {
                params.addAll(taskIds);
            } else if (taskIdRangeStart != null && taskIdRangeEnd != null) {
                params.add(taskIdRangeStart);
                params.add(taskIdRangeEnd);
            } else if (taskIdRangeStart != null) {
                params.add(taskIdRangeStart);
            } else if (taskIdRangeEnd != null) {
                params.add(taskIdRangeEnd);
            }

            params.add(cqlVector);

            ResultSet resultRows = session.execute(searchStmt.bind(params.toArray()));

            // track best Similarity per uniqueID
            Map<String, RetrievalResult> bestMatches = new HashMap<>();

            for (Row row : resultRows) {
                String id = row.getString(0);
                String contentChunk = row.getString(3);
                Float similarity = row.getFloat(4);
                // test if we already have a match
                RetrievalResult existingMatch = bestMatches.get(id);

                if (existingMatch == null || similarity > existingMatch.getScore()) {
                    // better match found!
                    bestMatches.put(id, new RetrievalResult(id, contentChunk, similarity));
                    logger.info("│   ├── Found/Updated document: " + id + " with score: " + similarity);
                }
            }
            // Sortiere nach Score (absteigend) und gebe Top 5 zurück
            List<RetrievalResult> result = bestMatches.values().stream()
                    .sorted((a, b) -> Float.compare(b.getScore(), a.getScore()))
                    .limit(maxResults)
                    .peek(match -> {
                        System.out.println("☑️ $UniqueID: " + match.getUniqueId() + " Score=" + match.getScore());
                        System.out.println(match.getContent());
                        System.out.println("");
                    })
                    .collect(Collectors.toList());

            logger.info("│   ├── ✅ Retrieved " + result.size() + " relevant documents");
            return result;

        } catch (Exception e) {
            throw new ClusterException(ClusterException.CLUSTER_ERROR,
                    "Failed to select embeddings from keyspace: " + e.getMessage(), e);
        }
    }

    public List<RetrievalResult> searchEmbeddings(List<Float> embedding,
            int maxResults) throws ClusterException {

        return searchEmbeddings(embedding, maxResults, null, null, null, null);
    }

    // Range-based search
    public List<RetrievalResult> searchEmbeddingsWithTaskRange(List<Float> embedding, int maxResults,
            List<String> modelVersions,
            int taskIdStart, int taskIdEnd)
            throws ClusterException {
        return searchEmbeddings(embedding, maxResults, modelVersions, null, taskIdStart, taskIdEnd);
    }

    // Wildcard model search
    public List<RetrievalResult> searchEmbeddingsWithModelWildcard(List<Float> embedding, int maxResults,
            String modelPattern,
            List<Integer> taskIds)
            throws ClusterException {
        return searchEmbeddings(embedding, maxResults, Arrays.asList(modelPattern), taskIds, null, null);
    }

    /**
     * Update the workflow meta data. The method returns true if a index with the
     * given uniqueId was found
     * 
     * @param uniqueId
     * @param modelVersion
     * @param taskId
     * @return true if an update was performed
     * @throws ClusterException
     */
    public boolean updateMetaData(String uniqueId, String modelVersion, int taskId)
            throws ClusterException {

        // Prepare statements if not already done
        if (selectChunksStmt == null) {
            String selectQuery = "SELECT chunk_id FROM document_vectors WHERE id = ?";
            selectChunksStmt = session.prepare(selectQuery);
        }

        if (updateChunkStmt == null) {
            String updateQuery = "UPDATE document_vectors SET model_version = ?, task_id = ? WHERE id = ? AND chunk_id = ?";
            updateChunkStmt = session.prepare(updateQuery);
        }
        try {
            // 1. Alle chunk_ids für die uniqueId ermitteln
            BoundStatement selectBoundStmt = selectChunksStmt.bind(uniqueId);
            ResultSet resultSet = session.execute(selectBoundStmt);

            List<UUID> chunkIds = new ArrayList<>();
            for (Row row : resultSet) {
                chunkIds.add(row.getUuid("chunk_id"));
            }

            if (chunkIds.isEmpty()) {
                logger.info("│   ├── ℹ️ No entries found for uniqueID: " + uniqueId);
                return false;
            }

            // 2. Jede gefundene chunk_id einzeln updaten
            int updateCount = 0;
            for (UUID chunkId : chunkIds) {
                BoundStatement updateBoundStmt = updateChunkStmt.bind(modelVersion, taskId, uniqueId, chunkId);
                session.execute(updateBoundStmt);
                updateCount++;
            }

            logger.info("│   ├── Updated metadata for " + updateCount + " entries with uniqueID: " +
                    uniqueId + " (model: " + modelVersion + ", task: " + taskId + ")");
            return true;

        } catch (Exception e) {
            throw new ClusterException(ClusterException.CLUSTER_ERROR,
                    "Failed to update metadata for uniqueID '" + uniqueId + "': " + e.getMessage(), e);
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

    /**
     * Helper method to build a key for specific select statement with parameters.
     * This key is used to cache prepared statements
     * 
     * @param dbLimit
     * @param modelVersions
     * @param taskIds
     * @param taskIdRangeStart
     * @param taskIdRangeEnd
     * @return
     */
    private String buildStatementKey(int dbLimit, List<String> modelVersions, List<Integer> taskIds,
            Integer taskIdRangeStart, Integer taskIdRangeEnd) {
        StringBuilder keyBuilder = new StringBuilder("search_").append(dbLimit);

        if (modelVersions != null) {
            keyBuilder.append("_mv").append(modelVersions.size());
            // Add wildcard indicator
            long wildcardCount = modelVersions.stream().mapToLong(mv -> mv.endsWith("*") ? 1 : 0).sum();
            keyBuilder.append("w").append(wildcardCount);
        }

        if (taskIds != null && !taskIds.isEmpty()) {
            keyBuilder.append("_ti").append(taskIds.size());
        } else if (taskIdRangeStart != null || taskIdRangeEnd != null) {
            keyBuilder.append("_tr");
            if (taskIdRangeStart != null)
                keyBuilder.append("s");
            if (taskIdRangeEnd != null)
                keyBuilder.append("e");
        }

        return keyBuilder.toString();
    }
}
