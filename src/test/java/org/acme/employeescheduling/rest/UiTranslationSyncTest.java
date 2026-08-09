package org.acme.employeescheduling.rest;

import io.quarkus.test.security.TestSecurity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.acme.employeescheduling.persistence.LabelEntity;
import org.acme.employeescheduling.persistence.LanguageEntity;
import org.acme.employeescheduling.persistence.LocalizzazioneEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * @brief Verifies that the UI translation catalog is identical on SQLite and PostgreSQL.
 *
 * @details The same class runs with both real profiles: this proves that localization
 *          alignment no longer depends on the SQLite-only seed in
 *          {@link DemoDataRepository}, which never runs under the PostgreSQL profile.
 */
@QuarkusTest
// Endpoints are deny-by-default: without an identity every HTTP request would return 401.
// Declare the identity instead of authenticating: these tests verify the domain, not login,
// and real form authentication would make failures unreadable.
@TestSecurity(user = "test-admin", roles = {"ADMIN", "CAPOSALA"})
@EnabledIfSystemProperty(named = "quarkus.test.profile", matches = "test-(sqlite|postgresql)")
class UiTranslationSyncTest {

    private static final String LABEL_TYPE = "labels";
    private static final String LABEL_FIELD = "value";

    @Inject
    UiTranslationSyncService syncService;

    @Test
    void everyCatalogKeyIsTranslatedInAllLanguagesOnTheActiveDatabase() {
        List<UiTranslationCatalog.Entry> catalog = UiTranslationCatalog.load();
        assertTrue(catalog.size() > 100, "Catalogo sospettosamente piccolo: " + catalog.size());

        Snapshot snapshot = QuarkusTransaction.requiringNew().call(UiTranslationSyncTest::readSnapshot);

        Set<String> missingLabels = new TreeSet<>();
        Set<String> missingTranslations = new TreeSet<>();
        for (UiTranslationCatalog.Entry entry : catalog) {
            Integer labelId = snapshot.labelIdByKey().get(entry.key());
            if (labelId == null) {
                missingLabels.add(entry.key());
                continue;
            }
            for (String language : UiTranslationCatalog.REQUIRED_LANGUAGES) {
                Integer languageId = snapshot.languageIdByCode().get(language);
                if (languageId == null) continue; // language not configured in this database
                if (!snapshot.translated().contains(labelId + "/" + languageId))
                    missingTranslations.add(entry.key() + " [" + language + "]");
            }
        }

        assertTrue(missingLabels.isEmpty(),
                "Etichette del catalogo non create sul database attivo: " + limit(missingLabels));
        assertTrue(missingTranslations.isEmpty(),
                "Traduzioni mancanti sul database attivo: " + limit(missingTranslations));
    }

    @Test
    void repeatedSyncWritesNothing() {
        // The catalog was already applied at startup: a second pass must be a no-op,
        // otherwise every restart would write to the database.
        UiTranslationSyncService.SyncResult repeated = syncService.syncCatalog();
        assertEquals(0, repeated.insertedLabels(), "La sincronizzazione ha ricreato etichette gia' presenti");
        assertEquals(0, repeated.insertedTranslations(), "La sincronizzazione ha riscritto traduzioni gia' presenti");
        assertTrue(repeated.missingLanguages().isEmpty(),
                "Lingue del catalogo assenti dalla tabella languages: " + repeated.missingLanguages());
    }

    /**
     * @brief Synchronization actually recreates missing data on both engines.
     *
     * @details Other tests start from an already aligned database, so the insertion branch
     *          was never executed: the SQL that implements it
     *          ({@code INSERT ... ON CONFLICT DO NOTHING}, written by hand because it must be
     *          atomic) could have been syntactically incorrect without any test noticing.
     *          Here a translation is deliberately deleted and verified to return with the
     *          catalog value.
     */
    @Test
    void syncRecreatesMissingTranslations() {
        UiTranslationCatalog.Entry entry = UiTranslationCatalog.load().get(0);
        Integer languageId = QuarkusTransaction.requiringNew().call(() -> {
            LanguageEntity language = LanguageEntity.<LanguageEntity>find("code", "it").firstResult();
            return language == null ? null : language.id;
        });
        assertNotNull(languageId, "Lingua 'it' assente dal database attivo");

        String expected = entry.values().get("it");
        QuarkusTransaction.requiringNew().run(() -> translationRow(entry.key(), languageId).delete());

        UiTranslationSyncService.SyncResult result = syncService.syncCatalog();
        assertEquals(1, result.insertedTranslations(),
                "La sincronizzazione non ha ricreato la traduzione cancellata");

        String recreated = QuarkusTransaction.requiringNew()
                .call(() -> translationRow(entry.key(), languageId).value);
        assertEquals(expected, recreated,
                "La traduzione ricreata non corrisponde al valore del catalogo");
    }

