package org.imixs.ai.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.imixs.workflow.ItemCollection;

import jakarta.ejb.AccessTimeout;
import jakarta.ejb.Lock;
import jakarta.ejb.LockType;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;

/**
 * Application-scoped singleton cache for workitems created during an agent
 * loop. Workitems are grouped by agent workitem ID — each agent run owns its
 * own {@link CacheEntry} containing the agent workitem itself and a list of
 * associated operator workitems.
 *
 * <p>
 * Entries expire automatically after {@link #TTL_MINUTES} to prevent memory
 * leaks caused by aborted agent loops.
 *
 * <p>
 * <b>Write API</b> — called by the stateless EJB during the agent loop:
 * <ul>
 * <li>{@link #put(ItemCollection)} — stores or refreshes the agent
 * workitem</li>
 * <li>{@link #putOperatorWorkitem(ItemCollection, ItemCollection)} — appends an
 * operator workitem</li>
 * <li>{@link #remove(ItemCollection, String)} — removes a single operator
 * workitem</li>
 * <li>{@link #removeAll(ItemCollection)} — clears all operator workitems</li>
 * </ul>
 *
 * <p>
 * <b>Read API</b> — called by request-scoped frontend beans:
 * <ul>
 * <li>{@link #getAgentWorkitem(String)} — returns the cached agent
 * workitem</li>
 * <li>{@link #getOperatorWorkitem(String, String)} — returns a specific
 * operator workitem by ID</li>
 * <li>{@link #hasEntries(String)} — returns {@code true} if pending operator
 * workitems exist</li>
 * </ul>
 *
 * <p>
 * <b>Threading</b>: The class-level {@link LockType#READ} lock allows parallel
 * reads via {@link ConcurrentHashMap}. Write methods are individually annotated
 * with {@link LockType#WRITE} to serialise mutations.
 */
@Singleton
@Startup
@Lock(LockType.READ)
@AccessTimeout(value = 5000)
public class AIAgentCache {

    /** Time-to-live for cache entries in minutes. */
    public static final int TTL_MINUTES = 30;

    private static final Logger logger = Logger.getLogger(AIAgentCache.class.getName());

    // -------------------------------------------------------------------------
    // Inner cache entry
    // -------------------------------------------------------------------------

    /**
     * Holds the agent workitem and its associated operator workitems for one agent
     * run, plus a creation timestamp for TTL tracking.
     *
     * <p>
     * The agent ID is derived once from {@link ItemCollection#getUniqueID()} so the
     * entry remains self-contained even if the workitem reference is updated by the
     * caller.
     */
    private static class CacheEntry {

        /** The unique ID of the owning agent workitem. */
        final String agentId;

        /** The agent workitem itself — returned to frontend beans on demand. */
        ItemCollection agentWorkitem;

        /** Creation time used for TTL checks. */
        final Instant createdAt;

        /**
         * Operator workitems collected during this agent run. Mutations are guarded by
         * the surrounding singleton's WRITE lock.
         */
        final List<ItemCollection> operatorWorkitems;

        /**
         * Creates a new entry for the given agent workitem.
         *
         * @param agentWorkitem the workitem that drives the agent run; must not be
         *                      {@code null} and must have a non-blank unique ID
         * @throws NullPointerException     if {@code agentWorkitem} is {@code null}
         * @throws IllegalArgumentException if the workitem has no unique ID
         */
        CacheEntry(ItemCollection agentWorkitem) {
            if (agentWorkitem == null) {
                throw new NullPointerException("agentWorkitem must not be null!");
            }
            String id = agentWorkitem.getUniqueID();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("agentWorkitem must have a non-blank $uniqueid!");
            }
            this.agentId = id;
            this.agentWorkitem = agentWorkitem;
            this.createdAt = Instant.now();
            this.operatorWorkitems = new ArrayList<>();
        }

