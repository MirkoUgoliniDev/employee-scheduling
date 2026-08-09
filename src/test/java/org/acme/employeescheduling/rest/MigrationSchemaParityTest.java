package org.acme.employeescheduling.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Guards the logical schema shared by SQLite and PostgreSQL.
 *
 * <p>The physical identity and floating-point syntax necessarily differs between
 * the engines; after normalising only those known differences, every table,
 * column, constraint and index must remain identical.</p>
 */
class MigrationSchemaParityTest {

    private static final Pattern TABLE = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-zA-Z0-9_]+)\\s*\\((.*?)\\)\\s*;");
    /**
     * CREATE / DROP / RENAME in a single scan, applying them in their order of appearance:
     * groups 1-2 = creation (name, body), 3 = dropped table,
     * 4-5 = rinomina (da, a).
     */
    private static final Pattern TABLE_LIFECYCLE = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-zA-Z0-9_]+)\\s*\\((.*?)\\)\\s*;"
            + "|DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([a-zA-Z0-9_]+)[^;]*;"
            + "|ALTER\\s+TABLE\\s+([a-zA-Z0-9_]+)\\s+RENAME\\s+TO\\s+([a-zA-Z0-9_]+)\\s*;");
    private static final Pattern INDEX = Pattern.compile(
            "(?is)CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[^;]+;"
            + "|ALTER\\s+TABLE\\s+[^;]+ADD\\s+COLUMN\\s+[^;]+;");
    private static final Pattern SCHEMA_CHANGE = Pattern.compile(
            "(?is)(?:ALTER\\s+TABLE|DROP\\s+(?:TABLE|INDEX))\\s+[^;]+;");
    private static final Pattern STRUCTURAL_DDL = Pattern.compile(
            "(?is)(?:\\b(?:CREATE|ALTER|DROP)\\s+(?:TABLE|INDEX|UNIQUE\\s+INDEX|VIEW|TRIGGER|SEQUENCE|TYPE|FUNCTION|SCHEMA|EXTENSION|DOMAIN)\\b"
            + "|\\bCOMMENT\\s+ON\\b|\\b(?:GRANT|REVOKE)\\b)[^;]*;");

    @Test
    void sqliteAndPostgresqlMigrationsDefineTheSameLogicalSchema() throws Exception {
        Path root = Path.of("src", "main", "resources", "db", "migration");
        Map<String, String> sqliteMigrations = migrations(root.resolve("sqlite"));
        Map<String, String> postgresqlMigrations = migrations(root.resolve("postgresql"));

        assertEquals(sqliteMigrations.keySet(), postgresqlMigrations.keySet(),
                "Every Flyway migration must exist for both SQLite and PostgreSQL");

        String sqlite = String.join("\n", sqliteMigrations.values());
        String postgresql = String.join("\n", postgresqlMigrations.values());

        assertOnlySupportedDdl("SQLite", sqlite);
        assertOnlySupportedDdl("PostgreSQL", postgresql);

        assertEquals(tableContract(sqlite), tableContract(postgresql),
                "SQLite and PostgreSQL table/column/constraint contracts drifted");
        assertEquals(indexContract(sqlite), indexContract(postgresql),
                "SQLite and PostgreSQL index contracts drifted");
        assertEquals(schemaChanges(sqlite), schemaChanges(postgresql),
                "SQLite and PostgreSQL ALTER/DROP contracts drifted");
    }

    private static void assertOnlySupportedDdl(String engine, String migration) {
        Matcher ddl = STRUCTURAL_DDL.matcher(migration);
        while (ddl.find()) {
            String statement = ddl.group();
            boolean supported = TABLE.matcher(statement).matches()
                    || INDEX.matcher(statement).matches()
                    || SCHEMA_CHANGE.matcher(statement).matches();
            assertTrue(supported, () -> engine + " migration contains unsupported structural DDL: "
                    + normalize(statement));
        }
    }

    /**
     * Builds the schema as it stands after ALL migrations, applying CREATE, DROP and
     * RENAME in file order.
     *
     * <p>Replacing a table is the only way SQLite can change a constraint: create the
     * new shape, copy, drop the old, rename. Reading CREATE statements alone would
     * report both the discarded table and the temporary name as if they still existed —
     * and would miss a table dropped in a later migration on one engine only, which is
     * exactly the drift this test exists to catch.</p>
     */
    private static Map<String, Set<String>> tableContract(String migration) {
        Map<String, Set<String>> contract = new LinkedHashMap<>();
        Matcher statement = TABLE_LIFECYCLE.matcher(migration);
        while (statement.find()) {
            String create = statement.group(1);
            if (create != null) {
                Set<String> definitions = new LinkedHashSet<>();
                for (String definition : splitTopLevel(statement.group(2))) {
                    definitions.add(normalize(definition));
                }
                contract.put(create.toLowerCase(Locale.ROOT), definitions);
                continue;
            }
            String dropped = statement.group(3);
            if (dropped != null) {
                contract.remove(dropped.toLowerCase(Locale.ROOT));
                continue;
            }
            String from = statement.group(4);
            String to = statement.group(5);
            if (from != null && to != null) {
                Set<String> definitions = contract.remove(from.toLowerCase(Locale.ROOT));
                if (definitions != null) {
                    contract.put(to.toLowerCase(Locale.ROOT), definitions);
                }
            }
        }
        return contract;
    }

    private static Set<String> indexContract(String migration) {
        Set<String> indexes = new LinkedHashSet<>();
        Matcher matcher = INDEX.matcher(migration);
        while (matcher.find()) {
            indexes.add(normalize(matcher.group()));
        }
        return indexes;
    }

    private static Set<String> schemaChanges(String migration) {
        Set<String> changes = new LinkedHashSet<>();
        Matcher matcher = SCHEMA_CHANGE.matcher(migration);
        while (matcher.find()) {
            // "DROP TABLE x CASCADE" is how PostgreSQL spells "DROP TABLE x" for a table
            // other tables reference; SQLite needs no keyword. Same intent, same effect
            // on the table itself. Only DROP is normalised: "ON DELETE CASCADE" inside a
            // foreign key means something else entirely.
            String change = normalize(matcher.group());
            if (change.startsWith("drop table")) {
                change = change.replace(" cascade;", ";");
            }
            // PostgreSQL identity sequences need an explicit post-seed restart;
            // SQLite AUTOINCREMENT advances automatically after explicit IDs.
            boolean identityRestart = change.matches("alter table [a-z0-9_]+ alter column id restart with [0-9]+;");
            // The CASCADE above also drops the foreign keys pointing at the table, so
            // PostgreSQL has to re-create them. SQLite keeps them inside the table body
            // and the rename makes them valid again: the end state is identical.
            boolean foreignKeyRebuild = change.matches(
                    "alter table [a-z0-9_]+ add constraint [a-z0-9_]+ foreign key\\([a-z0-9_]+\\)"
                    + "references [a-z0-9_]+\\(id\\)on delete cascade;");
            if (!identityRestart && !foreignKeyRebuild) {
                changes.add(change);
            }
        }
        return changes;
    }

    private static String normalize(String sql) {
        return sql.toLowerCase(Locale.ROOT)
                .replace("integer generated by default as identity primary key", "integer identity primary key")
                .replace("integer primary key autoincrement", "integer identity primary key")
                .replace("double precision", "real")
                .replace(" if not exists ", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("\\s*([(),])\\s*", "$1")
                .trim();
    }

    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean quoted = false;
        for (int i = 0; i < body.length(); i++) {
            char current = body.charAt(i);
            if (current == '\'' && (i + 1 >= body.length() || body.charAt(i + 1) != '\'')) {
                quoted = !quoted;
            } else if (!quoted && current == '(') {
                depth++;
            } else if (!quoted && current == ')') {
                depth--;
            } else if (!quoted && current == ',' && depth == 0) {
                parts.add(body.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(body.substring(start));
        return parts;
    }

    private static Map<String, String> migrations(Path directory) throws IOException {
        Map<String, String> migrations = new LinkedHashMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().matches("V[0-9][^/\\\\]*__.+\\.sql"))
                    .sorted()
                    .toList()) {
                migrations.put(path.getFileName().toString(), Files.readString(path));
            }
        }
        return migrations;
    }
}
