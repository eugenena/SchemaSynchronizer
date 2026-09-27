// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Contracts for definition validation, history bounds, credentials, and entry-point failures. */
class ReviewFindingsContractTest {

    private static final SchemaSynchronizerOptions OPTIONS = new SchemaSynchronizerOptions(
            "app", "schema_synchronizer_history", 7_249_031_147L, false, true, true);

    // ---------------------------------------------------------------- change-set structure and history bounds

    @Test
    void nullOrBlankChangeSetStatementsAreRejectedStructurallyBeforeAnyLock() {
        ChangeSetExecutor executor = new ChangeSetExecutor();
        for (List<String> statements : List.of(Arrays.asList("SELECT 1", null), List.of("  "), List.of("SELECT 1", ""))) {
            assertThatThrownBy(() -> executor.validateStructure(List.of(
                    new SchemaDefinition.ChangeSet("001", "d", statements))))
                    .as("%s", statements)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null or blank statement");
        }
        assertThatCode(() -> executor.validateStructure(List.of(
                new SchemaDefinition.ChangeSet("001", "d", List.of("SELECT 1"))))).doesNotThrowAnyException();
    }

    @Test
    void historyDescriptionIsBoundedInStoredBytesOnlyWhereTheColumnIsSizedInBytes() {
        String cjk300 = "日".repeat(300);            // 300 characters, 900 UTF-8 bytes
        String ascii500 = "a".repeat(500);
        String accented = "é".repeat(250) + "a";   // 251 characters, 501 UTF-8 bytes
        ChangeSetExecutor.StoredBytes utf8 = value -> value.getBytes(StandardCharsets.UTF_8).length;
        ChangeSetExecutor.StoredBytes singleByte = String::length;
        ChangeSetExecutor.StoredBytes unused = value -> {
            throw new AssertionError("the server is asked only past the UTF-8 bound");
        };
        for (DatabaseDialect dialect : DatabaseDialect.values()) {
            boolean bytes = dialect == DatabaseDialect.ORACLE || dialect == DatabaseDialect.SQLSERVER;
            ChangeSetExecutor.StoredBytes asked = dialect == DatabaseDialect.ORACLE ? utf8 : unused;
            assertThatCode(() -> ChangeSetExecutor.requireHistoryRowFits(change(ascii500), dialect, asked))
                    .as("%s 500 ASCII", dialect).doesNotThrowAnyException();
            assertThatCode(() -> ChangeSetExecutor.requireHistoryRowFits(change("a".repeat(333)), dialect, unused))
                    .as("%s within every character set's bound", dialect).doesNotThrowAnyException();
            assertThatCode(() -> ChangeSetExecutor.requireHistoryRowFits(change(null), dialect, unused))
                    .as("%s null", dialect).doesNotThrowAnyException();
            for (String description : List.of(cjk300, accented)) {
                if (bytes) {
                    assertThatThrownBy(() -> ChangeSetExecutor.requireHistoryRowFits(change(description), dialect,
                            utf8))
                            .as("%s %d chars", dialect, description.length())
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("500 bytes");
                    assertThatCode(() -> ChangeSetExecutor.requireHistoryRowFits(change(description), dialect,
                            singleByte))
                            .as("%s %d chars in a single-byte collation", dialect, description.length())
                            .doesNotThrowAnyException();
                } else {
                    assertThatCode(() -> ChangeSetExecutor.requireHistoryRowFits(change(description), dialect,
                            unused))
                            .as("%s %d chars", dialect, description.length()).doesNotThrowAnyException();
                }
            }
        }
    }

    @Test
    void oracleLegacyUtf8IsAskedEvenWithinFiveHundredUtf8Bytes() throws Exception {
        String emoji84 = "\uD83D\uDE00".repeat(84); // 336 UTF-8 bytes, 504 in Oracle UTF8 (CESU-8)
        ChangeSetExecutor.StoredBytes cesu8 = value -> value.length() * 3;
        assertThatThrownBy(() -> ChangeSetExecutor.requireHistoryRowFits(change(emoji84), DatabaseDialect.ORACLE,
                cesu8)).hasMessageContaining("500 bytes");
    }

