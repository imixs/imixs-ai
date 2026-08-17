/****************************************************************************
 * Copyright (c) 2022-2025 Imixs Software Solutions GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
 ****************************************************************************/
package org.imixs.ai.bpmn.handler;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.imixs.ai.bpmn.util.AIModelManager;
import org.imixs.ai.workflow.ImixsAIPromptEvent;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.exceptions.AdapterException;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.util.XMLParser;
import org.imixs.workflow.util.XMLTag;
import org.openbpmn.bpmn.BPMNModel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * The BPMNFormPromptHandler resolves <bpmn.form /> tags in a prompt template.
 *
 * Supported tag variants:
 *
 * <bpmn.form />
 * Resolves all form items into a combined block containing the target XML
 * skeleton (root element "data") plus a field mapping description.
 *
 * <bpmn.form root="invoice" />
 * Same as above, but uses "invoice" as the root element name of the
 * generated XML skeleton instead of the default "data".
 *
 * <bpmn.form items="invoice.number, invoice.date" />
 * Resolves only the named items.
 *
 * <bpmn.form readonly="ignore" />
 * Excludes readonly fields entirely instead of including them as
 * [readonly] context information.
 *
 * The generated block is intended to replace both the hand-written XML
 * target structure and the hand-written field mapping suggestions that a
 * BPMN modeller would otherwise maintain manually in the prompt template.
 * The resulting XML skeleton is compatible with AIResultHandlerXML, which
 * only evaluates the child elements of the XML root - the root element name
 * itself is never interpreted by that adapter.
 *
 * @author rsoika
 */
public class BPMNFormPromptHandler {

    public static final String PROMPT_ERROR = "PROMPT_ERROR";
    private static final String DEFAULT_ROOT_NAME = "data";
    private static final Logger logger = Logger.getLogger(BPMNFormPromptHandler.class.getName());

    /**
     * Set of form types that render as a single-choice select component.
     */
    private static final Set<String> SINGLE_SELECT_TYPES = Set.of(
            "selectOneMenu", "selectOneRadio", "selectOneRadioPageDirection");

    /**
     * Set of form types that render as a multi-choice select component.
     */
    private static final Set<String> MULTI_SELECT_TYPES = Set.of(
            "selectManyCheckbox", "selectManyCheckboxPageDirection");

    /**
     * Fixed instruction header prepended before the generated XML skeleton.
     * This is deliberately generic and independent of the concrete form
     * content.
     */
    private static final String INTRO_INSTRUCTIONS = "Transfer the data into an XML object with the following structure:";

    /**
     * Fixed instruction footer appended after the generated XML skeleton and
     * field mapping. This is deliberately generic and independent of the
     * concrete form content - it describes how the LLM should behave when
     * producing output for AIResultHandlerXML, not what data to extract.
     */
    private static final String OUTPUT_INSTRUCTIONS = """
            Output only the XML object above! Do not add explanations or comments, \
            and do not create any XML tags other than those shown. The example \
            values (e.g. "...", "2024-12-31", "1234.00") only illustrate the \
            expected format - if you don't have data for a field, leave the \
            corresponding tag empty.""";

    @Inject
    AIModelManager sharedModelManager;

    /**
     * Test-only setter allowing to inject a SharedModelManager instance from
     * outside this package, bypassing CDI. Intended to be used together with
     * a MockWorkflowEnvironment in unit/integration tests.
     *
     * @param sharedModelManager a pre-configured SharedModelManager instance
     */
    public void setSharedModelManager(AIModelManager sharedModelManager) {
        this.sharedModelManager = sharedModelManager;
    }

