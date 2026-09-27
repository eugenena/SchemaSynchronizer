// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL type names outside the synchronizer's search_path (pg_catalog plus the configured
 * schema): pgjdbc TYPE_NAME and format_type qualify them, a declaration may qualify them, and
 * types compare by name plus schema only when the declaration names one.
 */
class PostgresTypeSchemaContractTest {

    @Test
    void catalogRenderedTypesSplitIntoSchemaAndName() {
        assertSplit("\"public\".\"vector\"", "public", "vector");
        assertSplit("public.vector(3)", "public", "vector(3)");
        assertSplit("\"My \"\"Ext\"\"\".\"vector\"", "My \"Ext\"", "vector");
        assertSplit("\"extensions\".citext", "extensions", "citext");
        assertSplit("vector", null, "vector");
        assertSplit("character varying", null, "character varying");
        assertSplit("\"char\"", null, "\"char\"");
        assertSplit("numeric(5,2)", null, "numeric(5,2)");
        assertSplit("timestamp(3) without time zone", null, "timestamp(3) without time zone");
    }

    private static void assertSplit(String rendered, String schema, String name) {
        ColumnDefinitionParser.RenderedType type = ColumnDefinitionParser.splitRenderedType(rendered);
        assertThat(type.schema()).as(rendered).isEqualTo(schema);
        assertThat(type.name()).as(rendered).isEqualTo(name);
    }

    @Test
    void aDeclaredTypeQualifierIsFoldedLikeAPostgresIdentifier() {
        assertThat(ColumnDefinitionParser.typeSchema("vector(3)")).isNull();
        assertThat(ColumnDefinitionParser.typeSchema("public.vector(3) NOT NULL")).isEqualTo("public");
        assertThat(ColumnDefinitionParser.typeSchema("PUBLIC.VECTOR(3)")).isEqualTo("public");
        assertThat(ColumnDefinitionParser.typeSchema("public . vector(3)")).isEqualTo("public");
        assertThat(ColumnDefinitionParser.typeSchema("\"Public\".vector(3)")).isEqualTo("Public");
        assertThat(ColumnDefinitionParser.typeSchema("\"a\"\"b\".vector(3)")).isEqualTo("a\"b");
        for (String unqualified : List.of("NUMERIC(5,2) DEFAULT 1.5", "VARCHAR(10) DEFAULT 'a.b'",
                "DOUBLE PRECISION", "TIMESTAMP DEFAULT now()", "BIGINT NOT NULL")) {
            assertThat(ColumnDefinitionParser.typeSchema(unqualified)).as(unqualified).isNull();
        }
    }

    @Test
    void aQualifiedDeclarationParsesToTheBareType() {
        ColumnSpec qualified = ColumnDefinitionParser.parse("public.vector(3) NOT NULL");
        ColumnSpec bare = ColumnDefinitionParser.parse("vector(3) NOT NULL");
        assertThat(qualified).isEqualTo(bare);
        assertThat(qualified.baseType()).isEqualTo("VECTOR");
        assertThat(qualified.length()).isEqualTo(3);
        assertThat(ColumnDefinitionParser.parse("\"ext\".citext DEFAULT 'a.b'").defaultExpr()).isEqualTo("'a.b'");
    }

    @Test
    void vectorDimensionReadsQualifiedAndUnqualifiedRenderings() {
        assertThat(ColumnDefinitionParser.vectorDimension("vector(3)")).isEqualTo(3);
        assertThat(ColumnDefinitionParser.vectorDimension("public.vector(1536)")).isEqualTo(1536);
        assertThat(ColumnDefinitionParser.vectorDimension("\"my ext\".vector(2)")).isEqualTo(2);
        for (String other : Arrays.asList("vector", "public.vectorx(3)", "halfvec(3)", null)) {
            assertThat(ColumnDefinitionParser.vectorDimension(other)).as(other).isNull();
        }
    }

