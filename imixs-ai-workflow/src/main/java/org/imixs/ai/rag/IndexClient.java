package org.imixs.ai.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.imixs.melman.AbstractClient;
import org.imixs.melman.RestAPIException;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.xml.XMLDataCollection;
import org.imixs.workflow.xml.XMLDataCollectionAdapter;
import org.imixs.workflow.xml.XMLDocumentAdapter;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

public class IndexClient extends AbstractClient {

    public IndexClient(String baseUri, String token) {
        super(baseUri);
        // bearer token for every request
        registerClientRequestFilter(
                ctx -> ctx.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        // a retrieval runs inside a workflow transaction - do not wait 5 minutes
        setReadTimeout(30, TimeUnit.SECONDS);
    }

    /**
     * Returns the matches sorted by similarity. Each match has the items 'id',
     * 'similarity' and 'contentChunk'. The list is empty if nothing was found.
     */
    public List<ItemCollection> search(List<Float> embeddings, int maxResults, String categories,
            String modelGroups, String tasks) throws RestAPIException {

        ItemCollection searchData = new ItemCollection();
        searchData.setItemValue("embeddings", embeddings);
        // a missing item means 'no filter' on the server side
        if (categories != null) {
            searchData.setItemValue("categories", categories);
        }
        if (modelGroups != null) {
            searchData.setItemValue("modelGroups", modelGroups);
        }
        if (tasks != null) {
            searchData.setItemValue("tasks", tasks);
        }

        Client client = null;
        try {
            client = newClient();
            Response response = client.target(getBaseURI() + "search")
                    .queryParam("maxResults", maxResults)
                    .request(MediaType.APPLICATION_XML)
                    .post(Entity.entity(XMLDocumentAdapter.getDocument(searchData), MediaType.APPLICATION_XML));

            if (response.getStatus() != Response.Status.OK.getStatusCode()) {
                throw new RestAPIException(IndexClient.class.getSimpleName(), "" + response.getStatus(),
                        response.getStatus() + ": " + response.getStatusInfo().getReasonPhrase());
            }
            XMLDataCollection data = response.readEntity(XMLDataCollection.class);
            if (data == null) {
                return new ArrayList<>();
            }
            return XMLDataCollectionAdapter.putDataCollection(data);

        } catch (ProcessingException e) {
            String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            throw new RestAPIException(IndexClient.class.getSimpleName(),
                    "500", "error search ->" + message, e);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }
}