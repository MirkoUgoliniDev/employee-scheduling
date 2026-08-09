package org.acme.employeescheduling.rest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.acme.employeescheduling.persistence.LabelEntity;
import org.acme.employeescheduling.persistence.LanguageEntity;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * @brief Aligns UI labels in the active database with the portable canonical catalog.
 *
 * @details The historical seed ({@code DemoDataRepository.seedLabelTranslations*}) uses
 *          SQLite-only SQL and runs only with {@code app.sqlite.legacy-bootstrap=true}: it is
 *          never executed on PostgreSQL, so every new key added there silently diverged between
 *          the two engines. This service closes that gap by reading
 *          {@link UiTranslationCatalog} and writing through JPA, so it works identically on
 *          SQLite and PostgreSQL.
 *
 *          Synchronization is ADDITIVE: it inserts only what is missing and never touches an
 *          existing value, so translations changed on the Labels page are not overwritten at
 *          every restart. In steady state (catalog already applied), the cost is two SELECTs
 *          and no writes.
 */
@ApplicationScoped
public class UiTranslationSyncService {

    private static final Logger logger = Logger.getLogger(UiTranslationSyncService.class.getName());

    /** Conservative parameter limit for an IN clause, already used elsewhere in the project. */
    private static final int IN_CHUNK = 900;

    /** UI label translations: entity_type='labels', field_name='value'. */
    private static final String LABEL_ENTITY_TYPE = "labels";
    private static final String LABEL_FIELD_NAME = "value";

    @Inject
    @ConfigProperty(name = "app.i18n.sync-catalog", defaultValue = "true")
    boolean syncEnabled;

    @Inject
    EntityManager em;

    @Inject
    DemoDataRepository demoDataRepository;

    /**
     * @brief Synchronization outcome.
     * @param insertedLabels keys created in {@code labels}
     * @param insertedTranslations rows created in {@code localizzazioni}
     * @param missingLanguages catalog language codes absent from the languages table
     */
    public record SyncResult(int insertedLabels, int insertedTranslations, Set<String> missingLanguages) { }

