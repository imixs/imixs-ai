package org.imixs.ai.json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

import org.imixs.ai.TestRestAPI;
import org.imixs.workflow.ItemCollection;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Test class to test appling a json string to a workitem.
 * 
 * 
 * @author rsoika
 */
public class TestApplyJsonString {

    private static Logger logger = Logger.getLogger(TestRestAPI.class.getName());

    /**
     * The setup method loads t
     * 
     */
    @Before
    public void setup() {
        logger.info("setup...");
    }

    /**
     * Test parsing
     * 
     * 
     * <pre>
        {@code
         
        {
            "company.name": "Foo & Co. KG",
            "invoice.number": "111121307",
            "invoice.date": "2024-03-04",
            "invoice.total": 256.95,
            "cdtr.iban": [
                "DE87 3704 0000 0110 8208 00",
                "DE48 3707 0060 0105 333 00"
            ],
            "cdtr.bic": [
                "CXXXDEFFXXX",
                "DYYYDEDKXXX"
            ],
            "invoice.positions": [
                {
                "description": "NZ100/07706 1 parts",
                "quantity": 1,
                "unitprice": 16.00,
                "totalprice": 16.00
                },
                {
                "description": "A9999/33975 2 ",
                "quantity": 1,
                "unitprice": 762.00,
                "totalprice": 762.00
                }
            ]
        }        
    }</pre>
     */
    @Test
    public void testApplyJSONObject() {
        try {
            String path = this.getClass().getClassLoader().getResource("result-01.json").getPath();

            String testString = new String(Files.readAllBytes(Paths.get(path)));

            ItemCollection workitem = new ItemCollection();
            JSONParser.applyJSONObject(testString, workitem);

            Assert.assertEquals("Foo & Co. KG", workitem.getItemValueString("company.name"));

            List<String> ibans = workitem.getItemValueList("cdtr.iban", String.class);
            Assert.assertEquals(2, ibans.size());
            Assert.assertEquals("DE87 3704 0000 0110 8208 00", ibans.get(0));

            // Test Date
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
            Date date;
            date = dateFormat.parse("2024-03-04");
            Assert.assertEquals(date, workitem.getItemValueDate("invoice.date"));
        } catch (IOException | ParseException e) {
            e.printStackTrace();
            Assert.fail();
        }
    }

}
