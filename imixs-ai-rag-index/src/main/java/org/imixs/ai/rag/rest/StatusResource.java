package org.imixs.ai.rag.rest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Hello World resource to verify that the REST API is up and running. This
 * resource will be replaced by the real index and retrieval endpoints.
 * <p>
 * Example: GET /api/status
 */
@Path("/status")
public class StatusResource {

    /**
     * Returns a simple status document.
     *
     * @return a JSON object with service name, status and the current server time
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("service", "imixs-ai-rag-index");
        status.put("status", "UP");
        status.put("time", Instant.now().toString());
        return status;
    }
}
