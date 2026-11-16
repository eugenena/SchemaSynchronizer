// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinkaillc.schemasynchronizer.SchemaDefinition;
import com.thinkaillc.schemasynchronizer.SchemaDefinitionValidator;
import com.thinkaillc.schemasynchronizer.SchemaFingerprints;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Offline definition-profile commands (2.3.0+): structural fingerprints and two-profile
 * comparison plus FK preservation coverage findings. No database connection is used.
 */
public final class SchemaDefinitionProfilesCli {

    private SchemaDefinitionProfilesCli() {
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        String command = args[0];
        try {
            if ("fingerprint".equals(command)) {
                if (args.length != 2) {
                    err.println("usage: fingerprint <schema-file>");
                    return 2;
                }
                SchemaDefinition definition = new ObjectMapper()
                        .readValue(Path.of(args[1]).toFile(), SchemaDefinition.class);
                out.println(SchemaFingerprints.definitionFingerprint(definition));
                return 0;
            }
            if ("compare".equals(command)) {
                if (args.length != 3) {
                    err.println("usage: compare <schema-file-a> <schema-file-b>");
                    return 2;
                }
                ObjectMapper mapper = new ObjectMapper();
                SchemaDefinition left = mapper.readValue(Path.of(args[1]).toFile(), SchemaDefinition.class);
                SchemaDefinition right = mapper.readValue(Path.of(args[2]).toFile(), SchemaDefinition.class);
                List<String> differences = SchemaDefinitionValidator.compare(left, right);
                List<String> findings = SchemaDefinitionValidator.preservationFindings(left);
                differences.forEach(diff -> out.println("diff: " + diff));
                findings.forEach(finding -> out.println("preservation: " + finding));
                return differences.isEmpty() ? 0 : 1;
            }
            err.println("Unknown profiles command: " + command);
            return 2;
        } catch (Exception exception) {
            String message = exception.getMessage();
            err.println("SchemaSynchronizer failed: "
                    + (message == null || message.isBlank()
                    ? exception.getClass().getSimpleName() : message));
            return 1;
        }
    }
}
