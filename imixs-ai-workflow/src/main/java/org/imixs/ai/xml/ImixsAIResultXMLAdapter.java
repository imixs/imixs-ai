package org.imixs.ai.xml;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.imixs.ai.workflow.ImixsAIResultEvent;
import org.imixs.workflow.ItemCollection;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import jakarta.enterprise.event.Observes;

/**
 * The ImixsAIResultXMLAdapter is a CDI bean that can be used to parse a
 * completion prompt result in XML format. The bean transforms the structure
 * into items of a workitem.
 * <p>
 * This observer Bean is triggered automatically by the OpenAIAPIService after
 * processing a PROMT and feierring a `ImixsAIResultEvent`.
 * <p>
 * The LLMResultXMLAdapter only reacts on the LLMResultEvent in case the the
 * event type==='XML'
 * <p>
 * Example of a result object:
 * 
 * <pre>
  {@code
 
<invoice>
  <cdtr.name>...</cdtr.name>
  <invoice.number>...</invoice.number> 
  <invoice.date>2024-12-31</invoice.date>
  <payment.date>2024-12-31</payment.date>
  <invoice.total>1234.00</invoice.total>
  <cdtr.iban>...</cdtr.iban>
  <cdtr.bic>...</cdtr.bic>
  <invoice.positions>
    <order>...</order>
    <description>...</description>
    <costtype>...</costtype>
    <total>0.00</total>
    <tax>0.00</tax>
  </invoice.positions>
  <invoice.positions>
    <order>...</order>
    <description>...</description>
    <costtype>...</costtype>
    <total>0.00</total>
    <tax>0.00</tax>
  </invoice.positions>
</invoice>      
}</pre>
 * 
 */
public class ImixsAIResultXMLAdapter {
    private static Logger logger = Logger.getLogger(ImixsAIResultXMLAdapter.class.getName());

    public void onEvent(@Observes ImixsAIResultEvent event) {
        if (event.getWorkitem() == null) {
            return;
        }

        // we only adapt the result in case the eventType===JSON
        if ("XML".equalsIgnoreCase(event.getEventType())) {
            // get result string
            String xmlString = event.getPromptResult();

            logger.fine("Prompt Result= " + xmlString);
            xmlString = cleanXML(xmlString);
            ItemCollection xmlItemCol = new ItemCollection();
            parseXML(xmlString, xmlItemCol);
            // now replace all collected values
            // This is to ensure that existing values will be overwritten.
            for (String name : xmlItemCol.getItemNames()) {
                event.getWorkitem().setItemValue(name, xmlItemCol.getItemValue(name));
            }
        }
    }

    /**
     * This helper method removes non-xml data before and after the first and last
     * xml tag.
     * 
     * @param input
     * @return
     */
    public static String cleanXML(String input) {
        // This pattern matches from the first opening XML tag to the last closing XML
        // tag
        Pattern pattern = Pattern.compile("<[^>]+>.*</[^>]+>", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            return matcher.group();
        }
        return null; // Return null if no XML content is found
    }

    /**
     * Applies the values of a Imixs-AI result JSON string to a given workitem.
     *
     * @param resultObject
     * @param workitem
     */
    public static void parseXML(final String xmlString, ItemCollection workitem) {
        try {

            String xmlStringWrapped = wrapTextWithCDATA(xmlString);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder;
            builder = factory.newDocumentBuilder();

            // Parse the XML string to create a Document object
            Document document = builder.parse(new java.io.ByteArrayInputStream(xmlStringWrapped.getBytes()));
            // Get the <result> element
            Element root = document.getDocumentElement();
            NodeList childs = root.getChildNodes();
            for (int i = 0; i < childs.getLength(); i++) {
                Node element = childs.item(i);
                if (element.getNodeType() == Node.ELEMENT_NODE)
                    applyElement((Element) element, workitem);
            }
        } catch (IOException | SAXException | ParserConfigurationException e) {
            logger.severe("Failed to parse Result XML:" + xmlString);
            e.printStackTrace();
        }
    }

