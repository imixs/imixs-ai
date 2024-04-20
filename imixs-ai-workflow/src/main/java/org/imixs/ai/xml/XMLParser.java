package org.imixs.ai.xml;

import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * This is a XML Parser class to simplify the parsing of a Imixs-AI XML result
 * object.
 * 
 * For example a result object may look like this:
 * 
 * <pre>
  {@code
 
  <?xml version="1.0" encoding="UTF-8"?>
  <ResultData>
     <result>some data</result>
  </ResultData>

}</pre>
 * 
 */
public class XMLParser {
    private static Logger logger = Logger.getLogger(XMLParser.class.getName());

    /**
     * Static one liner method to parse the result tag of a Imixs-AI XML String
     * 
     * @param xml
     * @return
     */
    public static String parseResultTag(final String xmlString) {
        try {
            // Create a DocumentBuilder
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            // Parse the XML string to create a Document object
            Document document = builder.parse(new java.io.ByteArrayInputStream(xmlString.getBytes()));
            // Get the <result> element
            Element root = document.getDocumentElement();
            NodeList resultNodes = root.getElementsByTagName("result");
            if (resultNodes.getLength() > 0) {
                String resultContent = resultNodes.item(0).getTextContent();

                // in some cases the result stirng starts with "```json"
                if (resultContent.startsWith("```json")) {
                    resultContent = resultContent.substring(7);
                }
                if (resultContent.endsWith("```")) {
                    resultContent = resultContent.substring(0, resultContent.length() - 3);
                }

                return resultContent;
            } else {
                logger.warning("<result> tag not found.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

}
