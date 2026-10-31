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

/**
 * REST client for the search endpoint of the RAG service.
 * <p>
 * The base URI is the REST API of the RAG service (not the embeddings endpoint
 * of the LLM!).
 */
public class IndexClient extends AbstractClient {

    private static final int MAX_ERROR_LENGTH = 200;

    /**
     * @param baseUri the REST API of the RAG service
     * @param token   optional bearer token. A null or blank value sends no
     *                Authorization header.
     */
    public IndexClient(String baseUri, String token) {
        super(baseUri);
        // bearer token for every request - only if a token is configured
        if (token != null && !token.isBlank()) {
            registerClientRequestFilter(
                    ctx -> ctx.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        }
        // a retrieval runs inside a workflow transaction - do not wait 5 minutes
        setReadTimeout(30, TimeUnit.SECONDS);
    }

    /**
     * Searches the index for the given embedding vector. Returns the matches
     * sorted by similarity. Each match has the items 'id', 'similarity' and
     * 'contentChunk'. The list is empty if nothing was found.
     *
     * @param embeddings the query vector
     * @param maxResults maximum number of documents
     * @param filter     optional filter (null = no filter)
     */
    public List<ItemCollection> search(List<Float> embeddings, int maxResults, SearchFilter filter)
            throws RestAPIException {

        SearchFilter searchFilter = (filter != null) ? filter : SearchFilter.create();

        ItemCollection searchData = new ItemCollection();
        searchData.setItemValue("embeddings", embeddings);
        // a missing item means 'no filter' on the server side
        if (searchFilter.getCategory() != null) {
            searchData.setItemValue("categories", searchFilter.getCategory());
        }
        if (searchFilter.getModelGroups() != null) {
            searchData.setItemValue("modelGroups", searchFilter.getModelGroups());
        }
        if (searchFilter.getTasks() != null) {
            searchData.setItemValue("tasks", searchFilter.getTasks());
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
                        response.getStatus() + ": " + getErrorMessage(response));
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

    /**
     * Returns the reason phrase of a failed response. If the server sent a plain
     * text message (e.g. a 400 'bad request' explanation), it is appended.
     */
    private String getErrorMessage(Response response) {
        String reason = response.getStatusInfo().getReasonPhrase();
        try {
            if (response.hasEntity()) {
                String body = response.readEntity(String.class);
                if (body != null && !body.isBlank()) {
                    body = body.trim();
                    return reason + " - "
                            + (body.length() > MAX_ERROR_LENGTH ? body.substring(0, MAX_ERROR_LENGTH) : body);
                }
            }
        } catch (ProcessingException e) {
            // ignore - the status code is sufficient
        }
        return reason;
    }
}