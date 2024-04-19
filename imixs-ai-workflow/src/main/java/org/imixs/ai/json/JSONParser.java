package org.imixs.ai.json;

import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

/**
 * This is a JSON Parser class to simplify the parsing of a Imixs-AI result
 * object.
 * 
 * For example a result object may look like this:
 * 
 * {
 * "InvoiceData": {
 * "Company": "Foo Network, Express Ltd",
 * "InvoiceNumber": "DE200007",
 * "InvoiceDate": "2024-03-15",
 * "InvoiceAmountGross": 455.08
 * },
 * "PaymentInfo": {
 * "IBAN": "DE00000000028005865755",
 * "BIC": "CC0000ßß",
 * "PaymentDate": ""
 * }
 * }
 * 
 */
public class JSONParser {

    /**
     * Static one liner method to parse a JSON String into a JsonObject
     * 
     * @param jsonString
     * @return
     */
    public static JsonObject parseString(final String jsonString) {
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
     * Helper method to fix wrongly formatted number values.
     * 
     * 4,100.00 => 4100.00
     * 
     * @param jsonString
     * @return
     */
    public static String correctJSON(String jsonString) {
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