    private static SchemaDefinition.ChangeSet change(String description) {
        return new SchemaDefinition.ChangeSet("001", description, List.of("SELECT 1"));
    }

    // ---------------------------------------------------------------- credentials

    @Test
    void historyActorIsTruncatedToWholeCodePointsWithinTheByteLimit() {
        assertThat(CliCredentials.truncateUtf8("deploy", 200)).isEqualTo("deploy");
        assertThat(CliCredentials.truncateUtf8("a".repeat(250), 200)).hasSize(200);
        // 3-byte characters: 66 fit in 200 bytes (198), the 67th would not.
        assertThat(CliCredentials.truncateUtf8("日".repeat(100), 200)).isEqualTo("日".repeat(66));
        // A surrogate pair (4 bytes) is never split.
        String emoji = "\uD83D\uDE00";
        String truncated = CliCredentials.truncateUtf8("a".repeat(197) + emoji, 200);
        assertThat(truncated).isEqualTo("a".repeat(197));
        assertThat(CliCredentials.truncateUtf8("a".repeat(196) + emoji, 200)).isEqualTo("a".repeat(196) + emoji);
    }

    @Test
    void urlSecretsAreRedactedAndUnsecretTextIsUnchanged() {
        assertThat(CliCredentials.redactUrlSecrets("No suitable driver found for jdbc:x://h/db?user=u&password=s3cret&ssl=true"))
                .isEqualTo("No suitable driver found for jdbc:x://h/db?user=u&password=***&ssl=true");
        assertThat(CliCredentials.redactUrlSecrets("jdbc:sqlserver://h;user=u;Password=s3cret;encrypt=true"))
                .isEqualTo("jdbc:sqlserver://h;user=u;Password=***;encrypt=true");
        assertThat(CliCredentials.redactUrlSecrets("jdbc:x://h/db?PWD = s3cret")).isEqualTo("jdbc:x://h/db?PWD = ***");
        assertThat(CliCredentials.redactUrlSecrets("jdbc:postgresql://u:s3cret@h:5432/db"))
                .isEqualTo("jdbc:postgresql://u:***@h:5432/db");
        assertThat(CliCredentials.redactUrlSecrets("jdbc:postgresql://h:5432/db?user=u"))
                .isEqualTo("jdbc:postgresql://h:5432/db?user=u");
    }

