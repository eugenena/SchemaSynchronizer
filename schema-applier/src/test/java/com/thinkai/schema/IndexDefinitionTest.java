package com.thinkai.schema;

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
}
