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

package org.imixs.ai.json;

import java.io.StringReader;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.imixs.ai.workflow.ImixsAIResultEvent;
import org.imixs.workflow.ItemCollection;

import jakarta.enterprise.event.Observes;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * The LLMResultJSONAdapter is a CDI bean that can be used to parse an LLM
 * prompt result as a JSON String. The bean transforms the structure into items
 * of a workitem.
 * <p>
 * This observer Bean is triggered automatically by the LLMService after
 * processing a PROMT and feierring a `LLMResultEvent`.
 * <p>
 * The LLMResultJSONAdapter only reacts on the LLMResultEvent in case the the
 * event type==='JSON'
 * <p>
 * Example of a result object:
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
 * 
 */
public class ImixsAIResultJSONAdapter {
    private static Logger logger = Logger.getLogger(ImixsAIResultJSONAdapter.class.getName());

    public void onEvent(@Observes ImixsAIResultEvent event) {
        if (event.getWorkitem() == null) {
            return;
        }

        // we only adapt the result in case the eventType===JSON
        if ("JSON".equalsIgnoreCase(event.getEventType())) {
            // get result string
            String jsonString = event.getPromptResult();
            applyJSONObject(jsonString, event.getWorkitem());
        }
    }

    /**
     * Applies the values of a Imixs-AI result JSON string to a given workitem.
     *
     * @param resultObject
     * @param workitem
     */
    public static void applyJSONObject(final String jsonString, ItemCollection workitem) {
        JsonObject jsonObject = parseString(jsonString);
        applyJSONObject(jsonObject, workitem);
    }

    /**
     * Static one liner method to parse a JSON String into a JsonObject
     *
     * @param jsonString
     * @return
     */
    private static JsonObject parseString(final String jsonString) {
        // fix wrong formats....
        String fixedJsonString = correctJSON(jsonString);
        // Create a StringReader for the JSON string
        StringReader stringReader = new StringReader(fixedJsonString);
        // Create a JsonReader
        try (JsonReader jsonReader = Json.createReader(stringReader)) {
            // Parse the JSON string into a JsonObject
            JsonObject jsonObject = jsonReader.readObject();
            return jsonObject;
        }
    }

    /**
     * Applies the values of a Imixs-AI JSON result object to a given workitem.
     *
     * @param resultObject
     * @param workitem
     */
    public static void applyJSONObject(final JsonObject resultObject, ItemCollection workitem) {
        Set<String> keys = resultObject.keySet();
        for (String itemName : keys) {
            JsonValue jsonValue = resultObject.get(itemName);
            // Check the type of the JsonValue
            if (jsonValue.getValueType() == JsonValue.ValueType.STRING) {
                workitem.removeItem(itemName);
                applyJSONStringObject(jsonValue, itemName, workitem);
            } else if (jsonValue.getValueType() == JsonValue.ValueType.NUMBER) {
                workitem.removeItem(itemName);
                applyJSONNumberObject(jsonValue, itemName, workitem);
            } else if (jsonValue.getValueType() == JsonValue.ValueType.TRUE) {
                workitem.appendItemValue(itemName, true);
            } else if (jsonValue.getValueType() == JsonValue.ValueType.FALSE) {
                workitem.appendItemValue(itemName, false);
            } else if (jsonValue.getValueType() == JsonValue.ValueType.ARRAY) {
                // Convert JsonValue to JsonArray
                JsonArray jsonArray = (JsonArray) jsonValue;
                resolveJSONArray(itemName, jsonArray, workitem);
            } else {
                logger.warning("unsupported JSON Type");
            }
        }

    }