    @Test
    void aConnectFailureNeverCarriesTheUrlPassword() {
        assertThatThrownBy(() -> CliCredentials.connect("jdbc:no-such-driver://h/db?password=s3cret", "u", "p"))
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> {
                    for (Throwable t = failure; t != null; t = t.getCause()) {
                        assertThat(String.valueOf(t.getMessage())).doesNotContain("s3cret");
                    }
                    assertThat(failure.getMessage()).contains("password=***");
                });
    }

    // ---------------------------------------------------------------- entry points

    @Test
    void synchronizeFromClasspathWithoutADataSourceIsADefinitionError() {
        SchemaSynchronizer synchronizer = new SchemaSynchronizer(new ObjectMapper(), null, "/empty-schema.json",
                OPTIONS);
        assertThatThrownBy(synchronizer::synchronizeFromClasspath)
                .isInstanceOf(SchemaDefinitionException.class)
                .hasMessageContaining("requires a DataSource");
    }

    @Test
    void anOptionalMissingDefinitionNeverTouchesTheDataSource() {
        DataSource dataSource = mock(DataSource.class);
        SchemaSynchronizer synchronizer = new SchemaSynchronizer(new ObjectMapper(), dataSource,
                "/no-such-definition.json", new SchemaSynchronizerOptions(
                "app", "schema_synchronizer_history", 7_249_031_147L, false, true, false));
        SchemaSynchronizationResult result = synchronizer.synchronizeFromClasspath();
        assertThat(result.changed()).isFalse();
        verifyNoInteractions(dataSource);
    }

    @Test
    void anUnparseablePathInValidateMainIsADefinitionError() {
        assertThatThrownBy(() -> SchemaSynchronizer.validateMain(new String[]{"bad\0path.json"}))
                .isInstanceOf(SchemaDefinitionException.class);
    }

    // ---------------------------------------------------------------- suppression

    @Test
    void suppressNeverFormsACycle() {
        RuntimeException primary = new RuntimeException("primary");
        RuntimeException secondary = new RuntimeException("secondary");
        secondary.addSuppressed(primary);
        SchemaExceptions.suppress(primary, secondary);
        assertThat(primary.getSuppressed()).isEmpty();

        RuntimeException wrapper = new RuntimeException("wrapper", primary);
        SchemaExceptions.suppress(primary, wrapper);
        assertThat(primary.getSuppressed()).isEmpty();

        RuntimeException unrelated = new RuntimeException("unrelated");
        SchemaExceptions.suppress(primary, unrelated);
        assertThat(primary.getSuppressed()).containsExactly(unrelated);
    }

    // ---------------------------------------------------------------- declarative validation

    @Test
    void tableKeysThatDifferOnlyInCaseAreOneDuplicateTable() {
        for (DatabaseDialect dialect : DatabaseDialect.values()) {
            Map<String, SchemaDefinition.TableDef> tables = new LinkedHashMap<>();
            tables.put("Users", table(dialect, "users", List.of()));
            tables.put("users", table(dialect, "users", List.of()));
            assertThatThrownBy(() -> SchemaSynchronizer.validateDeclarative(
                    new SchemaDefinition(2, dialect.id(), tables, List.of()), dialect, OPTIONS))
                    .as("%s", dialect)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("duplicate table definition: users");
        }
    }

    @Test
    void indexNamesAreUniquePerSchemaOnPostgresAndOracleButPerTableElsewhere() {
        for (DatabaseDialect dialect : DatabaseDialect.values()) {
            Map<String, SchemaDefinition.TableDef> tables = new LinkedHashMap<>();
            tables.put("a", table(dialect, "a", List.of(index(dialect, "idx_created", "a"))));
            tables.put("b", table(dialect, "b", List.of(index(dialect, "idx_created", "b"))));
            SchemaDefinition definition = new SchemaDefinition(2, dialect.id(), tables, List.of());
            if (dialect == DatabaseDialect.POSTGRESQL || dialect == DatabaseDialect.ORACLE) {
                assertThatThrownBy(() -> SchemaSynchronizer.validateDeclarative(definition, dialect, OPTIONS))
                        .as("%s", dialect)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("idx_created").hasMessageContaining("unique per schema");
            } else {
                assertThatCode(() -> SchemaSynchronizer.validateDeclarative(definition, dialect, OPTIONS))
                        .as("%s", dialect).doesNotThrowAnyException();
            }
        }
    }

    @Test
    void aPostgresIndexCannotTakeATableName() {
        Map<String, SchemaDefinition.TableDef> tables = new LinkedHashMap<>();
        tables.put("a", table(DatabaseDialect.POSTGRESQL, "a", List.of(index(DatabaseDialect.POSTGRESQL, "b", "a"))));
        tables.put("b", table(DatabaseDialect.POSTGRESQL, "b", List.of()));
        assertThatThrownBy(() -> SchemaSynchronizer.validateDeclarative(
                new SchemaDefinition(2, "postgresql", tables, List.of()), DatabaseDialect.POSTGRESQL, OPTIONS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("share one namespace");
        // Other dialects keep index and table namespaces apart.
        Map<String, SchemaDefinition.TableDef> mysql = new LinkedHashMap<>();
        mysql.put("a", table(DatabaseDialect.MYSQL, "a", List.of(index(DatabaseDialect.MYSQL, "b", "a"))));
        mysql.put("b", table(DatabaseDialect.MYSQL, "b", List.of()));
        assertThatCode(() -> SchemaSynchronizer.validateDeclarative(
                new SchemaDefinition(2, "mysql", mysql, List.of()), DatabaseDialect.MYSQL, OPTIONS))
                .doesNotThrowAnyException();
    }

    private static SchemaDefinition.TableDef table(DatabaseDialect dialect, String name, List<String> indexes) {
        String type = dialect == DatabaseDialect.ORACLE ? "NUMBER(19)" : "BIGINT";
        return new SchemaDefinition.TableDef("CREATE TABLE " + name + " (id " + type + " NOT NULL, PRIMARY KEY (id))",
                List.of(new SchemaDefinition.ColumnDef("id", type + " NOT NULL")), indexes);
    }

    private static String index(DatabaseDialect dialect, String name, String table) {
        return "CREATE INDEX " + (dialect.supportsCreateIndexIfNotExists() ? "IF NOT EXISTS " : "") + name
                + " ON " + table + " (id)";
    }

    @Test
    void aQuotedMixedCaseKeyColumnIsTheFoldedColumnOnlyWhereColumnsCompareInsensitively() {
        for (DatabaseDialect dialect : List.of(DatabaseDialect.MYSQL, DatabaseDialect.MARIADB)) {
            IndexDefinition quoted = IndexDefinition.parse("CREATE INDEX idx ON t (`UserId`)", dialect);
            IndexDefinition bare = IndexDefinition.parse("CREATE INDEX idx ON t (userid)", dialect);
            assertThat(quoted.hasSameStructure(bare)).as("%s", dialect).isTrue();
        }
        // PostgreSQL compares case-sensitively: "UserId" is a different column from userid.
        IndexDefinition pgQuoted = IndexDefinition.parse("CREATE INDEX idx ON t (\"UserId\")", DatabaseDialect.POSTGRESQL);
        IndexDefinition pgBare = IndexDefinition.parse("CREATE INDEX idx ON t (userid)", DatabaseDialect.POSTGRESQL);
        assertThat(pgQuoted.hasSameStructure(pgBare)).isFalse();
        IndexDefinition pgFolded = IndexDefinition.parse("CREATE INDEX idx ON t (\"userid\")", DatabaseDialect.POSTGRESQL);
        assertThat(pgFolded.hasSameStructure(pgBare)).isTrue();
    }

    @Test
    void anInlinePrimaryKeyAfterATypeWithACommaIsDetected() {
        assertThat(SchemaSynchronizer.primaryKeyColumns(
                "CREATE TABLE t (id NUMBER(19,0) PRIMARY KEY, label VARCHAR2(20))", DatabaseDialect.ORACLE))
                .containsExactly("id");
        assertThat(SchemaSynchronizer.primaryKeyColumns(
                "CREATE TABLE t (id DECIMAL(20,0) NOT NULL PRIMARY KEY)", DatabaseDialect.MYSQL))
                .containsExactly("id");
        assertThat(SchemaSynchronizer.primaryKeyColumns(
                "CREATE TABLE t (id NUMERIC(10,2) DEFAULT (COALESCE(1, 2)) NOT NULL PRIMARY KEY)",
                DatabaseDialect.POSTGRESQL))
                .containsExactly("id");
        // The key belongs to the column that declares it, never to an earlier one.
        assertThat(SchemaSynchronizer.primaryKeyColumns(
                "CREATE TABLE t (a DECIMAL(5,1), b INT NOT NULL PRIMARY KEY)", DatabaseDialect.MYSQL))
                .containsExactly("b");
        assertThat(SchemaSynchronizer.primaryKeyColumns(
                "CREATE TABLE t (a DECIMAL(5,1), b INT NOT NULL)", DatabaseDialect.MYSQL))
                .isEmpty();
    }
}
