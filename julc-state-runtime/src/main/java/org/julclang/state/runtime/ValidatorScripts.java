package org.julclang.state.runtime;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.clientlib.PlutusDataAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The compiled script of a generated validator, read from beside the validator class (ADR-0018 M1c review S1/F1).
 * <p>
 * JuLC writes a validator's script to {@code META-INF/plutus/<SimpleName>.plutus.json} and its loader
 * ({@code JulcScriptLoader}) reads that name through the class loader. Two validators with the same simple name in
 * different packages ({@code …vault.VaultValidator}, {@code …dsl.VaultValidator}) therefore share one resource name, and
 * on one class path the first copy found would serve both: the runtime would deploy and evaluate the other contract.
 * Here the script is read from the validator's own code source (its classes directory or jar). If that is not
 * possible, a single copy on the class path is used, and more than one copy is refused. A code source that itself holds
 * two validators of that simple name (a shaded jar, where one script overwrote the other) is refused too. Until JuLC
 * keys scripts by their qualified name (bloxbean/julc, label {@code joust}), this keeps the runtime fail-closed.
 */
public final class ValidatorScripts {
    private ValidatorScripts() {}

    private static final Pattern CBOR = Pattern.compile("\"cborHex\"\\s*:\\s*\"([0-9a-fA-F]+)\"");
    private static final Map<Class<?>, String> CACHE = new ConcurrentHashMap<>();

    /** The resource JuLC writes the script of {@code validator} to. */
    public static String resource(Class<?> validator) {
        return "META-INF/plutus/" + validator.getSimpleName() + ".plutus.json";
    }

    /** The script of {@code validator}, without parameters. */
    public static PlutusV3Script load(Class<?> validator) {
        return PlutusV3Script.builder().cborHex(cborHex(validator)).build();
    }

    /** The script of {@code validator} with its deploy-time {@code params} applied (as {@code JulcScriptLoader.load(cls, params)}). */
    public static PlutusV3Script load(Class<?> validator, PlutusData params) {
        return JulcScriptAdapter.fromProgram(JulcScriptAdapter.toProgram(cborHex(validator))
                .applyParams(PlutusDataAdapter.fromClientLib(params)));
    }

    /** The {@code cborHex} of the script beside {@code validator}. */
    public static String cborHex(Class<?> validator) {
        return CACHE.computeIfAbsent(validator, ValidatorScripts::find);
    }

    private static String find(Class<?> validator) {
        URL where = null;
        var cs = validator.getProtectionDomain() == null ? null : validator.getProtectionDomain().getCodeSource();
        if (cs != null) where = cs.getLocation();
        ClassLoader loader = validator.getClassLoader() != null ? validator.getClassLoader() : ClassLoader.getSystemClassLoader();
        return cborOf(resolve(validator.getName(), validator.getSimpleName(), where, loader), validator.getName());
    }

    /**
     * The script JSON of the validator {@code name} (simple name {@code simple}) whose classes come from {@code where}:
     * from there if it holds the resource (and no second validator of that simple name), else the only copy on
     * {@code loader}'s class path. Refuses, with a {@link JoustException}, when the copy cannot be told apart.
     */
    static String resolve(String name, String simple, URL where, ClassLoader loader) {
        String resource = "META-INF/plutus/" + simple + ".plutus.json";
        if (where != null) {
            String beside = readBeside(where, resource);
            if (beside != null) {
                int named = classesNamed(where, simple);
                if (named > 1) {
                    throw new JoustException(name + ": its code source " + where + " holds " + named + " validators named " + simple
                            + ", which share one script resource (" + resource + "): the script cannot be told apart; give the"
                            + " validators different simple names (JuLC keys scripts by simple name)");
                }
                return beside;
            }
        }
        List<URL> copies;
        try {
            copies = Collections.list(loader.getResources(resource));
        } catch (IOException e) {
            throw new JoustException(name + ": cannot list " + resource, e);
        }
        if (copies.isEmpty()) throw new JoustException(name + ": no compiled script " + resource + " on the class path");
        if (copies.size() > 1) {
            throw new JoustException(name + ": " + copies.size() + " copies of " + resource + " on the class path and none beside the"
                    + " class (" + where + "): validators with the same simple name share one script resource, so the runtime refuses"
                    + " to guess; copies: " + copies);
        }
        try (InputStream in = copies.get(0).openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JoustException(name + ": cannot read " + copies.get(0), e);
        }
    }

    private static String cborOf(String json, String name) {
        var m = CBOR.matcher(json);
        if (!m.find()) throw new JoustException(name + ": its script resource has no cborHex");
        return m.group(1);
    }

    /** The resource from a classes directory or a jar, or null when it is not there (or the location is not a file). */
    private static String readBeside(URL where, String resource) {
        try {
            if (!"file".equals(where.getProtocol())) return null;
            Path p = Path.of(where.toURI());
            if (Files.isDirectory(p)) {
                Path f = p.resolve(resource);
                return Files.isRegularFile(f) ? Files.readString(f) : null;
            }
            if (Files.isRegularFile(p)) {
                try (var jar = new JarFile(p.toFile())) {
                    var e = jar.getJarEntry(resource);
                    if (e == null) return null;
                    try (var in = jar.getInputStream(e)) {
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
            return null;
        } catch (IOException | URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    /** How many top-level classes named {@code simple} (in any package) the code source holds. */
    private static int classesNamed(URL where, String simple) {
        String file = simple + ".class";
        try {
            Path p = Path.of(where.toURI());
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.walk(p)) {
                    return (int) s.filter(f -> f.getFileName().toString().equals(file)).count();
                }
            }
            try (var jar = new JarFile(p.toFile())) {
                var names = new ArrayList<String>();
                jar.stream().forEach(e -> {
                    String n = e.getName();
                    if (n.equals(file) || n.endsWith("/" + file)) names.add(n);
                });
                return names.size();
            }
        } catch (IOException | URISyntaxException | IllegalArgumentException e) {
            return 1;
        }
    }
}
