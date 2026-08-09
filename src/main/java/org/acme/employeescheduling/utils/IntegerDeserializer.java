package org.acme.employeescheduling.utils;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * @brief Custom Jackson deserializer for Integer values.
 * @details Handles deserialization of JSON string values to Integer objects.
 *          Trims whitespace before parsing and gracefully returns null for
 *          non-numeric values instead of throwing an exception.
 * @author Employee Scheduling Team
 * @version 1.0
 */
public class IntegerDeserializer extends JsonDeserializer<Integer> {

    /**
     * @brief Deserializes a JSON text value into an Integer.
     * @details Reads the JSON token as a string, trims whitespace, and attempts
     *          to parse it as an integer. Returns null if the value is not a valid number.
     * @param jsonParser the JSON parser providing the value to deserialize
     * @param context the deserialization context
     * @return the parsed Integer value, or null if the value is not a valid number
     * @throws IOException if an I/O error occurs during parsing
     */
    @Override
    public Integer deserialize(JsonParser jsonParser, DeserializationContext context) throws IOException {
        String value = jsonParser.getText();
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
