package io.github.eugenena.schemasynchronizer;

/**
 * Standalone entry point that serializes a source database to a schema definition.
 *
 * <p>Usage:
 * <pre>
 * SchemaSerializer &lt;jdbc-url&gt; &lt;user&gt; &lt;password-or--&gt; &lt;schema&gt; &lt;output-path&gt;
 * </pre>
 * Use {@code -} for the password to read {@code SCHEMA_DB_PASSWORD}.
 */
public final class SchemaSerializer {

    private SchemaSerializer() {
    }

    public static void main(String[] args) throws Exception {
        SchemaSnapshotWriter.main(args);
    }
}
