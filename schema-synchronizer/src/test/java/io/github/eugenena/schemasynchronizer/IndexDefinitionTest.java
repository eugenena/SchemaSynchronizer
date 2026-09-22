package io.github.eugenena.schemasynchronizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IndexDefinitionTest {
    @Test
    void normalizesSerializerAndCatalogForms() {
        IndexDefinition target = IndexDefinition.parse(
                "CREATE UNIQUE INDEX IF NOT EXISTS work_items_pkey ON public.work_items USING btree (id)");
        IndexDefinition live = IndexDefinition.parse(
                "CREATE UNIQUE INDEX work_items_pkey ON public.work_items USING btree (id)");

        assertThat(target.name()).isEqualTo("work_items_pkey");
        assertThat(target.schema()).isEqualTo("public");
        assertThat(target.table()).isEqualTo("work_items");
        assertThat(target.canonicalSql()).isEqualTo(live.canonicalSql());
    }

    @Test
    void comparesIndexStructureSeparatelyFromPostgresPredicateRendering() {
        IndexDefinition serialized = IndexDefinition.parse(
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_status ON jobs (tenant_id) "
                        + "WHERE status = ANY ((ARRAY['DRAFT'::varchar, 'READY'::varchar])::text[])");
        IndexDefinition live = IndexDefinition.parse(
                "CREATE UNIQUE INDEX uq_status ON public.jobs USING btree (tenant_id) "
                        + "WHERE status = ANY (ARRAY[('DRAFT'::varchar)::text, ('READY'::varchar)::text])");

        assertThat(serialized.hasSameStructure(live)).isTrue();
        assertThat(serialized.hasEquivalentPredicate(live)).isTrue();
        assertThat(serialized.canonicalSql()).isNotEqualTo(live.canonicalSql());
        assertThat(serialized.hasSameStructure(IndexDefinition.parse(
                "CREATE UNIQUE INDEX uq_status ON jobs (id) WHERE status = 'DRAFT'"))).isFalse();
    }

    @Test
    void treatsGenuinelyDifferentPredicatesAsDrift() {
        IndexDefinition expected = IndexDefinition.parse(
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_status ON jobs (tenant_id) WHERE status = 'ACTIVE'");
        IndexDefinition live = IndexDefinition.parse(
                "CREATE UNIQUE INDEX uq_status ON jobs (tenant_id) WHERE status = 'DRAFT'");

        assertThat(expected.hasSameStructure(live)).isTrue();
        assertThat(expected.hasEquivalentPredicate(live)).isFalse();
    }

    @Test
    void findsOnlyTopLevelWhereOutsideExpressionLiterals() {
        IndexDefinition expected = IndexDefinition.parse(
                "CREATE INDEX IF NOT EXISTS idx_text ON messages ((replace(body, ' WHERE ', ''))) "
                        + "WHERE archived = false");
        IndexDefinition changedExpression = IndexDefinition.parse(
                "CREATE INDEX idx_text ON messages ((replace(title, ' WHERE ', ''))) WHERE archived = false");

        assertThat(expected.structuralSql()).contains("replace(body,' WHERE ','')");
        assertThat(expected.predicateSql()).isEqualTo("archived = false");
        assertThat(expected.hasSameStructure(changedExpression)).isFalse();
    }
}