    public void onEvent(@Observes ImixsAIPromptEvent event) throws AdapterException {
        if (event == null || event.getWorkitem() == null) {
            return;
        }
        String prompt = event.getPromptTemplate();
        if (prompt == null || prompt.isBlank()) {
            return;
        }

        List<XMLTag> xmlTagList = XMLParser.parseTagMatches(prompt, "bpmn.form");
        for (XMLTag xmlTag : xmlTagList) {
            String itemsAttribute = xmlTag.getAttribute("items");
            boolean excludeReadonly = "ignore".equalsIgnoreCase(xmlTag.getAttribute("readonly"));
            String rootAttribute = xmlTag.getAttribute("root");
            String rootName = (rootAttribute != null && !rootAttribute.isBlank()) ? rootAttribute.trim()
                    : DEFAULT_ROOT_NAME;

            String formDefinition = fetchFormDefinitionByWorkitem(event.getWorkitem());
            String formPromptBlock = buildFormPromptBlock(formDefinition, itemsAttribute, excludeReadonly, rootName);

            // Replace tag in prompt if content was found
            if (formPromptBlock != null) {
                prompt = prompt.replace(xmlTag.getOuterXML(), formPromptBlock);
            }
        }
        event.setPromptTemplate(prompt);
    }

    /**
     * Helper method that reads a form definition associated with the task the
     * current workitem is associated to.
     *
     * @param workitem
     * @return
     */
    public String fetchFormDefinitionByWorkitem(ItemCollection workitem) {

        // return if no modelversion is defined
        if (workitem == null || workitem.getModelVersion().isBlank()) {
            return "";
        }
        ItemCollection task;
        try {
            BPMNModel model = sharedModelManager.getModelManager().getModelByWorkitem(workitem);
            task = sharedModelManager.getModelManager().loadTask(workitem, model);
        } catch (ModelException e) {
            logger.fine("unable to parse data object in model: " + e.getMessage());
            return "";
        }

        return sharedModelManager.fetchFormDefinitionByTask(task);
    }

    /**
     * Parses an <imixs-form> definition and builds a combined prompt block
     * consisting of an XML target skeleton followed by a field mapping
     * description. This block is intended to fully replace the manually
     * written XML structure and mapping suggestions in a system prompt.
     *
     * @param formDefinition  the raw <imixs-form> XML
     * @param itemsFilter     optional comma separated list of item names to
     *                        restrict the output to (may be null or blank)
     * @param excludeReadonly if true, fields resolved as readonly (item or
     *                        cascaded from the enclosing section) are excluded
     *                        entirely; if false, readonly fields are included
     *                        in the mapping and flagged as [readonly]
     * @param rootName        the root element name to use for the generated
     *                        XML skeleton (e.g. "invoice")
     * @return the generated prompt block (never null, may be an empty string)
     */
    protected String buildFormPromptBlock(String formDefinition, String itemsFilter, boolean excludeReadonly,
            String rootName) {
        if (formDefinition == null || formDefinition.isBlank()) {
            return "";
        }

        Set<String> filterSet = null;
        if (itemsFilter != null && !itemsFilter.isBlank()) {
            filterSet = new HashSet<>();
            for (String name : itemsFilter.split(",")) {
                filterSet.add(name.trim());
            }
        }

        Document doc = parseXML(formDefinition);
        if (doc == null) {
            return "";
        }

        List<Element> items = collectItems(doc, filterSet, excludeReadonly);
        if (items.isEmpty()) {
            return "";
        }

        StringBuilder block = new StringBuilder();
        block.append(INTRO_INSTRUCTIONS);
        block.append("\n\n");
        block.append(buildFormXmlSkeleton(items, rootName));
        block.append("\n");
        block.append("Field mapping:\n");
        block.append(buildFieldMappingText(items));
        block.append("\n");
        block.append(OUTPUT_INSTRUCTIONS);
        block.append("\n");
        return block.toString();
    }

    /**
     * Collects all <item> elements from a parsed form definition that match
     * the given items filter and readonly exclusion rule.
     *
     * @param doc             the parsed <imixs-form> document
     * @param filterSet       optional set of item names to restrict the
     *                        result to (null means no filtering)
     * @param excludeReadonly if true, items resolved as readonly are skipped
     * @return the list of matching <item> elements, in document order
     */
    private List<Element> collectItems(Document doc, Set<String> filterSet, boolean excludeReadonly) {
        List<Element> items = new ArrayList<>();
        NodeList itemNodes = doc.getElementsByTagName("item");
        for (int i = 0; i < itemNodes.getLength(); i++) {
            Element itemElement = (Element) itemNodes.item(i);
            String name = itemElement.getAttribute("name");
            if (name.isBlank()) {
                continue;
            }
            if (filterSet != null && !filterSet.contains(name)) {
                continue;
            }
            if (excludeReadonly && isReadonly(itemElement)) {
                continue;
            }
            items.add(itemElement);
        }
        return items;
    }

