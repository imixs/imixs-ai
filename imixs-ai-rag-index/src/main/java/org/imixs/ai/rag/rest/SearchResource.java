package org.imixs.ai.rag.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.imixs.ai.rag.cluster.ClusterException;
import org.imixs.ai.rag.cluster.ClusterService;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.xml.XMLDataCollectionAdapter;
import org.imixs.workflow.xml.XMLDocument;
import org.imixs.workflow.xml.XMLDocumentAdapter;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

/**
 * REST endpoint for semantic retrieval.
 * <p>
 * POST /api/search?maxResults=3
 * <p>
 * The payload is an ItemCollection with the items 'embeddings', 'categories',
 * 'modelGroups' and 'tasks'. The response is a collection of ItemCollections
 * with the items 'id', 'similarity' and 'contentChunk'.
 */
@Path("/search")
public class SearchResource {

    private static final Logger logger = Logger.getLogger(SearchResource.class.getName());

    @Inject
    ClusterService clusterService;

    @POST
    @Consumes({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    @Produces({ MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML })
    public Response search(XMLDocument xmlDocument, @QueryParam("maxResults") @DefaultValue("3") int maxResults) {

        if (xmlDocument == null) {
            return badRequest("request body missing");
        }
        if (maxResults < 1 || maxResults > ClusterService.SEARCH_LIMIT_MAX) {
            return badRequest("maxResults must be between 1 and " + ClusterService.SEARCH_LIMIT_MAX);
        }

        ItemCollection searchData = XMLDocumentAdapter.putDocument(xmlDocument);

        // convert the vector - after a JSON roundtrip the values may be Double or
        // Integer
        List<Float> embeddings = new ArrayList<>();
        for (Object value : searchData.getItemValue("embeddings")) {
            if (!(value instanceof Number)) {
                return badRequest("embeddings must contain numbers only");
            }
            embeddings.add(((Number) value).floatValue());
        }
        if (embeddings.size() != ClusterService.DIMENSIONS) {
            return badRequest("embeddings must have " + ClusterService.DIMENSIONS + " dimensions");
        }

        // a missing item means 'no filter' (null) - this differs from an empty string
        String categories = getOptionalString(searchData, "categories");
        String modelGroups = getOptionalString(searchData, "modelGroups");
        String tasks = getOptionalString(searchData, "tasks");

        if (!clusterService.isConnected()) {
            return Response.status(Status.SERVICE_UNAVAILABLE).build();
        }

        try {
            List<ItemCollection> result = clusterService.searchEmbeddings(embeddings, maxResults, categories,
                    modelGroups, tasks);
            return Response.ok(XMLDataCollectionAdapter.getDataCollection(result)).build();
        } catch (ClusterException e) {
            logger.log(Level.SEVERE, "search failed: " + e.getMessage(), e);
            return Response.status(Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    private String getOptionalString(ItemCollection data, String name) {
        return data.hasItem(name) ? data.getItemValueString(name) : null;
    }

    private Response badRequest(String message) {
        return Response.status(Status.BAD_REQUEST).entity(message).type(MediaType.TEXT_PLAIN).build();
    }

}
