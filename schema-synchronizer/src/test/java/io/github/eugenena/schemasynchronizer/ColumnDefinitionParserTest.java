package io.github.eugenena.schemasynchronizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnDefinitionParserTest {

    @Test
    void parsesVarcharNotNullWithDefault() {
        var spec = ColumnDefinitionParser.parse("VARCHAR(100) NOT NULL DEFAULT 'PENDING'");
        assertThat(spec.baseType()).isEqualTo("VARCHAR");
        assertThat(spec.length()).isEqualTo(100);
        assertThat(spec.notNull()).isTrue();
        assertThat(ColumnDefinitionParser.normalizeDefault(spec.defaultExpr()))
                .isEqualTo("'PENDING'");
    }

    @Test
    void stripsPostgresTypeCastOnDefault() {
        assertThat(ColumnDefinitionParser.normalizeDefault("'PENDING'::character varying"))
                .isEqualTo("'PENDING'");
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

    @Test
    void parsesNumericPrecisionAndScale() {
        var spec = ColumnDefinitionParser.parse("NUMERIC(18, 4) NOT NULL");
        assertThat(spec.baseType()).isEqualTo("NUMERIC");
        assertThat(spec.length()).isEqualTo(18);
        assertThat(spec.scale()).isEqualTo(4);
        assertThat(spec.notNull()).isTrue();
    }
}
