package io.github.eugenena.schemasynchronizer;

final class SqlIdentifiers {
    private SqlIdentifiers() {}

    static String requireIdentifier(String value, String label) {
        if (value == null || value.length() > 63 || !value.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("invalid " + label + ": " + value);
        }
        return value.toLowerCase();
    }

    static String qualified(String schema, String name) {
        return requireIdentifier(schema, "schema") + "." + requireIdentifier(name, "identifier");
    }
}
