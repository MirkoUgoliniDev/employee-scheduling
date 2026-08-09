package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * @brief Fails the build if the UI translation catalog is incomplete or a key is added
 *        only on the SQLite path.
 *
 * @details UI labels live in two historically misaligned places: the Java seed in
 *          {@link DemoDataRepository} (SQL {@code INSERT OR IGNORE}, executed only with
 *          {@code app.sqlite.legacy-bootstrap=true}) and the portable catalog
 *          {@link UiTranslationCatalog}, applied through JPA to SQLite and PostgreSQL.
 *          Only the latter reaches both engines: this test ensures no key exists solely
 *          in the former. Pure unit test, with no Quarkus context.
 */
class UiTranslationCatalogTest {

    private static final Path REPOSITORY_SOURCE =
            Path.of("src/main/java/org/acme/employeescheduling/rest/DemoDataRepository.java");

    /** Keys registered as labels: INSERT OR IGNORE INTO labels (key, description) VALUES ('x','y'). */
    private static final Pattern SEEDED_LABEL =
            Pattern.compile("INSERT OR IGNORE INTO labels \\(key, description\\) VALUES \\('([^']*)'");

    /** Keys from translation tables: {"key","it","value"}. */
    private static final Pattern SEEDED_TRANSLATION =
            Pattern.compile("\\{\"([^\"]*)\",\"(?:it|en|fr|es|de)\",");

    @Test
    void catalogIsCompleteAndParsable() {
        List<UiTranslationCatalog.Entry> catalog = UiTranslationCatalog.load();
        assertFalse(catalog.isEmpty(), "Il catalogo traduzioni UI e' vuoto");

        for (UiTranslationCatalog.Entry entry : catalog) {
            assertEquals(UiTranslationCatalog.REQUIRED_LANGUAGES.size(), entry.values().size(),
                    "La chiave '" + entry.key() + "' non ha tutte le lingue richieste");
            for (String language : UiTranslationCatalog.REQUIRED_LANGUAGES)
                assertTrue(entry.values().containsKey(language),
                        "La chiave '" + entry.key() + "' non ha la traduzione '" + language + "'");
        }
    }

    @Test
    void catalogContainsTheActiveDatabaseIndicator() {
        UiTranslationCatalog.Entry entry = UiTranslationCatalog.load().stream()
                .filter(candidate -> "config.info.activeDatabase".equals(candidate.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "config.info.activeDatabase manca dal catalogo: l'indicatore "
                                + "'Database attivo' resterebbe in italiano su tutte le lingue"));
        assertEquals("Database attivo", entry.values().get("it"));
        assertEquals("Active database", entry.values().get("en"));
    }

    @Test
    void malformedRecordsAreRejected() {
        assertAll(
                () -> assertThrows(IllegalStateException.class,
                        () -> parse("chiave\tdescrizione\tit\ten\tfr\tes\n"),
                        "Una riga con meno campi del previsto deve fallire"),
                () -> assertThrows(IllegalStateException.class,
                        () -> parse("chiave\tdescrizione\tit\ten\tfr\tes\t\n"),
                        "Una traduzione vuota deve fallire"),
                () -> assertThrows(IllegalStateException.class,
                        () -> parse("chiave\tdesc\ta\tb\tc\td\te\nchiave\tdesc\ta\tb\tc\td\te\n"),
                        "Una chiave duplicata deve fallire"),
                () -> assertThrows(IllegalStateException.class,
                        () -> parse("\tdescrizione\ta\tb\tc\td\te\n"),
                        "Una chiave vuota deve fallire"));
    }

    @Test
    void wellFormedRecordsAreAccepted() throws Exception {
        List<UiTranslationCatalog.Entry> entries = parse(
                "# commento\n\nchiave.test\tDescrizione\tuno\tone\tun\tuno\teins\n");
        assertEquals(1, entries.size());
        assertEquals("chiave.test", entries.get(0).key());
        assertEquals("eins", entries.get(0).values().get("de"));
    }

    /**
     * @brief The SQLite-only seed must not introduce keys absent from the portable catalog.
     * @details This makes the database-alignment rule executable: a key added only to
     *          {@code seedLabelTranslations*} would appear on SQLite and be absent from
     *          PostgreSQL, where that code never runs.
     */
    @Test
    void legacySqliteSeedIntroducesNoKeyOutsideThePortableCatalog() throws Exception {
        String source = Files.readString(REPOSITORY_SOURCE, StandardCharsets.UTF_8);
        Set<String> seeded = new TreeSet<>();
        collect(SEEDED_LABEL, source, seeded);
        collect(SEEDED_TRANSLATION, source, seeded);
        assertFalse(seeded.isEmpty(), "Nessuna chiave estratta da " + REPOSITORY_SOURCE
                + ": il pattern di estrazione non riconosce piu' il seed legacy");

        Set<String> portable = UiTranslationCatalog.load().stream()
                .map(UiTranslationCatalog.Entry::key)
                .collect(Collectors.toCollection(TreeSet::new));
        seeded.removeAll(portable);

        assertTrue(seeded.isEmpty(),
                "Queste chiavi esistono solo nel seed SQLite di DemoDataRepository e non nel "
                        + "catalogo portabile " + UiTranslationCatalog.RESOURCE + ", quindi su "
                        + "PostgreSQL mancherebbero: " + seeded
                        + ". Aggiungerle al catalogo (key, descrizione e le 5 lingue).");
    }

    /** @brief Collects literal keys, discarding those built through concatenation. */
    private static void collect(Pattern pattern, String source, Set<String> target) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            String key = matcher.group(1);
            // The source contains keys composed at runtime (e.g. "solver.label." + k):
            // the captured text is not a real key and cannot be verified statically.
            if (key.isBlank() || key.contains("+") || key.contains("\"") || key.contains(" ")) continue;
            target.add(key);
        }
    }

    private static List<UiTranslationCatalog.Entry> parse(String content) throws Exception {
        return UiTranslationCatalog.parse(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }
}