    /**
     * Helper method to apply the values of a json array
     * 
     * @param jsonArray
     * @param workitem
     */
    @SuppressWarnings("rawtypes")
    private static void resolveJSONArray(String itemName, JsonArray jsonArray, ItemCollection workitem) {

        // clear old value
        workitem.removeItem(itemName);
        ArrayList<ItemCollection> childItems = new ArrayList<ItemCollection>();
        // Iterate over the elements of the array
        for (JsonValue element : jsonArray) {
            // Process each element based on its type
            switch (element.getValueType()) {
            case STRING:
                applyJSONStringObject(element, itemName, workitem);
                break;
            case NUMBER:
                applyJSONNumberObject(element, itemName, workitem);
                break;
            case TRUE:
                workitem.appendItemValue(itemName, true);
                break;
            case FALSE:
                workitem.appendItemValue(itemName, false);
                break;
            case NULL:
                // now op
                break;
            case OBJECT:
                // Handle objects if needed
                ItemCollection childItemCol = new ItemCollection();
                JsonObject childObject = (JsonObject) element;
                applyJSONObject(childObject, childItemCol);
                childItems.add(childItemCol);

                break;
            case ARRAY:
                logger.warning(itemName + ": Array in Array is not supported");
                break;
            default:
                logger.warning(itemName + ": Unknown value type");
                break;
            }
        }

        // Test if we have child Item structure....
        if (childItems != null && childItems.size() > 0) {
            List<Map> mapOrderItems = new ArrayList<Map>();
            // iterate over all order items..
            for (ItemCollection orderItem : childItems) {
                mapOrderItems.add(orderItem.getAllItems());
            }
            workitem.replaceItemValue(itemName, mapOrderItems);
        }
    }

    private static void applyJSONNumberObject(JsonValue jsonValue, String itemName, ItemCollection workitem) {
        // Check if the JsonValue is a number
        if (jsonValue instanceof JsonNumber) {
            // Convert JsonValue to JsonNumber
            JsonNumber jsonNumber = (JsonNumber) jsonValue;

            // Check if the number is an integer
            if (jsonNumber.isIntegral()) {
                // Get the integer value
                int intValue = jsonNumber.intValue();
                workitem.appendItemValue(itemName, intValue);

            } else {
                // Get the floating-point value
                double doubleValue = jsonNumber.doubleValue();
                workitem.appendItemValue(itemName, doubleValue);
            }
        } else {
            System.out.println("The value is not a number.");
        }
    }

    /*
     * Helper method to apply a string value to a worktiem. The method tests if the
     * string is a ISO Date. In that case we convert the string into a
     * java.util:Date object.
     * 
     */
    private static void applyJSONStringObject(JsonValue jsonValue, String itemName, ItemCollection workitem) {
        // Convert JsonValue to JsonString
        JsonString jsonString = (JsonString) jsonValue;
        String value = jsonString.getString();
        if (isISODateValue(value)) {
            // Define the expected format
            DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE;
            // Parse the string to LocalDate
            LocalDate localDate = LocalDate.parse(value, formatter);
            // Convert LocalDate to java.util.Date
            Date date = Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
            workitem.appendItemValue(itemName, date);
        } else {
            workitem.appendItemValue(itemName, value);
        }
    }

    /**
     * Helper method to parse the given string and validate if it conforms to the
     * ISO date format (yyyy-MM-dd).
     * 
     * @param dateString
     * @return true if it is a ISO Date String
     */
    public static boolean isISODateValue(String dateString) {

        // Define the expected format
        DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE;

        try {
            // Parse the string
            LocalDate date = LocalDate.parse(dateString, formatter);
            // If parsing is successful, the string is in ISO date format
            return true;
        } catch (DateTimeParseException e) {
            // If parsing fails, the string is not in ISO date format
            return false;
        }
    }

    /**
     * Helper method to fix wrongly formatted number values.
     * 
     * 4,100.00 => 4100.00
     * 
     * @param jsonString
     * @return
     */
    public static String correctJSON(String jsonString) {

        // remove all characters before the first '{' and after the last '}'
        int startIndex = jsonString.indexOf('{');
        int endIndex = jsonString.lastIndexOf('}');
        jsonString = jsonString.substring(startIndex, endIndex + 1);

        // fix N/A
        jsonString = jsonString.replace("N/A", "0.0");

        // Define a regular expression pattern to match incorrectly formatted numbers
        // Pattern pattern = Pattern.compile("(\\d+),(\\d+\\.\\d+)");
        Pattern pattern = Pattern.compile("(\\d{1,3}(?:,\\d{3})*),(\\d+\\.\\d+)");

        Matcher matcher = pattern.matcher(jsonString);

        // Iterate through matches and replace them with string representations
        StringBuffer stringBuffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = "\"" + matcher.group(1).replaceAll(",", "") + "" + matcher.group(2) + "\"";
            matcher.appendReplacement(stringBuffer, replacement);
        }
        matcher.appendTail(stringBuffer);

        return stringBuffer.toString();
    }

}
