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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.inject.Inject;

/**
 * Singleton EJB that loads the <code>imixs-llm.xml</code> configuration file at
 * application startup and provides a lookup map of {@link LLMModelConfig}
 * entries by their logical model id.
 * <p>
 * The path to the configuration file is resolved via MicroProfile Config using
 * the property key <code>llm.config.file</code>. The deployer can set this as
 * an environment variable, a system property, or in
 * microprofile-config.properties:
 *
 * <pre>
 * # As environment variable (MicroProfile maps LLM_CONFIG_FILE automatically):
 * LLM_CONFIG_FILE=/opt/imixs/imixs-llm.xml
 *
 * # Or as system property:
 * llm.config.file=/opt/imixs/imixs-llm.xml
 * </pre>
 *
 * If the property is not set the service starts with an empty registry and logs
 * a warning. This is intentional so that deployments without LLM support are
 * not broken by a missing config file.
 * <p>
 * Usage in BPMN adapters / plugins:
 *
 * <pre>
 * {@code
 * @Inject
 * LLMConfigService llmConfigService;
 *
 * LLMModelConfig config = llmConfigService.getModel("my-llm");
 * String endpoint = config.getEndpoint();
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

    // Immutable map after startup - key = model id
    private Map<String, LLMModelConfig> modelRegistry = Collections.emptyMap();

    /**
     * Loads and parses the imixs-llm.xml from the path configured via the
     * MicroProfile Config property <code>llm.config.file</code>.
     */
    @PostConstruct
    public void init() {
        if (!configFilePath.isPresent() || configFilePath.get().isBlank()) {
            logger.warning("LLMConfigService: property '" + ENV_LLM_CONFIG_FILE
                    + "' is not set – LLM model registry is empty.");
            return;
        }

        String path = configFilePath.get().trim();
        logger.info("LLMConfigService: loading config from '" + path + "'");

        try (InputStream is = new FileInputStream(path)) {
            modelRegistry = parseConfig(is);
            logger.info("LLMConfigService: loaded " + modelRegistry.size()
                    + " model(s): " + modelRegistry.keySet());
        } catch (IOException e) {
            logger.severe("LLMConfigService: cannot read '" + path + "': " + e.getMessage());
        } catch (Exception e) {
            logger.severe("LLMConfigService: failed to parse '" + path + "': " + e.getMessage());
        }
    }

    /**
     * Returns the {@link LLMModelConfig} for the given logical model id.
     *
     * @param modelId - the id attribute of the &lt;model&gt; element
     * @return the matching config, or {@code null} if not found
     */
    public LLMModelConfig getModel(String modelId) {
        return modelRegistry.get(modelId);
    }

    /**
     * Returns an unmodifiable view of all registered models.
     */
    public Map<String, LLMModelConfig> getAllModels() {
        return Collections.unmodifiableMap(modelRegistry);
    }

    /**
     * Returns true if a model with the given id is registered.
     *
     * @param modelId - the logical model id
     */
    public boolean hasModel(String modelId) {
        return modelRegistry.containsKey(modelId);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Parses the imixs-llm.xml input stream and builds the model registry map.
     *
     * @param is - input stream of the config file
     * @return map of model id to LLMModelConfig
     */
    private Map<String, LLMModelConfig> parseConfig(InputStream is) throws Exception {
        Map<String, LLMModelConfig> registry = new LinkedHashMap<>();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Disable external entities to prevent XXE attacks
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(is);
        doc.getDocumentElement().normalize();

        NodeList modelNodes = doc.getElementsByTagName("model");
        for (int i = 0; i < modelNodes.getLength(); i++) {
            Node node = modelNodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element modelElement = (Element) node;
            String id = modelElement.getAttribute("id");
            if (id == null || id.isBlank()) {
                logger.warning("LLMConfigService: skipping <model> element without id attribute.");
                continue;
            }

            LLMModelConfig.Builder configBuilder = new LLMModelConfig.Builder(id);

            // Read the single generic endpoint
            String endpoint = getTextContent(modelElement, "endpoint");
            if (endpoint != null) {
                configBuilder.endpoint(resolveEnvPlaceholders(endpoint));
            } else {
                logger.warning("LLMConfigService: model '" + id + "' has no <endpoint> element – skipping.");
                continue;
            }

            // Read apikey
            String apiKey = getTextContent(modelElement, "apikey");
            if (apiKey != null) {
                configBuilder.apiKey(resolveEnvPlaceholders(apiKey));
            }

            // Read optional <options> child elements
            NodeList optionsNodes = modelElement.getElementsByTagName("options");
            if (optionsNodes.getLength() > 0) {
                Element optionsElement = (Element) optionsNodes.item(0);

                String temperature = getTextContent(optionsElement, "temperature");
                if (temperature != null) {
                    try {
                        configBuilder.temperature(Double.parseDouble(temperature.trim()));
                    } catch (NumberFormatException e) {
                        logger.warning("LLMConfigService: invalid temperature value '" + temperature
                                + "' for model '" + id + "' – ignored.");
                    }
                }

                String maxTokens = getTextContent(optionsElement, "max_tokens");
                if (maxTokens != null) {
                    try {
                        configBuilder.maxTokens(Integer.parseInt(maxTokens.trim()));
                    } catch (NumberFormatException e) {
                        logger.warning("LLMConfigService: invalid max_tokens value '" + maxTokens
                                + "' for model '" + id + "' – ignored.");
                    }
                }
            }

            LLMModelConfig config = configBuilder.build();
            registry.put(id, config);
            logger.fine("LLMConfigService: registered model: " + config);
        }

        return registry;
    }

    /**
     * Returns the trimmed text content of the first child element with the given
     * tag name, or null if the element does not exist or is empty.
     *
     * @param parent  - parent XML element
     * @param tagName - child tag name to look up
     */
    private String getTextContent(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * Resolves ${env.VAR_NAME} placeholders in XML values against system
     * environment variables. This is separate from MicroProfile Config, which only
     * resolves the config file path itself.
     * <p>
     * If the referenced variable is not set, the placeholder is replaced with an
     * empty string and a warning is logged.
     *
     * @param value - raw string possibly containing ${env.XXX} placeholders
     * @return resolved string
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
                break; // Malformed placeholder - stop
            }
            String varName = result.substring(start + 6, end); // skip "${env."
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