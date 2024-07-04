package org.imixs.ai.xml;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.imixs.workflow.ItemCollection;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test the LLMResultXMLAdapter
 * 
 * @author rsoika
 */
public class TestResultXMLAdapter {

    /**
     * Test parsing an XML result returned by a LLM.
     * The XML is not valid!
     */
    @SuppressWarnings("unchecked")
    @Test
    public void testParseXMLResult01() {

        try {

            // file = new File("src/test/resources/xml/xmlresult.xml");
            String xml = readXmlFileAsString("src/test/resources/xml/xmlresult-01.xml");

            ItemCollection workitem = new ItemCollection();
            ImixsAIResultXMLAdapter.parseXML(xml, workitem);
            Assert.assertNotNull(workitem);
            Assert.assertEquals("Kraxi GmbH", workitem.getItemValueString("cdtr.name"));
            Assert.assertEquals("R1234", workitem.getItemValueString("invoice.number"));

            Assert.assertTrue(workitem.isItemValueDate("invoice.date"));

            List<Map<String, List<Object>>> positions = workitem.getItemValue("invoice.position");
            Assert.assertEquals(2, positions.size());
            Map<String, List<Object>> map1 = (Map<String, List<Object>>) positions.get(0);
            Assert.assertEquals("A123", map1.get("order").get(0));

        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail();
        }

    }

    /**
     * Test parsing an XML result returned by a LLM.
     * The XML is not valid!
     */
    @SuppressWarnings("unchecked")
    @Test
    public void testParseXMLResult02() {

        try {

            // file = new File("src/test/resources/xml/xmlresult.xml");
            String xml = readXmlFileAsString("src/test/resources/xml/xmlresult-02.xml");

            ItemCollection workitem = new ItemCollection();
            ImixsAIResultXMLAdapter.parseXML(xml, workitem);
            Assert.assertNotNull(workitem);
            Assert.assertEquals("XXX Logistics GmbH & Co. KG", workitem.getItemValueString("cdtr.name"));
            Assert.assertEquals("AB -> 1234", workitem.getItemValueString("invoice.number"));

            Assert.assertTrue(workitem.isItemValueDate("invoice.date"));

            List<Map<String, List<Object>>> positions = workitem.getItemValue("invoice.position");
            Assert.assertEquals(3, positions.size());
            Map<String, List<Object>> map1 = (Map<String, List<Object>>) positions.get(0);
            Assert.assertEquals("THC / Cont.", map1.get("order").get(0));

        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail();
        }

    }

    /**
     * Helper method to read xml from a file
     * 
     * @param filePath
     * @return
     */
    private static String readXmlFileAsString(String filePath) {
        StringBuilder contentBuilder = new StringBuilder();

        try {
            File file = new File(filePath);
            Files.lines(Paths.get(filePath))
                    .forEach(line -> contentBuilder.append(line).append("\n"));
        } catch (IOException e) {
            e.printStackTrace();
        }

        return contentBuilder.toString();
    }
}
