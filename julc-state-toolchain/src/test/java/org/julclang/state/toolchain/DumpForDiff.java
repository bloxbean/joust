package org.julclang.state.toolchain;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Diagnostics helper (not a test): writes the toolchain's outputs for an example under build/i1-dump. */
public class DumpForDiff {
    public static void main(String[] args) throws Exception {
        for (String example : args) {
            Path module = Path.of("../examples").resolve(example);
            Map<String, String> files = new LinkedHashMap<>();
            for (String dir : new String[]{"src/main/java", "src/main/jsc"}) {
                Path root = module.resolve(dir);
                if (!Files.isDirectory(root)) continue;
                try (Stream<Path> s = Files.walk(root)) {
                    for (Path p : s.filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".jsc")).toList()) {
                        String text = Files.readString(p);
                        if (p.toString().endsWith(".jsc") || text.contains("@StateContract") || text.contains("@OnchainLibrary")) files.put(root.relativize(p).toString(), text);
                    }
                }
            }
            var r = Toolchain.build(files, Map.of());
            Path out = Path.of("build/i1-dump", example);
            Files.createDirectories(out);
            for (var c : r.contracts()) {
                Files.writeString(out.resolve(c.name() + ".description.json"), c.descriptionJson());
                Files.writeString(out.resolve(c.validatorName() + ".java"), c.validatorSource());
                Files.writeString(out.resolve(c.validatorName() + ".cbor"), c.scriptCborHex());
            }
            System.out.println(example + ": " + r.ok() + " " + r.diagnostics());
        }
    }
}
