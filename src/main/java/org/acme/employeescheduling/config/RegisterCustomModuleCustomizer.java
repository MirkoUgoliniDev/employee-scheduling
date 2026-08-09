package org.acme.employeescheduling.config;



import jakarta.inject.Singleton;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.jackson.ObjectMapperCustomizer;



/**
 * @brief Jackson ObjectMapper customizer for Java time module registration.
 * @details Registers the JavaTimeModule with the Jackson ObjectMapper and disables
 *          serialization of dates as timestamps, ensuring ISO-8601 date/time
 *          formatting throughout the application.
 * @author Employee Scheduling Team
 * @version 1.0
 */
@Singleton
public class RegisterCustomModuleCustomizer implements ObjectMapperCustomizer {

    /**
     * @brief Customizes the ObjectMapper with Java time support.
     * @details Registers the JSR-310 JavaTimeModule and disables timestamp-based
     *          date serialization in favor of ISO-8601 string representation.
     * @param mapper the Jackson ObjectMapper instance to customize
     */
    public void customize(ObjectMapper mapper) {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}

