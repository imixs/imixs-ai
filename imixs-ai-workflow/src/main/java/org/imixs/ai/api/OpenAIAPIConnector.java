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
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.workflow.exceptions.PluginException;

import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

/**
 * The OpenAIAPIConnector provides methods to establish a HTTP connection to a
 * LLM endpoint.
 * <p>
 * The logical model id is resolved via {@link LLMConfigService}, which reads
 * the endpoint URL and optional API key from the <code>imixs-llm.xml</code>
 * configuration file. The caller only provides the model id - it is not exposed
 * to the underlying connection details.
 * <p>
 * Bearer authentication is used when an API key is configured for the model. If
 * no API key is set the request is sent without an Authorization header, which
 * is the typical case for locally hosted LLM instances.
 *
 * @author rsoika
 */
@Stateless
@LocalBean
public class OpenAIAPIConnector implements Serializable {

    private static final long serialVersionUID = 1L;
    private static Logger logger = Logger.getLogger(OpenAIAPIConnector.class.getName());

    public static final String ERROR_CONNECTION = "ERROR_CONNECTION";
    public static final String ENV_LLM_SERVICE_ENDPOINT_TIMEOUT = "llm.service.timeout";

    public static final String ENDPOINT_URI_COMPLETIONS = "v1/chat/completions";
    public static final String ENDPOINT_URI_EMBEDDINGS = "v1/embeddings";

    @Inject
    @ConfigProperty(name = ENV_LLM_SERVICE_ENDPOINT_TIMEOUT, defaultValue = "120000")
    int serviceTimeout;

    @Inject
    LLMConfigService llmConfigService;

    /**
     * Creates a HttpURLConnection to a LLM endpoint identified by the given logical
     * model id.
     * <p>
     * The model id is resolved via {@link LLMConfigService}. If no model with the
     * given id is registered a {@link PluginException} is thrown.
     *
     * @param modelId     - logical model id as defined in imixs-llm.xml
     * @param resourceURI - endpoint resource path, e.g.
     *                    {@link #ENDPOINT_URI_COMPLETIONS}
     * @return an open HttpURLConnection ready for writing the request body
     * @throws PluginException if the model id is unknown or the connection fails
     */
    public HttpURLConnection createHttpConnection(String modelId, String resourceURI)
            throws PluginException {

        // Resolve model configuration by logical id
        LLMModelConfig modelConfig = llmConfigService.getModel(modelId);
        if (modelConfig == null) {
            throw new PluginException(
                    OpenAIAPIConnector.class.getSimpleName(),
                    ERROR_CONNECTION,
                    "Unknown LLM model id: '" + modelId + "' – verify imixs-llm.xml");
        }

        String apiEndpoint = modelConfig.getEndpoint();
        if (apiEndpoint == null || apiEndpoint.isBlank()) {
            throw new PluginException(
                    OpenAIAPIConnector.class.getSimpleName(),
                    ERROR_CONNECTION,
                    "LLM model '" + modelId + "' has no endpoint configured");
        }

        HttpURLConnection conn = null;
        try {
            if (!apiEndpoint.endsWith("/")) {
                apiEndpoint = apiEndpoint + "/";
            }
            URL url = new URL(apiEndpoint + resourceURI);
            conn = (HttpURLConnection) url.openConnection();

            conn.setConnectTimeout(serviceTimeout);
            conn.setReadTimeout(serviceTimeout);

            // Bearer authentication - only if an API key is configured
            if (modelConfig.getApiKey() != null && !modelConfig.getApiKey().isBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + modelConfig.getApiKey());
            }

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);

        } catch (IOException e) {
            logger.severe(e.getMessage());
            throw new PluginException(
                    OpenAIAPIConnector.class.getSimpleName(),
                    ERROR_CONNECTION,
                    "Failed to create connection for model '" + modelId + "': " + e.getMessage(), e);
        }

        return conn;
    }
}