/****************************************************************************
 * Copyright (c) 2022-2025 Imixs Software Solutions GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
 ****************************************************************************/

package org.imixs.ai.api;

/**
 * Immutable configuration record for a single LLM model entry defined in
 * imixs-llm.xml.
 * <p>
 * Each model entry represents exactly one LLM service endpoint — either a
 * completion model or an embedding model. The distinction is made by the
 * calling code, not by this configuration object.
 * <p>
 * Example imixs-llm.xml entries:
 *
 * <pre>
 * {@code
 * <model id="my-llm">
 *     <endpoint>http://localhost:8080/</endpoint>
 *     <apikey>${env.LLM_API_KEY}</apikey>
 *     <options>
 *         <temperature>0.2</temperature>
 *         <max_tokens>1024</max_tokens>
 *     </options>
 * </model>
 *
 * <model id="my-embeddings">
 *     <endpoint>http://localhost:8081/</endpoint>
 * </model>
 * }
 * </pre>
 *
 * In BPMN configurations the two models are referenced separately:
 *
 * <pre>
 * {@code
 * <imixs-ai name="RAG_INDEX">
 *     <endpoint-completion>my-llm</endpoint-completion>
 *     <endpoint-embeddings>my-embeddings</endpoint-embeddings>
 *     ...
 * </imixs-ai>
 * }
 * </pre>
 *
 * @author rsoika
 */
public class LLMModelConfig {

    private final String id;
    private final String endpoint;
    private final String apiKey;

    // Optional default parameters passed to the LLM API
    private final Double temperature;
    private final Integer maxTokens;

    private LLMModelConfig(Builder builder) {
        this.id = builder.id;
        this.endpoint = builder.endpoint;
        this.apiKey = builder.apiKey;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
    }

    public String getId() {
        return id;
    }

    /**
     * Returns the API endpoint URL of this model.
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Returns the API key for Bearer authentication, or null if not configured.
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Returns the default temperature option, or null if not set.
     */
    public Double getTemperature() {
        return temperature;
    }

    /**
     * Returns the default max_tokens option, or null if not set.
     */
    public Integer getMaxTokens() {
        return maxTokens;
    }

    @Override
    public String toString() {
        return "LLMModelConfig{id='" + id + "'"
                + ", endpoint='" + endpoint + "'"
                + ", apiKey=" + (apiKey != null ? "[SET]" : "[NOT SET]")
                + ", temperature=" + temperature
                + ", maxTokens=" + maxTokens
                + "}";
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static class Builder {

        private final String id;
        private String endpoint;
        private String apiKey;
        private Double temperature;
        private Integer maxTokens;

        public Builder(String id) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("LLMModelConfig id must not be blank");
            }
            this.id = id;
        }

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public LLMModelConfig build() {
            return new LLMModelConfig(this);
        }
    }
}