        /**
         * Returns {@code true} if this entry has exceeded the configured TTL.
         *
         * @return {@code true} if the entry is expired
         */
        boolean isExpired() {
            return Instant.now().isAfter(createdAt.plusSeconds(TTL_MINUTES * 60L));
        }
    }

    // -------------------------------------------------------------------------
    // Cache storage — outer key: agent workitem ID
    // -------------------------------------------------------------------------

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    // =========================================================================
    // Write API — called by the stateless EJB
    // =========================================================================

    /**
     * Stores the agent workitem in the cache and returns its unique ID.
     *
     * <p>
     * If an entry for this agent ID already exists it is left untouched (the
     * existing entry keeps its operator workitems and its original TTL). Use
     * {@link #removeAll(ItemCollection)} first if a clean reset is needed.
     *
     * <p>
     * Expired entries across all agent buckets are evicted before the new entry is
     * inserted.
     *
     * @param agentWorkitem the workitem that drives the agent run; must not be
     *                      {@code null} and must have a non-blank unique ID
     * @return the {@code $uniqueid} of the stored agent workitem
     * @throws NullPointerException     if {@code agentWorkitem} is {@code null}
     * @throws IllegalArgumentException if the workitem has no unique ID
     */
    @Lock(LockType.WRITE)
    public String put(ItemCollection agentWorkitem) {
        if (agentWorkitem == null || agentWorkitem.getUniqueID().isEmpty()) {
            return null;
        }
        evictExpired();

        // first test if we have a cache entry...
        CacheEntry cacheEntry = cache.get(agentWorkitem.getUniqueID());
        if (cacheEntry == null) {
            // create a new one
            cacheEntry = new CacheEntry(agentWorkitem);
            cache.put(cacheEntry.agentId, cacheEntry);
            logger.info("├── AIAgentCache: stored agent workitem " + cacheEntry.agentId);
        } else {
            logger.info("├── AIAgentCache: renew already stored agent workitem " + cacheEntry.agentId);
            // update the agentWorkitem...
            cacheEntry.agentWorkitem = agentWorkitem;
            // cache.put(cacheEntry.agentId, cacheEntry);
        }

        return cacheEntry.agentId;
    }

    /**
     * Appends an operator workitem to the cache entry identified by the given agent
     * workitem.
     *
     * <p>
     * If no entry exists yet for this agent, a new one is created automatically
     * from {@code agentWorkitem}.
     *
     * <p>
     * Expired entries across all agent buckets are evicted before the workitem is
     * added.
     *
     * @param agentWorkitem    the workitem that drives the agent run; used to look
     *                         up or create the cache entry
     * @param operatorWorkitem the operator workitem to append; must not be
     *                         {@code null}
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code agentWorkitem} has no unique ID
     */
    @Lock(LockType.WRITE)
    public void putOperatorWorkitem(ItemCollection agentWorkitem, ItemCollection operatorWorkitem) {
        if (operatorWorkitem == null) {
            throw new NullPointerException("operatorWorkitem must not be null!");
        }
        evictExpired();

        String agentId = resolveAgentId(agentWorkitem);
        CacheEntry cacheEntry = cache.computeIfAbsent(agentId, k -> new CacheEntry(agentWorkitem));
        cacheEntry.operatorWorkitems.add(operatorWorkitem);

        logger.info("├── AIAgentCache: added operator workitem " + operatorWorkitem.getUniqueID()
                + " for agent " + agentId);
    }

    /**
     * Returns the cached agent workitem for the given agent ID, or {@code null} if
     * no entry exists or the entry has expired.
     *
     * <p>
     * Frontend beans (e.g. {@code @RequestScoped}) can call this method with just
     * the agent ID — no {@link org.imixs.ai.ImixsAIContextHandler} needed. The full
     * AI context can be restored afterwards via
     * {@code ImixsAIContextHandler.importContext()}.
     *
     * @param agentId the {@code $uniqueid} of the agent workitem
     * @return the cached agent workitem, or {@code null} if not found or expired
     */
    public ItemCollection getAgentWorkitem(String agentId) {
        CacheEntry cacheEntry = cache.get(agentId);
        if (cacheEntry == null) {
            return null;
        }
        if (cacheEntry.isExpired()) {
            logger.warning("├── AIAgentCache: entry expired for agent " + agentId);
            return null;
        }
        return cacheEntry.agentWorkitem;
    }

    /**
     * Returns a specific operator workitem from the cache entry identified by the
     * given agent ID, or {@code null} if not found or expired.
     *
     * @param agentId  the {@code $uniqueid} of the agent workitem
     * @param uniqueId the {@code $uniqueid} of the operator workitem to retrieve
     * @return the matching operator workitem, or {@code null}
     */
    public ItemCollection getOperatorWorkitem(String agentId, String uniqueId) {
        CacheEntry cacheEntry = cache.get(agentId);
        if (cacheEntry == null) {
            return null;
        }
        if (cacheEntry.isExpired()) {
            logger.warning("├── AIAgentCache: entry expired for agent " + agentId);
            return null;
        }

        return cacheEntry.operatorWorkitems.stream()
                .filter(w -> w.getUniqueID().equals(uniqueId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns {@code true} if there is at least one non-expired operator workitem
     * for the given agent ID.
     *
     * <p>
     * This method is used by the {@code AIAgentOperator} to decide whether to
     * trigger the next processing event or the final success event.
     *
     * @param agentId the {@code $uniqueid} of the agent workitem
     * @return {@code true} if at least one pending operator workitem exists
     */
    public boolean hasOperatorWorkitems(String agentId) {
        CacheEntry cacheEntry = cache.get(agentId);
        if (cacheEntry == null) {
            return false;
        }
        if (cacheEntry.isExpired()) {
            return false;
        }
        return !cacheEntry.operatorWorkitems.isEmpty();
    }

    /**
     * Removes the entire cache entry for the given agent workitem, including the
     * cached agent workitem itself and all associated operator workitems.
     *
     * <p>
     * This method should be called when an agent run has completed successfully or
     * has been aborted, to release all resources held by the entry.
     *
     * <p>
     * If no entry exists for the given agent workitem, the call is a no-op.
     *
     * @param agentWorkitem the workitem that drives the agent run; used to look up
     *                      the cache entry to remove
     * @throws NullPointerException     if {@code agentWorkitem} is {@code null}
     * @throws IllegalArgumentException if {@code agentWorkitem} has no unique ID
     */
    @Lock(LockType.WRITE)
    public void remove(ItemCollection agentWorkitem) {
        String agentId = resolveAgentId(agentWorkitem);
        CacheEntry removed = cache.remove(agentId);
        if (removed != null) {
            logger.info("├── AIAgentCache: removed complete entry for agent " + agentId
                    + " (" + removed.operatorWorkitems.size() + " operator workitem(s) discarded)");
        }
    }

    /**
     * Removes a specific operator workitem from the cache entry identified by the
     * given agent workitem.
     *
     * <p>
     * If no matching cache entry or operator workitem is found, the call is a
     * no-op.
     *
     * @param agentWorkitem the workitem that drives the agent run; used to look up
     *                      the cache entry
     * @param uniqueId      the {@code $uniqueid} of the operator workitem to remove
     * @throws NullPointerException     if {@code agentWorkitem} is {@code null}
     * @throws IllegalArgumentException if {@code agentWorkitem} has no unique ID
     */
    @Lock(LockType.WRITE)
    public void removeOperatorWorkitem(ItemCollection agentWorkitem, String uniqueId) {
        String agentId = resolveAgentId(agentWorkitem);
        CacheEntry cacheEntry = cache.get(agentId);
        if (cacheEntry == null) {
            return;
        }

        boolean removed = cacheEntry.operatorWorkitems
                .removeIf(w -> w.getUniqueID().equals(uniqueId));

        if (removed) {
            logger.info("├── AIAgentCache: removed operator workitem " + uniqueId
                    + " for agent " + agentId);
        } else {
            logger.warning("├── AIAgentCache: operator workitem not found: " + uniqueId
                    + " for agent " + agentId);
        }
    }

    /**
     * Removes all operator workitems from the cache entry identified by the given
     * agent workitem. The entry itself (including the cached agent workitem) is
     * kept; only the operator workitem list is cleared.
     *
     * <p>
     * This method should be called on agent error or timeout to release resources
     * while keeping the agent entry available for status queries.
     *
     * @param agentWorkitem the workitem that drives the agent run; used to look up
     *                      the cache entry
     * @throws NullPointerException     if {@code agentWorkitem} is {@code null}
     * @throws IllegalArgumentException if {@code agentWorkitem} has no unique ID
     */
    @Lock(LockType.WRITE)
    public void removeAllOperatorWorkitems(ItemCollection agentWorkitem) {
        String agentId = resolveAgentId(agentWorkitem);
        CacheEntry cacheEntry = cache.get(agentId);
        if (cacheEntry == null) {
            return;
        }

        cacheEntry.operatorWorkitems.clear();
        logger.info("├── AIAgentCache: cleared all operator workitems for agent " + agentId);
    }

    // =========================================================================
    // Read API — called by request-scoped frontend beans
    // =========================================================================

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Resolves the agent ID from the given agent workitem.
     *
     * @param agentWorkitem the agent workitem; must not be {@code null} and must
     *                      have a non-blank unique ID
     * @return the unique ID of the agent workitem
     * @throws NullPointerException     if {@code agentWorkitem} is {@code null}
     * @throws IllegalArgumentException if the workitem has no unique ID
     */
    private String resolveAgentId(ItemCollection agentWorkitem) {
        if (agentWorkitem == null) {
            throw new NullPointerException("agentWorkitem must not be null!");
        }
        String id = agentWorkitem.getUniqueID();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("agentWorkitem must have a non-blank $uniqueid!");
        }
        return id;
    }

    /**
     * Evicts all expired entries from the cache. Called automatically before every
     * write operation to prevent unbounded memory growth from abandoned agent runs.
     *
     * <p>
     * This method is always invoked while the caller already holds the
     * {@link LockType#WRITE} lock, so no additional locking is required here.
     */
    private void evictExpired() {
        cache.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired()) {
                logger.warning("├── AIAgentCache: evicting expired entry for agent " + entry.getKey());
                return true;
            }
            return false;
        });
    }
}