    /**
     * @brief Applies the catalog at startup, after schema bootstrap.
     * @details Explicit priority: the legacy bootstrap in {@link DemoDataRepository} observes
     *          the same event at the default priority (APPLICATION+500) and must be able to
     *          create the tables before this service writes to them. An error here degrades
     *          unseeded strings to the i18next fallback: it is a visible defect but does not
     *          justify blocking startup.
     */
    @Transactional
    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 600) StartupEvent ignored) {
        if (!syncEnabled) return;
        try {
            SyncResult result = syncCatalog();
            if (result.insertedLabels() > 0 || result.insertedTranslations() > 0)
                logger.info("Catalogo traduzioni UI allineato: " + result.insertedLabels()
                        + " etichette e " + result.insertedTranslations() + " traduzioni inserite.");
            if (!result.missingLanguages().isEmpty())
                logger.warning("Lingue del catalogo assenti dalla tabella languages, traduzioni non applicate: "
                        + result.missingLanguages());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Allineamento del catalogo traduzioni UI fallito", e);
        }
    }

    /**
     * @brief Inserts catalog labels and translations missing from the active database.
     * @return the count of records actually created
     */
    @Transactional
    public SyncResult syncCatalog() {
        List<UiTranslationCatalog.Entry> catalog = UiTranslationCatalog.load();
        if (catalog.isEmpty()) return new SyncResult(0, 0, Set.of());

        Map<String, Integer> languageIdByCode = new HashMap<>();
        for (LanguageEntity language : LanguageEntity.<LanguageEntity>listAll())
            languageIdByCode.put(language.code, language.id);

        Set<String> missingLanguages = new HashSet<>();
        for (UiTranslationCatalog.Entry entry : catalog)
            for (String code : entry.values().keySet())
                if (!languageIdByCode.containsKey(code)) missingLanguages.add(code);

        Map<String, LabelEntity> labelByKey = loadLabelsByKey(catalog);

        int insertedLabels = 0;
        for (UiTranslationCatalog.Entry entry : catalog) {
            if (labelByKey.containsKey(entry.key())) continue;
            insertedLabels += insertLabelIfAbsent(entry.key(), entry.description());
        }
        // Translations reference labels.id: newly written labels must be reread to obtain their
        // identifiers. The reread also collects those inserted in the meantime by another
        // instance, which is exactly what is needed.
        if (insertedLabels > 0) labelByKey = loadLabelsByKey(catalog);

        Set<String> existingPairs = loadExistingTranslationPairs(labelByKey.values());

        int insertedTranslations = 0;
        for (UiTranslationCatalog.Entry entry : catalog) {
            LabelEntity label = labelByKey.get(entry.key());
            if (label == null || label.id == null) continue;
            for (Map.Entry<String, String> translation : entry.values().entrySet()) {
                Integer languageId = languageIdByCode.get(translation.getKey());
                if (languageId == null) continue;
                if (!existingPairs.add(label.id + "/" + languageId)) continue;
                insertedTranslations += insertTranslationIfAbsent(label.id, languageId, translation.getValue());
            }
        }

        if (insertedLabels > 0 || insertedTranslations > 0)
            demoDataRepository.invalidateTranslationsAfterCommit();

        return new SyncResult(insertedLabels, insertedTranslations, missingLanguages);
    }

    /**
     * @brief Inserts a row only if it is absent, without ever failing on a conflict.
     *
     * @details The implementation previously read and then inserted: two instances starting
     *          together against the same PostgreSQL database both saw the key as absent, both
     *          wrote it, and the second violated the unique index. Because the entire
     *          synchronization runs in one transaction, that conflict also rolled back all
     *          previously inserted rows: the instance started with an incomplete catalog and
     *          users saw the Italian fallback in every language until the next restart.
     *
     *          {@code DO NOTHING}, not {@code DO UPDATE}: synchronization is additive by
     *          contract and must never overwrite a translation changed on the Labels page.
     *
     * @return 1 if the row was created, 0 if it already existed
     */
    private int insertLabelIfAbsent(String key, String description) {
        return em.createNativeQuery(
                        "INSERT INTO labels (key, description) VALUES (?1, ?2) ON CONFLICT (key) DO NOTHING")
                .setParameter(1, key)
                .setParameter(2, description)
                .executeUpdate();
    }

    /** @brief Like {@link #insertLabelIfAbsent}, for a label translation. */
    private int insertTranslationIfAbsent(int labelId, int languageId, String value) {
        return em.createNativeQuery(
                        "INSERT INTO localizzazioni (entity_type, entity_id, field_name, language_id, value) "
                        + "VALUES (?1, ?2, ?3, ?4, ?5) "
                        + "ON CONFLICT (entity_type, entity_id, field_name, language_id) DO NOTHING")
                .setParameter(1, LABEL_ENTITY_TYPE)
                .setParameter(2, labelId)
                .setParameter(3, LABEL_FIELD_NAME)
                .setParameter(4, languageId)
                .setParameter(5, value)
                .executeUpdate();
    }

    /** @brief Bulk-loads existing labels among those in the catalog. */
    private Map<String, LabelEntity> loadLabelsByKey(List<UiTranslationCatalog.Entry> catalog) {
        List<String> keys = new ArrayList<>(catalog.size());
        for (UiTranslationCatalog.Entry entry : catalog) keys.add(entry.key());

        Map<String, LabelEntity> byKey = new LinkedHashMap<>();
        for (int from = 0; from < keys.size(); from += IN_CHUNK) {
            List<String> chunk = keys.subList(from, Math.min(from + IN_CHUNK, keys.size()));
            List<LabelEntity> found = em.createQuery(
                            "SELECT l FROM LabelEntity l WHERE l.labelKey IN :keys", LabelEntity.class)
                    .setParameter("keys", chunk)
                    .getResultList();
            for (LabelEntity label : found) byKey.put(label.labelKey, label);
        }
        return byKey;
    }

    /** @brief Already translated "labelId/languageId" pairs, to avoid rewriting anything. */
    private Set<String> loadExistingTranslationPairs(Iterable<LabelEntity> labels) {
        List<Integer> ids = new ArrayList<>();
        for (LabelEntity label : labels) if (label.id != null) ids.add(label.id);

        Set<String> pairs = new HashSet<>();
        for (int from = 0; from < ids.size(); from += IN_CHUNK) {
            List<Integer> chunk = ids.subList(from, Math.min(from + IN_CHUNK, ids.size()));
            List<Object[]> rows = em.createQuery(
                            "SELECT o.entityId, o.languageId FROM LocalizzazioneEntity o "
                                    + "WHERE o.entityType = :type AND o.fieldName = :field AND o.entityId IN :ids",
                            Object[].class)
                    .setParameter("type", LABEL_ENTITY_TYPE)
                    .setParameter("field", LABEL_FIELD_NAME)
                    .setParameter("ids", chunk)
                    .getResultList();
            for (Object[] row : rows) pairs.add(row[0] + "/" + row[1]);
        }
        return pairs;
    }
}
