package com.thinkai.schema;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnDefinitionParserTest {

    @Test
    void parsesVarcharNotNullWithDefault() {
        var spec = ColumnDefinitionParser.parse("VARCHAR(100) NOT NULL DEFAULT 'JOB_SEARCHING'");
        assertThat(spec.baseType()).isEqualTo("VARCHAR");
        assertThat(spec.length()).isEqualTo(100);
        assertThat(spec.notNull()).isTrue();
        assertThat(ColumnDefinitionParser.normalizeDefault(spec.defaultExpr()))
                .isEqualTo("'JOB_SEARCHING'");
    }

    @Test
    void stripsPostgresTypeCastOnDefault() {
        assertThat(ColumnDefinitionParser.normalizeDefault("'JOB_SEARCHING'::character varying"))
                .isEqualTo("'JOB_SEARCHING'");
        assertThat(ColumnDefinitionParser.normalizeDefault("false"))
                .isEqualTo("false");
    }

    @Test
    void parsesTextNullableNoDefault() {
        var spec = ColumnDefinitionParser.parse("TEXT");
        assertThat(spec.baseType()).isEqualTo("TEXT");
        assertThat(spec.length()).isNull();
        assertThat(spec.notNull()).isFalse();
        assertThat(spec.defaultExpr()).isNull();
    }

    @Test
    void parsesBigintDefault() {
        var spec = ColumnDefinitionParser.parse("BIGINT DEFAULT 0");
        assertThat(spec.baseType()).isEqualTo("BIGINT");
        assertThat(spec.notNull()).isFalse();
        assertThat(ColumnDefinitionParser.normalizeDefault(spec.defaultExpr())).isEqualTo("0");
    }
}
