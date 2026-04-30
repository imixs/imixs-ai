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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.inject.Inject;
import jakarta.json.stream.JsonParsingException;

/**
 * Singleton EJB that loads the <code>imixs-llm.xml</code> configuration file at
 * application startup and provides lookup methods for endpoint URL and API key
 * by logical endpoint id.
 * <p>
 * The path to the configuration file is configured via MicroProfile Config:
 *
 * <pre>
 * # As environment variable:
 * LLM_CONFIG_FILE=/opt/imixs/imixs-llm.xml
 *
 * # Or as system property:
 * llm.config.file=/opt/imixs/imixs-llm.xml
 * </pre>
 *
 * If the property is not set the service starts with an empty registry and logs
 * a warning.
 * <p>
 * Example imixs-llm.xml:
 *
 * <pre>
 * {@code
 * <imixs-llm>
 *     <endpoint id="my-llm">
 *         <url>http://localhost:8080/</url>
 *         <apikey>${env.LLM_API_KEY}</apikey>
 *         <options>
 *             <temperature>0.2</temperature>
 *             <max_tokens>1024</max_tokens>
 *         </options>
 *     </endpoint>
 * </imixs-llm>
 * }
 * </pre>
 *
 * @author rsoika
 */
@Singleton
@Startup
public class LLMConfigService {

    public static final String ENV_LLM_CONFIG_FILE = "llm.config.file";

    private static final Logger logger = Logger.getLogger(LLMConfigService.class.getName());

    @Inject
    @ConfigProperty(name = ENV_LLM_CONFIG_FILE)
    Optional<String> configFilePath;

    // The parsed XML document - null if config file was not loaded
    private Document configDocument = null;

    /**
     * Loads and parses the imixs-llm.xml from the path configured via the
     * MicroProfile Config property <code>llm.config.file</code>.
     */
    @PostConstruct
    public void init() {
        if (!configFilePath.isPresent() || configFilePath.get().isBlank()) {
            logger.warning("├── ⚠️ LLMConfigService: property '" + ENV_LLM_CONFIG_FILE
                    + "' is not set – LLM endpoint registry is empty.");
            return;
        }

        String path = configFilePath.get().trim();
        logger.info("LLMConfigService: loading config from '" + path + "'");

        try (InputStream is = new FileInputStream(path)) {
            configDocument = parseXML(is);
            logger.info("├── ✅ LLMConfigService: config loaded successfully from '" + path + "'");
        } catch (IOException e) {
            logger.severe("├── ⚠️ LLMConfigService: cannot read '" + path + "': " + e.getMessage());
        } catch (Exception e) {
            logger.severe("├── ⚠️ LLMConfigService: failed to parse '" + path + "': " + e.getMessage());
        }
    }

    /**
     * Returns the URL of the endpoint with the given id, or null if not found.
     *
     * @param endpointId - the id attribute of the &lt;endpoint&gt; element
     * @return the resolved URL string, or null
     */
    public String getURL(String endpointId) {
        return getEndpointValue(endpointId, "url");
    }

    /**
     * Returns the API key of the endpoint with the given id, or null if not
     * configured.
     *
     * @param endpointId - the id attribute of the &lt;endpoint&gt; element
     * @return the resolved API key string, or null
     */
    public String getApiKey(String endpointId) {
        return getEndpointValue(endpointId, "apikey");
    }

    /**
     * Returns true if an endpoint with the given id exists in the config.
     *
     * @param endpointId - the id attribute of the &lt;endpoint&gt; element
     */
    public boolean hasEndpoint(String endpointId) {
        return findEndpointElement(endpointId) != null;
    }

