package org.acme.employeescheduling.rest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @brief Canonical UI label catalog, read from {@code i18n/ui-translations.tsv}.
 *
 * @details This is the PORTABLE source of truth for static translations: it is applied
 *          at startup to any active database (SQLite or PostgreSQL) by
 *          {@link UiTranslationSyncService}. The old {@code seedLabelTranslations*}
 *          methods in {@link DemoDataRepository} use SQLite-only SQL and do not run under
 *          the PostgreSQL profile: every NEW key must be added here, not there.
 *
 *          The TSV format (instead of Java constants) also avoids the JVM's 64 KB
 *          per-method limit, which had already forced the seed into multiple methods.
 */
public final class UiTranslationCatalog {

    /** Catalog path on the classpath. */
    public static final String RESOURCE = "i18n/ui-translations.tsv";

    /** Required languages for every key, in TSV column order. */
    public static final List<String> REQUIRED_LANGUAGES = List.of("it", "en", "fr", "es", "de");

    private static final int FIELD_COUNT = 2 + 5; // key + description + five languages

    /**
     * @brief A catalog entry.
     * @param key i18n key used by the frontend with {@code t()}
     * @param description description shown on the Labels page
     * @param values translations by language code (it/en/fr/es/de)
     */
    public record Entry(String key, String description, Map<String, String> values) { }

    private UiTranslationCatalog() { }

    /** @brief Loads the catalog from the classpath. */
    public static List<Entry> load() {
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(RESOURCE);
        if (in == null) in = UiTranslationCatalog.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (in == null) throw new IllegalStateException("Catalogo traduzioni UI non trovato: " + RESOURCE);
        try (InputStream stream = in) {
            return parse(stream);
        } catch (IOException e) {
            throw new UncheckedIOException("Lettura del catalogo traduzioni UI fallita", e);
        }
    }

    /**
     * @brief Parses the TSV. Empty lines and comments ({@code #}) are ignored.
     * @throws IllegalStateException if a line is malformed, a language is missing, or
     *         a key is duplicated: an inconsistent catalog is a build error,
     *         not data to fix at runtime.
     */
    static List<Entry> parse(InputStream stream) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Map<String, Integer> seenKeys = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.charAt(0) == '#') continue;
                // -1: trailing empty columns must NOT be silently discarded;
                // otherwise a missing trailing translation would look like a short row.
                String[] fields = line.split("\t", -1);
                if (fields.length != FIELD_COUNT)
                    throw new IllegalStateException(RESOURCE + ":" + lineNumber
                            + " ha " + fields.length + " campi invece di " + FIELD_COUNT);

                String key = fields[0].trim();
                if (key.isEmpty())
                    throw new IllegalStateException(RESOURCE + ":" + lineNumber + " ha la chiave vuota");
                Integer previous = seenKeys.put(key, lineNumber);
                if (previous != null)
                    throw new IllegalStateException(RESOURCE + ":" + lineNumber
                            + " duplica la chiave '" + key + "' gia' definita alla riga " + previous);

                String description = fields[1].trim();
                if (description.isEmpty())
                    throw new IllegalStateException(RESOURCE + ":" + lineNumber
                            + " (" + key + ") non ha descrizione");

                Map<String, String> values = new LinkedHashMap<>();
                for (int i = 0; i < REQUIRED_LANGUAGES.size(); i++) {
                    String value = fields[2 + i];
                    if (value.isBlank())
                        throw new IllegalStateException(RESOURCE + ":" + lineNumber + " (" + key
                                + ") non ha la traduzione '" + REQUIRED_LANGUAGES.get(i) + "'");
                    values.put(REQUIRED_LANGUAGES.get(i), value);
                }
                entries.add(new Entry(key, description, Collections.unmodifiableMap(values)));
            }
        }
        return Collections.unmodifiableList(entries);
    }
}