    /**
     * Builds the target XML skeleton for a list of form items, e.g.:
     *
     * <pre>
     * &lt;invoice&gt;
     *   &lt;invoice.number&gt;...&lt;/invoice.number&gt;
     *   &lt;invoice.date type="date"&gt;2024-12-31&lt;/invoice.date&gt;
     *   &lt;invoice.total type="double"&gt;1234.00&lt;/invoice.total&gt;
     * &lt;/invoice&gt;
     * </pre>
     *
     * The type="date"/type="double" attributes are set exactly for the cases
     * that AIResultHandlerXML relies on to correctly convert the value back
     * into the workitem (isDouble()/isISODateValue()). The root element name
     * itself is not interpreted by that adapter and can be chosen freely.
     *
     * @param items    the form items to render, in document order
     * @param rootName the root element name to use
     * @return the generated XML skeleton, terminated by a newline
     */
    private String buildFormXmlSkeleton(List<Element> items, String rootName) {
        StringBuilder xml = new StringBuilder();
        xml.append("<").append(rootName).append(">\n");
        for (Element itemElement : items) {
            String name = itemElement.getAttribute("name");
            String formType = itemElement.getAttribute("type");
            String llmType = mapFormTypeToLLMType(formType);

            String typeAttribute = "";
            if ("date".equals(llmType) || "double".equals(llmType)) {
                typeAttribute = " type=\"" + llmType + "\"";
            }

            xml.append("  <").append(name).append(typeAttribute).append(">")
                    .append(buildExampleValue(llmType))
                    .append("</").append(name).append(">\n");
        }
        xml.append("</").append(rootName).append(">\n");
        return xml.toString();
    }

    /**
     * Builds the field mapping description for a list of form items, e.g.:
     *
     * - invoice.total (double, ISO 4217, decimal point, no thousand separator): The
     * total invoice amount. [required]
     *
     * @param items the form items to render, in document order
     * @return the generated mapping text
     */
    private String buildFieldMappingText(List<Element> items) {
        StringBuilder result = new StringBuilder();
        for (Element itemElement : items) {
            String name = itemElement.getAttribute("name");
            String formType = itemElement.getAttribute("type");
            String label = itemElement.getAttribute("label");
            String description = itemElement.getAttribute("description");
            String options = itemElement.getAttribute("options");
            boolean required = "true".equalsIgnoreCase(itemElement.getAttribute("required"));
            boolean readonly = isReadonly(itemElement);

            result.append(buildFieldLine(name, formType, label, description, options));
        }
        return result.toString();
    }

    /**
     * Provides a representative example value for a given LLM data type, used
     * as placeholder content in the generated XML skeleton.
     */
    private String buildExampleValue(String llmType) {
        switch (llmType) {
            case "date":
                return "2024-12-31";
            case "double":
                return "1234.00";
            case "boolean":
                return "true";
            default:
                return "...";
        }
    }

    /**
     * Resolves the effective 'readonly' state of an item, applying the
     * cascading rule from the Imixs Form Specification: an item-level
     * 'readonly' attribute overrides the enclosing section's default.
     */
    private boolean isReadonly(Element itemElement) {
        String itemValue = itemElement.getAttribute("readonly");
        if (!itemValue.isBlank()) {
            return "true".equalsIgnoreCase(itemValue);
        }
        org.w3c.dom.Node parent = itemElement.getParentNode();
        if (parent instanceof Element) {
            String sectionValue = ((Element) parent).getAttribute("readonly");
            return "true".equalsIgnoreCase(sectionValue);
        }
        return false;
    }