    /**
     * Returns the LLM options configured for the given endpoint, or an empty
     * LLMOptions instance if no <options> element is present, the content is blank,
     * or the JSON is invalid.
     * <p>
     * The <options> element is expected to contain a JSON object as text content:
     * 
     * <pre>
     * {@code
     * <options>{"temperature": 0.2, "max_tokens": 1024}</options>
     * }
     * </pre>
     * <p>
     * Environment placeholders in the form ${env.VAR_NAME} are resolved before
     * parsing, so values like "model": "${env.LLM_MODEL}" are supported.
     * <p>
     * Invalid JSON is logged as a warning and yields an empty LLMOptions, so a
     * single malformed endpoint configuration does not break unrelated workflows.
     *
     * @param endpointId - the id attribute of the <endpoint> element
     * @return an LLMOptions instance, never null
     */
    public LLMOptions getOptions(String endpointId) {
        Element endpoint = findEndpointElement(endpointId);
        if (endpoint == null) {
            return new LLMOptions();
        }
        NodeList optionsNodes = endpoint.getElementsByTagName("options");
        if (optionsNodes.getLength() == 0) {
            return new LLMOptions();
        }
        String content = optionsNodes.item(0).getTextContent();
        if (content == null || content.isBlank()) {
            return new LLMOptions();
        }
        // Resolve ${env.VAR_NAME} placeholders before parsing as JSON
        String resolved = resolveEnvPlaceholders(content.trim());
        try {
            return new LLMOptions(resolved);
        } catch (JsonParsingException e) {
            logger.warning("LLMConfigService: invalid JSON in <options> for endpoint '"
                    + endpointId + "' – ignoring options. " + e.getMessage());
            return new LLMOptions();
        }
    }

    // -------------------------------------------------------------------------
    // Package-private for testing
    // -------------------------------------------------------------------------

    /**
     * Allows unit tests to inject a pre-parsed XML document directly without
     * reading from the filesystem.
     *
     * @param document - a parsed imixs-llm.xml document
     */
    public void setConfigDocument(Document document) {
        this.configDocument = document;
    }

    /**
     * Finds the &lt;endpoint&gt; element with the given id and returns the text
     * content of the specified direct child tag.
     */
    private String getEndpointValue(String endpointId, String tagName) {
        Element endpoint = findEndpointElement(endpointId);
        if (endpoint == null) {
            return null;
        }
        NodeList nodes = endpoint.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        if (value == null || value.isBlank()) {
            return null;
        }
        return resolveEnvPlaceholders(value.trim());
    }

    /**
     * Returns the &lt;endpoint&gt; Element with the matching id attribute, or null
     * if the config document is not loaded or no match is found.
     */
    private Element findEndpointElement(String endpointId) {
        if (configDocument == null || endpointId == null || endpointId.isBlank()) {
            return null;
        }
        NodeList endpoints = configDocument.getElementsByTagName("endpoint");
        for (int i = 0; i < endpoints.getLength(); i++) {
            Element element = (Element) endpoints.item(i);
            if (endpointId.equals(element.getAttribute("id"))) {
                return element;
            }
        }
        logger.warning("LLMConfigService: no endpoint found for id '" + endpointId + "'");
        return null;
    }

    /**
     * Parses an imixs-llm.xml input stream into a DOM Document.
     */
    private Document parseXML(InputStream is) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Disable external entities to prevent XXE attacks
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(is);
        doc.getDocumentElement().normalize();
        return doc;
    }

    /**
     * Resolves ${env.VAR_NAME} placeholders against system environment variables.
     * If the variable is not set, the placeholder is replaced with an empty string
     * and a warning is logged.
     */
    private String resolveEnvPlaceholders(String value) {
        if (value == null || !value.contains("${env.")) {
            return value;
        }
        StringBuilder result = new StringBuilder(value);
        int start;
        while ((start = result.indexOf("${env.")) >= 0) {
            int end = result.indexOf("}", start);
            if (end < 0) {
                break;
            }
            String varName = result.substring(start + 6, end);
            String envValue = System.getenv(varName);
            if (envValue == null) {
                logger.warning("LLMConfigService: environment variable '" + varName
                        + "' is not set – replacing with empty string.");
                envValue = "";
            }
            result.replace(start, end + 1, envValue);
        }
        return result.toString();
    }
}