package org.imixs.ai.rag.rest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Protects the REST API with static bearer tokens (one per client application).
 * <p>
 * Configuration (environment variable or MicroProfile Config property):
 * RAG_API_TOKENS=workflow-app1=<token>,workflow-app2=<token>
 * <p>
 * The filter fails closed: if no token is configured, every request is
 * rejected.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION)
public class ApiTokenFilter implements ContainerRequestFilter {

    public static final String ENV_RAG_API_TOKENS = "RAG_API_TOKENS";
    public static final String PROPERTY_CLIENT_NAME = "rag.client.name";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Logger logger = Logger.getLogger(ApiTokenFilter.class.getName());

    @Inject
    @ConfigProperty(name = ENV_RAG_API_TOKENS)
    Optional<String> tokenConfig;

    // client name -> token (as bytes for constant-time comparison)
    private final Map<String, byte[]> tokens = new LinkedHashMap<>();

    @PostConstruct
    void init() {
        if (tokenConfig.isEmpty() || tokenConfig.get().isBlank()) {
            logger.warning("No API tokens configured (" + ENV_RAG_API_TOKENS + ") - all requests will be rejected!");
            return;
        }
        for (String entry : tokenConfig.get().split(",")) {
            int pos = entry.indexOf('=');
            if (pos <= 0 || pos == entry.length() - 1) {
                logger.warning("Ignoring invalid token entry in " + ENV_RAG_API_TOKENS);
                continue;
            }
            String name = entry.substring(0, pos).trim();
            String token = entry.substring(pos + 1).trim();
            tokens.put(name, token.getBytes(StandardCharsets.UTF_8));
        }
        logger.info("API token filter active for " + tokens.size() + " client(s): " + tokens.keySet());
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String clientName = authenticate(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION));
        if (clientName == null) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer").build());
            return;
        }
        // make the client name available to resources (e.g. for logging)
        requestContext.setProperty(PROPERTY_CLIENT_NAME, clientName);
    }

    /**
     * Returns the client name for a valid bearer token or null. All entries are
     * compared without early exit and in constant time per entry.
     */
    private String authenticate(String authHeader) {
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }
        byte[] presented = authHeader.substring(BEARER_PREFIX.length()).trim()
                .getBytes(StandardCharsets.UTF_8);
        String match = null;
        for (Map.Entry<String, byte[]> entry : tokens.entrySet()) {
            if (MessageDigest.isEqual(entry.getValue(), presented)) {
                match = entry.getKey();
            }
        }
        return match;
    }
}