    @Test
    void typesCompareBySchemaOnlyWhenTheDeclarationNamesOne() {
        SchemaSynchronizer synchronizer = new SchemaSynchronizer(new ObjectMapper(), null, "",
                new SchemaSynchronizerOptions("app", "schema_synchronizer_history", 1L, false, true, true));
        // declared definition, live qualifier (null = visible: pg_catalog or the configured schema), conflict?
        Object[][] cells = {
                {"vector(3)", null, false},
                {"vector(3)", "public", false},
                {"public.vector(3)", "public", false},
                {"\"public\".vector(3)", "public", false},
                {"app.vector(3)", null, false},
                {"pg_catalog.int4", null, false},
                {"public.vector(3)", null, true},
                {"extensions.vector(3)", "public", true},
                {"\"Public\".vector(3)", "public", true},
                {"app.vector(3)", "public", true},
        };
        for (Object[] cell : cells) {
            String conflict = synchronizer.typeSchemaConflict((String) cell[0], (String) cell[1]);
            assertThat(conflict != null).as("%s vs live %s", cell[0], cell[1]).isEqualTo(cell[2]);
        }
    }

    @Test
    void aDryRunReadsTheColumnsOfATableItWouldCreateFromCreateSql() {
        for (DatabaseDialect dialect : DatabaseDialect.values()) {
            String q = SqlIdentifiers.quote(dialect, "order");
            assertThat(SchemaSynchronizer.createdColumns("CREATE TABLE IF NOT EXISTS items (id BIGINT NOT NULL, "
                    + q + " NUMERIC(19,0) DEFAULT 1, note VARCHAR(10) DEFAULT 'a, b)', CONSTRAINT pk PRIMARY KEY (id), "
                    + "UNIQUE (note), CHECK (id > 0 AND " + q + " IN (1, 2)), FOREIGN KEY (id) REFERENCES t (id))",
                    dialect)).as("%s", dialect).containsExactly("id", "order", "note");
            assertThat(SchemaSynchronizer.createdColumns("CREATE TABLE items (id BIGINT PRIMARY KEY)", dialect))
                    .containsExactly("id");
            assertThat(SchemaSynchronizer.createdColumns("CREATE TABLE items AS SELECT 1 AS id", dialect)).isNull();
            assertThat(SchemaSynchronizer.createdColumns(null, dialect)).isNull();
        }
        assertThat(SchemaSynchronizer.createdColumns("CREATE TABLE items (id INT, KEY idx_id (id), "
                + "INDEX idx2 (id), FULLTEXT ft (id))", DatabaseDialect.MYSQL)).containsExactly("id");
        assertThat(SchemaSynchronizer.createdColumns("CREATE TABLE items (\"My Col\" INT)",
                DatabaseDialect.POSTGRESQL)).as("undeclarable name").isNull();
    }

    @Test
    void keywordsThatAreLegalColumnNamesOnPostgresAreReadAsColumns() {
        DatabaseDialect pg = DatabaseDialect.POSTGRESQL;
        assertThat(SchemaSynchronizer.createdColumns("CREATE TABLE items (id INT, key TEXT, index INT, "
                + "fulltext TEXT, spatial TEXT, exclude TEXT, period DATE)", pg))
                .containsExactly("id", "key", "index", "fulltext", "spatial", "exclude", "period");
        assertThat(SchemaSynchronizer.createdColumns("CREATE TABLE items (id INT, "
                + "EXCLUDE USING gist (id WITH =), EXCLUDE (id WITH =))", pg)).containsExactly("id");
        assertThat(SchemaSynchronizer.createdColumns("CREATE TABLE items (id INT, valid_from DATE, "
                + "valid_to DATE, PERIOD FOR valid (valid_from, valid_to))", DatabaseDialect.ORACLE))
                .containsExactly("id", "valid_from", "valid_to");
        assertThat(SchemaSynchronizer.createdColumns("CREATE TABLE items (id INT, INDEX ix (id))",
                DatabaseDialect.SQLSERVER)).containsExactly("id");
        assertThat(SchemaSynchronizer.createdColumns("CREATE TABLE items (id INT, primary INT, foreign INT, "
                + "constraint INT, PRIMARY KEY (id), CONSTRAINT fk FOREIGN KEY (id) REFERENCES t (id))",
                DatabaseDialect.ORACLE)).containsExactly("id", "primary", "foreign", "constraint");
    }

