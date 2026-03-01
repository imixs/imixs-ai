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

package org.imixs.ai.api;

import java.io.IOException;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

/**
 * The OpenAIAPIConnector provides methods to establish a connection to a LLM
 * endpoint. A connection can either be established with an API key or by a
 * BASIC authentication with username/password.
 * 
 * <p>
 * The connector is used by the `OpenAIAPIService` class.
 * 
 * 
 * @author rsoika
 *
 */
@Stateless
@LocalBean
public class OpenAIAPIConnector implements Serializable {

    private static final long serialVersionUID = 1L;
    private static Logger logger = Logger.getLogger(OpenAIAPIConnector.class.getName());

    public static final String ERROR_CONNECTION = "ERROR_CONNECTION";
    public static final String ENV_LLM_SERVICE_ENDPOINT = "llm.service.endpoint";
    public static final String ENV_LLM_SERVICE_ENDPOINT_APIKEY = "llm.service.endpoint.apikey";
    public static final String ENV_LLM_SERVICE_ENDPOINT_USER = "llm.service.endpoint.user";
    public static final String ENV_LLM_SERVICE_ENDPOINT_PASSWORD = "llm.service.endpoint.password";
    public static final String ENV_LLM_SERVICE_ENDPOINT_TIMEOUT = "llm.service.timeout";

    public static final String ENDPOINT_URI_COMPLETIONS = "v1/chat/completions";
    // public static final String ENDPOINT_URI_COMPLETIONS = "completion";

    public static final String ENDPOINT_URI_EMBEDDINGS = "v1/embeddings";

    @Inject
    @ConfigProperty(name = ENV_LLM_SERVICE_ENDPOINT)
    Optional<String> serviceEndpoint;

    @Inject
    @ConfigProperty(name = ENV_LLM_SERVICE_ENDPOINT_USER)
    Optional<String> serviceEndpointUser;

    @Inject
    @ConfigProperty(name = ENV_LLM_SERVICE_ENDPOINT_APIKEY)
    Optional<String> serviceEndpointApiKey;

    @Inject
    @ConfigProperty(name = ENV_LLM_SERVICE_ENDPOINT_PASSWORD)
    Optional<String> serviceEndpointPassword;

    @Inject
    @ConfigProperty(name = ENV_LLM_SERVICE_ENDPOINT_TIMEOUT, defaultValue = "120000")
    int serviceTimeout;

    public void setServiceEndpointApiKey(Optional<String> serviceEndpointApiKey) {
        this.serviceEndpointApiKey = serviceEndpointApiKey;
    }

    public void setServiceEndpointUser(Optional<String> serviceEndpointUser) {
        this.serviceEndpointUser = serviceEndpointUser;
    }

    public void setServiceEndpointPassword(Optional<String> serviceEndpointPassword) {
        this.serviceEndpointPassword = serviceEndpointPassword;
    }

    /**
     * Builds aULConnection to a LLM Endpoint
     * 
     * @param apiEndpoint - optional service endpoint
     * @param resourceURI - endpoint resource
     * @throws PluginException
     */
    public HttpURLConnection createHttpConnection(String apiEndpoint, String resourceURI)
            throws PluginException {

        HttpURLConnection conn = null;
        try {
            if (apiEndpoint == null) {
                // default to global endpoint
                if (!serviceEndpoint.isPresent()) {
                    throw new PluginException(OpenAIAPIConnector.class.getSimpleName(), ERROR_CONNECTION,
                            ENV_LLM_SERVICE_ENDPOINT + " is empty!");
                }
                apiEndpoint = serviceEndpoint.get();
            }
            if (!apiEndpoint.endsWith("/")) {
                apiEndpoint = apiEndpoint + "/";
            }
            URL url = new URL(apiEndpoint + resourceURI);
            conn = (HttpURLConnection) url.openConnection();

            conn.setConnectTimeout(serviceTimeout); // set timeout to 5 seconds
            conn.setReadTimeout(serviceTimeout);

            // Set Authentication - API key takes precedence
            if (serviceEndpointApiKey.isPresent()) {
                // Bearer Authentication with API key
                conn.setRequestProperty("Authorization", "Bearer " + serviceEndpointApiKey.get());
            } else if (serviceEndpointUser.isPresent() && serviceEndpointPassword.isPresent()) {
                // Basic Authentication with user/password
                String auth = serviceEndpointUser.get() + ":" + serviceEndpointPassword.get();
                byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
                String authHeaderValue = "Basic " + new String(encodedAuth);
                conn.setRequestProperty("Authorization", authHeaderValue);
            }

            // Set the appropriate HTTP method
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);

        } catch (IOException e) {
            logger.severe(e.getMessage());
            throw new PluginException(
                    OpenAIAPIConnector.class.getSimpleName(),
                    ERROR_CONNECTION,
                    "Failed to create connection to endpoint '" + apiEndpoint + "' : " + e.getMessage(), e);
        }
        return conn;
    }

}