    @Test
    void syncDoesNotOverwriteEditedTranslations() {
        UiTranslationCatalog.Entry entry = UiTranslationCatalog.load().get(0);
        Integer languageId = QuarkusTransaction.requiringNew().call(() -> {
            LanguageEntity language = LanguageEntity.<LanguageEntity>find("code", "it").firstResult();
            return language == null ? null : language.id;
        });
        assertNotNull(languageId, "Lingua 'it' assente dal database attivo");

        String edited = "Valore modificato dall'utente";
        String original = QuarkusTransaction.requiringNew().call(() -> {
            LocalizzazioneEntity row = translationRow(entry.key(), languageId);
            String previous = row.value;
            row.value = edited;
            row.persistAndFlush();
            return previous;
        });
        try {
            syncService.syncCatalog();
            String afterSync = QuarkusTransaction.requiringNew()
                    .call(() -> translationRow(entry.key(), languageId).value);
            assertEquals(edited, afterSync,
                    "La sincronizzazione ha sovrascritto una traduzione modificata dalla pagina Etichette");
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                LocalizzazioneEntity row = translationRow(entry.key(), languageId);
                row.value = original;
                row.persistAndFlush();
            });
        }
    }

    /**
     * @param labelIdByKey label IDs by i18n key
     * @param languageIdByCode language IDs by code
     * @param translated "labelId/languageId" pairs with a nonempty value
     */
    private record Snapshot(Map<String, Integer> labelIdByKey,
                            Map<String, Integer> languageIdByCode,
                            Set<String> translated) { }

    /** @brief Reads the active database's UI translation state in three queries. */
    private static Snapshot readSnapshot() {
        Map<String, Integer> labelIdByKey = new HashMap<>();
        for (LabelEntity label : LabelEntity.<LabelEntity>listAll())
            labelIdByKey.put(label.labelKey, label.id);

        Map<String, Integer> languageIdByCode = new HashMap<>();
        for (LanguageEntity language : LanguageEntity.<LanguageEntity>listAll())
            languageIdByCode.put(language.code, language.id);

        Set<String> translated = new HashSet<>();
        for (LocalizzazioneEntity row : LocalizzazioneEntity.<LocalizzazioneEntity>find(
                "entityType = ?1 and fieldName = ?2", LABEL_TYPE, LABEL_FIELD).list())
            if (row.value != null && !row.value.isBlank())
                translated.add(row.entityId + "/" + row.languageId);

        return new Snapshot(labelIdByKey, languageIdByCode, translated);
    }

    /** @brief Translation row for a key in one language; must already exist. */
    private static LocalizzazioneEntity translationRow(String key, Integer languageId) {
        LabelEntity label = LabelEntity.<LabelEntity>find("labelKey", key).firstResult();
        assertNotNull(label, "Etichetta assente sul database attivo: " + key);
        LocalizzazioneEntity row = LocalizzazioneEntity.<LocalizzazioneEntity>find(
                        "entityType = ?1 and entityId = ?2 and fieldName = ?3 and languageId = ?4",
                        LABEL_TYPE, label.id, LABEL_FIELD, languageId)
                .firstResult();
        assertNotNull(row, "Traduzione assente sul database attivo per " + key);
        return row;
    }

    /** @brief Truncates the list in the error message: 800 keys do not help diagnosis. */
    private static String limit(Set<String> values) {
        List<String> shown = new ArrayList<>(values).subList(0, Math.min(10, values.size()));
        return values.size() <= 10 ? shown.toString() : shown + " (e altre " + (values.size() - 10) + ")";
    }
}
