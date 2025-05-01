package org.imixs.ai.bpmn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.imixs.workflow.ItemCollection;
import org.junit.jupiter.api.Test;

/**
 * Test class for itemCollection object
 * 
 * @author rsoika
 * 
 */
public class TestItemCollection {

    @Test
    public void testItemCollection() {
        ItemCollection itemCollection = new ItemCollection();
        itemCollection.replaceItemValue("txtTitel", "Hello");
        assertEquals(itemCollection.getItemValueString("txttitel"), "Hello");
    }

    @Test
    public void testRemoveItem() {
        ItemCollection itemCollection = new ItemCollection();
        itemCollection.replaceItemValue("txtTitel", "Hello");
        assertEquals(itemCollection.getItemValueString("txttitel"), "Hello");
        assertTrue(itemCollection.hasItem("txtTitel"));
        itemCollection.removeItem("TXTtitel");
        assertFalse(itemCollection.hasItem("txtTitel"));
        assertEquals(itemCollection.getItemValueString("txttitel"), "");
    }

}