    /**
     * Builds a single mapping line for one form item, e.g.:
     *
     * - invoice.currency (text, one of: EUR, CHF, GBP, USD): Currency
     * - invoice.total (double, ISO 4217, decimal point, no thousand separator): The
     * total invoice amount. [required]
     * - document.company (text): Company: [readonly]
     *
     * Falls back to the UI 'label' if no 'description' attribute is present.
     *
     * @param name        the item name (e.g. "invoice.total")
     * @param formType    the Imixs form input type (e.g. "currency")
     * @param label       the UI label, used as fallback text
     * @param description the optional LLM extraction hint
     * @param options     the raw 'options' attribute for select-type items (may be
     *                    null)
     * @param required    whether the field is mandatory
     * @param readonly    whether the field is read-only (context only, not an
     *                    extraction target)
     * @return one formatted line, terminated by a newline
     */
    private String buildFieldLine(String name, String formType, String label, String description,
            String options) {
        String llmType = mapFormTypeToLLMType(formType);
        String formatHint = mapFormTypeToFormatHint(formType);
        String enumHint = buildEnumHint(formType, options);

        StringBuilder line = new StringBuilder();
        line.append("- ").append(name).append(" (").append(llmType);
        if (formatHint != null) {
            line.append(", ").append(formatHint);
        }
        if (enumHint != null) {
            line.append(", ").append(enumHint);
        }
        line.append(")");

        String text = (description != null && !description.isBlank()) ? description : label;
        if (text != null && !text.isBlank()) {

            // cut ending ':'
            text = text.trim();
            if (text.endsWith(":")) {
                text = text.substring(0, text.length() - 1);
            }

            line.append(": ").append(text.trim());
        }

        line.append("\n");
        return line.toString();
    }

    /**
     * Builds an enum-style hint for select-type items, e.g.:
     *
     * "one of: EUR, CHF, GBP, USD"
     * "one or more of: red, green, blue"
     * "true or false"
     *
     * Returns null for non-select types or if no options are defined.
     */
    private String buildEnumHint(String formType, String options) {
        if (formType == null) {
            return null;
        }
        if ("selectBooleanCheckbox".equals(formType)) {
            return "true or false";
        }
        if (!SINGLE_SELECT_TYPES.contains(formType) && !MULTI_SELECT_TYPES.contains(formType)) {
            return null;
        }
        if (options == null || options.isBlank()) {
            return null;
        }

        // Options are semicolon separated. Each option may define an optional
        // display label using a '|' separator (label|value). We only need the
        // stored value here, since that is what gets written back to the workitem.
        StringBuilder values = new StringBuilder();
        for (String option : options.split(";")) {
            String value = option.trim();
            if (value.isEmpty()) {
                continue;
            }
            int pipeIndex = value.indexOf('|');
            if (pipeIndex >= 0) {
                value = value.substring(0, pipeIndex).trim();
            }
            if (values.length() > 0) {
                values.append(", ");
            }
            values.append(value);
        }
        if (values.length() == 0) {
            return null;
        }

        String prefix = MULTI_SELECT_TYPES.contains(formType) ? "one or more of: " : "one of: ";
        return prefix + values;
    }

    /**
     * Maps an Imixs form input type to the corresponding data type used in the
     * LLM output XML (text, date, double, boolean).
     */
    private String mapFormTypeToLLMType(String formType) {
        if (formType == null) {
            return "text";
        }
        switch (formType) {
            case "date":
                return "date";
            case "currency":
                return "double";
            case "selectBooleanCheckbox":
                return "boolean";
            case "custom":
                // Custom components (e.g. markdown editor) are treated as
                // plain text for LLM purposes - the 'path' attribute is a UI
                // rendering detail and not relevant for extraction/output.
                return "text";
            default:
                return "text";
        }
    }

    /**
     * Provides an additional format hint for the LLM depending on the form
     * input type. Returns null if no hint is required.
     */
    private String mapFormTypeToFormatHint(String formType) {
        if (formType == null) {
            return null;
        }
        switch (formType) {
            case "date":
                return "format YYYY-MM-DD";
            case "currency":
                return "ISO 4217, decimal point, no thousand separator";
            default:
                return null;
        }
    }

    /**
     * Parses a raw XML string into a DOM Document. External entities are
     * disabled to prevent XXE attacks, since the form definition may
     * originate from a shared/imported BPMN model.
     */
    private Document parseXML(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            logger.log(Level.WARNING, "unable to parse form definition: " + e.getMessage(), e);
            return null;
        }
    }
}