    @Test
    void createSqlThatCopiesColumnsFromAnotherTableIsUnreadable() {
        assertThat(SchemaSynchronizer.createdColumns("CREATE TABLE child (LIKE parent INCLUDING ALL)",
                DatabaseDialect.POSTGRESQL)).isNull();
        assertThat(SchemaSynchronizer.createdColumns("CREATE TABLE child (LIKE parent)", DatabaseDialect.MYSQL))
                .isNull();
        assertThat(SchemaSynchronizer.createdColumns("CREATE TABLE child (extra INT) INHERITS (parent)",
                DatabaseDialect.POSTGRESQL)).isNull();
        assertThat(SchemaSynchronizer.createdColumns("CREATE TABLE items (id INT, name TEXT CHECK (name LIKE 'a%'))",
                DatabaseDialect.POSTGRESQL)).as("LIKE inside a CHECK is not a copy").containsExactly("id", "name");
    }

    @Test
    void commasInsideBracketsDoNotSplitColumns() {
        DatabaseDialect pg = DatabaseDialect.POSTGRESQL;
        assertThat(SchemaSynchronizer.createdColumns("CREATE TABLE items (id INT, vals INT[] DEFAULT ARRAY[1, 2], "
                + "tags TEXT[] DEFAULT ARRAY['a', 'b'], name TEXT)", pg)).containsExactly("id", "vals", "tags", "name");
        assertThat(SchemaSynchronizer.createdColumns("CREATE TABLE items (id INT, tags INT[] DEFAULT ARRAY[1, extra], "
                + "name TEXT)", pg)).containsExactly("id", "tags", "name");
        assertThat(SchemaSynchronizer.createdColumns("CREATE TABLE items ([id] INT, [a,]]b] INT)",
                DatabaseDialect.SQLSERVER)).as("undeclarable name, but brackets balanced").isNull();
        assertThat(SchemaSynchronizer.createdColumns("CREATE TABLE items (id INT, name)", pg))
                .as("a bare name with no type is unreadable").isNull();
    }

    @Test
    void aQuotedIndexColumnInTheWrongSpellingGetsTheFoldedNameError() {
        IndexDefinition wrong = IndexDefinition.parse("CREATE INDEX ix ON t ([Email])", DatabaseDialect.SQLSERVER);
        assertThatThrownBy(() -> SchemaSynchronizer.requireComparableIndex(wrong, DatabaseDialect.SQLSERVER))
                .hasMessageContaining("folded name [email]");
        IndexDefinition right = IndexDefinition.parse("CREATE INDEX ix ON t ([email])", DatabaseDialect.SQLSERVER);
        SchemaSynchronizer.requireComparableIndex(right, DatabaseDialect.SQLSERVER);
        IndexDefinition oracle = IndexDefinition.parse("CREATE INDEX ix ON t (\"Email\")", DatabaseDialect.ORACLE);
        assertThatThrownBy(() -> SchemaSynchronizer.requireComparableIndex(oracle, DatabaseDialect.ORACLE))
                .hasMessageContaining("folded name \"EMAIL\"");
        IndexDefinition expression = IndexDefinition.parse("CREATE INDEX ix ON t (lower(email))",
                DatabaseDialect.SQLSERVER);
        assertThatThrownBy(() -> SchemaSynchronizer.requireComparableIndex(expression, DatabaseDialect.SQLSERVER))
                .hasMessageContaining("plain column list");
    }

    @Test
    void qualifiedColumnTypesAreAcceptedOnlyOnPostgres() {
        for (DatabaseDialect dialect : DatabaseDialect.values()) {
            SchemaDefinition definition = new SchemaDefinition(2, dialect.id(), Map.of("items",
                    new SchemaDefinition.TableDef("CREATE TABLE items (id BIGINT NOT NULL, tag public.citext, "
                            + "PRIMARY KEY (id))",
                            List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                    new SchemaDefinition.ColumnDef("tag", "public.citext")), List.of())), List.of());
            String schema = dialect == DatabaseDialect.POSTGRESQL ? "public" : "app";
            if (dialect == DatabaseDialect.POSTGRESQL) {
                assertThatCode(() -> SchemaDefinitionValidator.validate(definition, schema))
                        .doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> SchemaDefinitionValidator.validate(definition, schema)).as("%s", dialect)
                        .isInstanceOf(SchemaDefinitionException.class)
                        .hasMessageContaining("schema-qualified column types are supported only on PostgreSQL");
            }
        }
    }
}