    /**
     * Applies the values of a Imixs-AI JSON result object to a given workitem.
     * 
     * @param resultObject
     * @param workitem
     */
    public static void applyElement(final Element element, ItemCollection workitem) {
        if (containsChildNodes(element)) {
            NodeList childNodes = element.getChildNodes();
            String parentItemName = element.getNodeName();
            ItemCollection childItemCol = new ItemCollection();
            for (int i = 0; i < childNodes.getLength(); i++) {
                Node node = childNodes.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    applyElement((Element) node, childItemCol);
                }
            }
            workitem.appendItemValue(parentItemName, childItemCol.getAllItems());

        } else {
            // apply value
            String name = element.getNodeName();
            String value = element.getTextContent();

            // test if value is a number
            if (isDouble(element)) {
                value = cleanDoubleFormatting(value);
                workitem.appendItemValue(name, Double.parseDouble(value));
                return;
            }
            if (isISODateValue(element)) {
                // Define the expected format
                DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE;
                // Parse the string to LocalDate
                LocalDate localDate = LocalDate.parse(value, formatter);
                // Convert LocalDate to java.util.Date
                Date date = Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
                workitem.appendItemValue(name, date);
                return;
            }

            // default to string
            workitem.appendItemValue(name, value);
        }
    }

    /**
     * This method parses a String providing a double value and fixes some minor
     * formatting issues.
     * 
     * e.g.
     * 
     * "1,172.15" => "1172.15"
     * "1.172,15" => "1172.15"
     * 
     * @param value
     * @return
     */
    private static String cleanDoubleFormatting(String value) {
        // remove spaces
        value = value.replace(" ", "");

        // check , and . combination
        int commaPos = value.indexOf(",");
        int digitPos = value.indexOf(".");
        if (commaPos > -1 && digitPos > -1 && commaPos < digitPos) {
            // "1,172.15" => remove ,
            value = value.replace(",", "");
        } else {
            if (commaPos > -1 && digitPos > -1 && digitPos < commaPos) {
                // "1.172,15" remove . and than replace , with .
                value = value.replace(".", "");
                value = value.replace(",", ".");
            }
        }
        return value;
    }

    /**
     * Helper method to parse the given element has the attribute type="double"
     * 
     * @param element
     * @return true if it is type=double
     */
    public static boolean isDouble(final Element element) {
        if (element.hasAttribute("type")) {
            String typeValue = element.getAttribute("type");
            return "double".equalsIgnoreCase(typeValue);
        }
        return false;
    }

    /**
     * Helper method to parse the given element has the attribute type="date"
     * or if the content conforms to the ISO date format (yyyy-MM-dd).
     * 
     * @param element
     * @return true if it is type=date or a ISO Date String
     */
    public static boolean isISODateValue(final Element element) {
        if (element.hasAttribute("type")) {
            String typeValue = element.getAttribute("type");
            if ("date".equalsIgnoreCase(typeValue)) {
                return true;
            }
        }
        // Define the expected format
        DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE;
        try {
            // Parse the string
            @SuppressWarnings("unused")
            LocalDate date = LocalDate.parse(element.getTextContent(), formatter);
            // If parsing is successful, the string is in ISO date format
            return true;
        } catch (DateTimeParseException e) {
            // If parsing fails, the string is not in ISO date format
            return false;
        }
    }

    private static boolean containsChildNodes(Element element) {
        NodeList childNodes = element.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node node = childNodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                return true; // Contains at least one element child node
            }
        }
        return false; // No element child nodes
    }

    /**
     * A Helper method that wraps each tag into a CDATA to cover invalid xml
     * returned by the LLM
     * 
     * @param xml
     * @return
     */
    private static String wrapTextWithCDATA(String xml) {
        // Regulärer Ausdruck, um die Textinhalte in CDATA zu wickeln
        Pattern pattern = Pattern.compile(">([^<]+)<");
        Matcher matcher = pattern.matcher(xml);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String textContent = matcher.group(1).trim();
            if (!textContent.isEmpty()) {
                matcher.appendReplacement(sb, "><![CDATA[" + textContent + "]]><");
            } else {
                matcher.appendReplacement(sb, "><");
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

}
