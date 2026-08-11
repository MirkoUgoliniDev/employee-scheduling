package org.acme.employeescheduling.rest;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.acme.employeescheduling.persistence.LabelEntity;
import org.acme.employeescheduling.persistence.LanguageEntity;
import org.acme.employeescheduling.persistence.LocalizzazioneEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * @brief Label and localization texts are served to anonymous clients and rendered with
 *        dangerouslySetInnerHTML on the home page: the server must sanitize them on write.
 *
 * @details Regression guard for the audit point I2: the only other defense was DOMPurify
 *          on the client. A script tag written through the admin endpoints must not be
 *          stored verbatim.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = "ADMIN")
class LabelWriteSanitizationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** @brief "it" language id on the active database (used in payloads and lookups). */
    private static Integer IT_LANGUAGE_ID;

    @BeforeAll
    static void findItalianLanguageId() {
        IT_LANGUAGE_ID = QuarkusTransaction.requiringNew().call(() -> {
            LanguageEntity language = LanguageEntity.<LanguageEntity>find("code", "it").firstResult();
            return language == null ? null : language.id;
        });
    }

    private ObjectNode labelPayload(String key, String description, String translation) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("key", key);
        node.put("description", description);
        ObjectNode translations = node.putObject("translations");
        translations.put(String.valueOf(IT_LANGUAGE_ID), translation);
        return node;
    }

    @Test
    void labelDescriptionIsSanitizedOnWrite() {
        int id = given().contentType(ContentType.JSON)
                .body(labelPayload("i2.test.description", "<script>alert(1)</script>Descrizione", "Traduzione"))
                .when().post("/labels")
                .then().statusCode(201).extract().jsonPath().getInt("id");

        try {
            String stored = QuarkusTransaction.requiringNew().call(() -> {
                LabelEntity entity = LabelEntity.findById(id);
                return entity == null ? null : entity.description;
            });
            assertFalse(stored.contains("<script>"), "Lo script è arrivato nel database: " + stored);
            assertEquals("Descrizione", stored, "La descrizione sanificata non è quella attesa");
        } finally {
            int labelId = id;
            QuarkusTransaction.requiringNew().run(() -> LabelEntity.deleteById(labelId));
        }
    }

    @Test
    void labelTranslationIsSanitizedOnWrite() {
        int id = given().contentType(ContentType.JSON)
                .body(labelPayload("i2.test.translation", "Descrizione", "<script>alert(1)</script>Traduzione"))
                .when().post("/labels")
                .then().statusCode(201).extract().jsonPath().getInt("id");

        try {
            String stored = QuarkusTransaction.requiringNew().call(() -> {
                LocalizzazioneEntity row = LocalizzazioneEntity.find(
                        "entityType = ?1 and entityId = ?2 and fieldName = ?3",
                        "labels", id, "value").firstResult();
                return row == null ? null : row.value;
            });
            assertFalse(stored.contains("<script>"), "Lo script è arrivato nella traduzione: " + stored);
            assertEquals("Traduzione", stored, "La traduzione sanificata non è quella attesa");
        } finally {
            int labelId = id;
            QuarkusTransaction.requiringNew().run(() -> LabelEntity.deleteById(labelId));
        }
    }

    @Test
    void localizzazioneValueIsSanitizedOnWrite() {
        int labelId = QuarkusTransaction.requiringNew().call(() -> {
            LabelEntity label = LabelEntity.find("labelKey", "i2.test.localizzazione").firstResult();
            if (label == null) {
                label = new LabelEntity();
                label.labelKey = "i2.test.localizzazione";
                label.description = "Descrizione";
                label.persistAndFlush();
            }
            return label.id;
        });

        try {
            ArrayNode items = MAPPER.createArrayNode();
            ObjectNode item = items.addObject();
            item.put("languageId", IT_LANGUAGE_ID);
            item.put("fieldName", "value");
            item.put("value", "<script>alert(1)</script>Valore");
            given().contentType(ContentType.JSON).body(items.toString())
                    .when().put("/localizzazioni/labels/" + labelId)
                    .then().statusCode(200);

            String stored = QuarkusTransaction.requiringNew().call(() -> {
                LocalizzazioneEntity row = LocalizzazioneEntity.find(
                        "entityType = ?1 and entityId = ?2 and fieldName = ?3",
                        "labels", labelId, "value").firstResult();
                return row == null ? null : row.value;
            });
            assertFalse(stored.contains("<script>"), "Lo script è arrivato in /localizzazioni: " + stored);
            assertEquals("Valore", stored, "Il valore sanificato non è quello atteso");
        } finally {
            int id = labelId;
            QuarkusTransaction.requiringNew().run(() -> {
                LocalizzazioneEntity.delete("entityType = ?1 and entityId = ?2", "labels", id);
                LabelEntity.deleteById(id);
            });
        }
    }
}
