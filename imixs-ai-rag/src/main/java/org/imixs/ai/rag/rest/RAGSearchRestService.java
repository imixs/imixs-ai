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

package org.imixs.ai.rag.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.imixs.ai.rag.RAGUtil;
import org.imixs.ai.rag.RetrievalService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.engine.DocumentService;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.index.vectors.LuceneVectorIndexManager;
import org.imixs.workflow.index.vectors.VectorMatch;
import org.imixs.workflow.xml.XMLDataCollection;
import org.imixs.workflow.xml.XMLDataCollectionAdapter;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;

/**
 * The RAGSearchRestService provides a REST endpoint for a semantic search in
 * the RAG index. The endpoint is intended for testing and tuning retrievals,
 * e.g. by the Imixs-Admin client.
 * <p>
 * Example:
 *
 * <pre>
 * GET /api/rag/search?text=Isoflex 1000&amp;categories=order.summary&amp;query=($workflowgroup:"Auftrag")&amp;maxResults=10
 * </pre>
 * <p>
 * The result is a collection of the matching documents, sorted by similarity.
 * Each document contains the additional items:
 * <ul>
 * <li>{@code $similarity} - the cosine similarity of the best matching chunk
 * (-1 to 1)</li>
 * <li>{@code $rank} - the position in the result (starting with 1)</li>
 * </ul>
 * <p>
 * The search runs with the rights of the authenticated user. Only documents
 * the user is allowed to read are returned.
 * <p>
 * The class is a plain JAX-RS resource. It is registered by the REST
 * application of the workflow application - the module provides no own
 * {@code jakarta.ws.rs.core.Application}.
 *
 * @version 1.0
 * @author rsoika
 */
@Path("/rag")
@Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON, MediaType.TEXT_XML })
@Stateless
public class RAGSearchRestService {

    private static final int MAX_RESULTS_LIMIT = 100;

    public static final String ITEM_SIMILARITY = "$similarity";
    public static final String ITEM_RANK = "$rank";

    private static final Logger logger = Logger.getLogger(RAGSearchRestService.class.getName());

    @Inject
    private RetrievalService retrievalService;

    @Inject
    private DocumentService documentService;

    @Inject
    private LuceneVectorIndexManager vectorIndexManager;

    /**
     * Searches the RAG index for documents similar to the given text.
     *
     * @param text       - the query text (required)
     * @param categories - optional comma separated list of categories, empty =
     *                   all categories
     * @param query      - optional full-text search term to filter the documents
     * @param maxResults - maximum number of documents (default 10, max 100)
     * @param items      - optional comma separated list of items to be returned
     * @param debug      - prints debug information into the server log
     * @return the matching documents incl. $similarity and $rank
     */
    @GET
    @Path("/search")
    public XMLDataCollection search(@QueryParam("text") String text,
            @QueryParam("categories") String categories,
            @QueryParam("query") String query,
            @QueryParam("maxResults") @DefaultValue("10") int maxResults,
            @QueryParam("items") String items,
            @QueryParam("debug") @DefaultValue("false") boolean debug) {

        if (!vectorIndexManager.isEnabled()) {
            throw new WebApplicationException("The vector index is disabled", Status.SERVICE_UNAVAILABLE);
        }
        if (text == null || text.isBlank()) {
            throw new WebApplicationException("Missing query parameter 'text'", Status.BAD_REQUEST);
        }
        int limit = Math.min(Math.max(maxResults, 1), MAX_RESULTS_LIMIT);

        long l = System.currentTimeMillis();
        List<VectorMatch> matches;
        try {
            matches = retrievalService.search(text, RAGUtil.parseCategories(categories), query, limit, debug);
        } catch (PluginException e) {
            // invalid query or configuration
            throw new WebApplicationException(e.getMessage(), Status.BAD_REQUEST);
        } catch (AdapterException e) {
            // embedding endpoint not reachable
            throw new WebApplicationException(e.getMessage(), Status.BAD_GATEWAY);
        }

        // load the documents - the document service verifies the read access again
        List<ItemCollection> result = new ArrayList<>();
        int rank = 1;
        for (VectorMatch match : matches) {
            ItemCollection document = documentService.load(match.uniqueId());
            if (document == null) {
                continue;
            }
            document.setItemValue(ITEM_SIMILARITY, match.similarity());
            document.setItemValue(ITEM_RANK, rank++);
            result.add(document);
        }

        logger.log(Level.FINE, "├── 🔎 RAG search: {0} match(es) in {1}ms",
                new Object[] { result.size(), System.currentTimeMillis() - l });
        return XMLDataCollectionAdapter.getDataCollection(result, parseItems(items));
    }

    /**
     * Parses the optional item list. If items are requested, the items
     * $similarity and $rank are always added.
     */
    private static List<String> parseItems(String items) {
        List<String> result = new ArrayList<>();
        if (items == null || items.isBlank()) {
            // empty list = all items
            return result;
        }
        for (String item : items.split(",")) {
            if (!item.isBlank()) {
                result.add(item.trim());
            }
        }
        if (!result.contains(ITEM_SIMILARITY)) {
            result.add(ITEM_SIMILARITY);
        }
        if (!result.contains(ITEM_RANK)) {
            result.add(ITEM_RANK);
        }
        return result;
    }
}