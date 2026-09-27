// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden SHA-256 digests for the change-set checksum. Every 1.x user has these values in the
 * {@code checksum} column of their history table; if any assertion here changes, every existing
 * ledger reports "checksum mismatch". The digests were computed outside Java from the v1.2.0
 * algorithm: SHA-256 over id, NUL, each statement (CRLF to LF, trimmed) followed by NUL, the
 * verification SQL (CRLF to LF, trimmed; absent when null), NUL, and the effective phase name.
 * Never regenerate them from {@link ChangeSetExecutor#checksum}.
 */
class ChangeSetChecksumGoldenTest {

    static final String CREATE_ITEMS = "CREATE TABLE items (id BIGINT PRIMARY KEY)";
    static final String CREATE_ITEMS_BEFORE =
            "1189b2a96781f0e61345e184107065953dc54c6d14af8b5ceeaba927e41bd112";
    private static final String MULTILINE =
            "d421ac4985f2eeb3c5c82b04f5243a1b08d7d93a8544238ced679f8515521c6b";
    private static final String SELECT_ONE =
            "04a68a06e1eaa51416ae4dfeed702bb280237a001a5b37f12b7a1f181c2168d8";

    @Test
    void beforeSchemaWithoutVerification() throws Exception {
        assertThat(checksum(new SchemaDefinition.ChangeSet("001-create", "d", List.of(CREATE_ITEMS), null,
                SchemaDefinition.ChangeSet.Phase.BEFORE_SCHEMA))).isEqualTo(CREATE_ITEMS_BEFORE);
    }

    @Test
    void nullPhaseHashesAsBeforeSchema() throws Exception {
        assertThat(checksum(new SchemaDefinition.ChangeSet("001-create", "d", List.of(CREATE_ITEMS), null, null)))
                .isEqualTo(CREATE_ITEMS_BEFORE);
        assertThat(checksum(new SchemaDefinition.ChangeSet("001-create", "d", List.of(CREATE_ITEMS))))
                .isEqualTo(CREATE_ITEMS_BEFORE);
    }

    @Test
    void afterSchemaPhaseChangesTheDigest() throws Exception {
        assertThat(checksum(new SchemaDefinition.ChangeSet("001-create", "d", List.of(CREATE_ITEMS), null,
                SchemaDefinition.ChangeSet.Phase.AFTER_SCHEMA)))
                .isEqualTo("e5e8f9d69fe2510915242f3f73a924f76d5c12740481f454a8333dd4b8c6c74d");
    }

    @Test
    void descriptionIsNotPartOfTheDigest() throws Exception {
        assertThat(checksum(new SchemaDefinition.ChangeSet("001-create", "reworded later", List.of(CREATE_ITEMS))))
                .isEqualTo(CREATE_ITEMS_BEFORE);
    }

    @Test
    void multipleStatementsWithVerification() throws Exception {
        assertThat(checksum(new SchemaDefinition.ChangeSet("002-index", "d", List.of(
                        "CREATE INDEX idx_items_note ON items (note)", "ALTER TABLE items ADD COLUMN note TEXT"),
                "SELECT COUNT(*) = 1 FROM pg_indexes WHERE indexname = 'idx_items_note'",
                SchemaDefinition.ChangeSet.Phase.BEFORE_SCHEMA)))
                .isEqualTo("6b94bdc613c515dfed48a7077e3816dc5cc8d34c5324c8ceb3e7809a808c6c63");
    }

    @Test
    void lfCrlfAndSurroundingWhitespaceHashIdentically() throws Exception {
        for (SchemaDefinition.ChangeSet change : List.of(
                multiline("CREATE TABLE t (\n  id INT\n)", "SELECT 1\nFROM t"),
                multiline("CREATE TABLE t (\r\n  id INT\r\n)", "SELECT 1\r\nFROM t"),
                multiline("  \n CREATE TABLE t (\n  id INT\n) \t\n", "\n SELECT 1\nFROM t  "))) {
            assertThat(checksum(change)).as(change.statements().get(0)).isEqualTo(MULTILINE);
        }
    }

    @Test
    void emptyAndMissingVerificationHashIdentically() throws Exception {
        assertThat(checksum(new SchemaDefinition.ChangeSet("004-empty", "d", List.of("SELECT 1"), "")))
                .isEqualTo(SELECT_ONE);
        assertThat(checksum(new SchemaDefinition.ChangeSet("004-empty", "d", List.of("SELECT 1"), null)))
                .isEqualTo(SELECT_ONE);
    }

    @Test
    void nonAsciiIsHashedAsUtf8() throws Exception {
        assertThat(checksum(new SchemaDefinition.ChangeSet("005-\u00fcn\u00efcode", "d",
                List.of("COMMENT ON TABLE items IS 'caf\u00e9 \u2615'"))))
                .isEqualTo("deba8930aba077759ff7de25bdf84e7f18aaaa9727abe5ce184cdd3c83f1d1de");
    }

    @Test
    void statementBoundariesAreSignificant() throws Exception {
        assertThat(checksum(new SchemaDefinition.ChangeSet("006-split", "d", List.of("ab", "c"))))
                .isEqualTo("db97250f3103569e02c24b00a1ab7927351c406ad8fce808a5b37f341b0a90c1");
        assertThat(checksum(new SchemaDefinition.ChangeSet("006-split", "d", List.of("a", "bc"))))
                .isEqualTo("b288ef504e38e3e2d18a22c4e31ea0a2726d5b609a3c3ef51e2d8e514170eb35");
    }

    @Test
    void jsonDefinitionHashesLikeTheEquivalentRecord() throws Exception {
        SchemaDefinition definition = new ObjectMapper().readValue("""
                {"formatVersion": 2, "tables": {}, "changes": [
                  {"id": "003-multiline", "description": "d",
                   "statements": ["CREATE TABLE t (\\r\\n  id INT\\r\\n)"],
                   "verificationSql": "SELECT 1\\r\\nFROM t", "phase": "AFTER_SCHEMA"},
                  {"id": "001-create", "statements": ["CREATE TABLE items (id BIGINT PRIMARY KEY)"]}
                ]}""", SchemaDefinition.class);
        assertThat(checksum(definition.changes().get(0))).isEqualTo(MULTILINE);
        assertThat(checksum(definition.changes().get(1))).isEqualTo(CREATE_ITEMS_BEFORE);
    }

    private static SchemaDefinition.ChangeSet multiline(String statement, String verification) {
        return new SchemaDefinition.ChangeSet("003-multiline", "d", List.of(statement), verification,
                SchemaDefinition.ChangeSet.Phase.AFTER_SCHEMA);
    }

    private static String checksum(SchemaDefinition.ChangeSet change) throws Exception {
        return ChangeSetExecutor.checksum(change);
    }
}
