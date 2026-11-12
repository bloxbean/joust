package org.julclang.state.runtime;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.clientlib.ValidatorOutput;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The compiled script of a generated validator, read from beside the validator class (ADR-0018 M1c reviews S1/F1, C5,
 * S7, F11, F12).
 * <p>
 * JuLC writes a validator's script to {@code META-INF/plutus/<SimpleName>.plutus.json}, and its loader
 * ({@code JulcScriptLoader}) reads that name through the class loader. Two validators with the same simple name in
 * different packages ({@code …vault.VaultValidator}, {@code …dsl.VaultValidator}) therefore share one resource name, and
 * on one class path the first copy found would serve both: the runtime would deploy and evaluate the other contract.
 * The rules here keep the runtime fail-closed until JuLC keys scripts by the qualified name (bloxbean/julc#326):
 * <ul>
 *   <li><b>The validator's own location</b> (its classes directory, its jar, or a {@code jar:} URL into one), when it can
 *   be read, is the only place the script is taken from. It must hold the script ({@code META-INF/plutus/…}) and no
 *   second class of that simple name in another package (a shaded jar or a merged directory, where one script
 *   overwrote the other). Otherwise the load is refused; a copy from elsewhere is never taken.</li>
 *   <li><b>Only when that location cannot be read</b> (no code source, a {@code jrt:} module, a nested jar the JDK cannot
 *   open) are the copies on the class path considered. A copy whose own location can be read counts only if that location
 *   holds this validator's class ({@code pkg/Name.class}) and no other class of that simple name; one such copy is
 *   used. A single copy whose location cannot be read is used too: <b>this is the remaining fallback</b>. If the
 *   validator's own copy is missing there and another validator's single copy sits somewhere unreadable, that copy is
 *   loaded; only a script hash recorded in bytecode (julc#326) closes it. Anything else, several candidates included,
 *   is refused.</li>
 *   <li><b>JuLC's parameter checks</b> are kept (as {@code JulcScriptLoader.load}): a parameterized validator is never
 *   loaded without its parameters, an unparameterized one never with some, and the count must match. An unapplied
 *   script's address would accept funds no transaction can spend.</li>
 * </ul>
 * A flat or shaded jar whose merge kept a resource-only plant in place of the validator's own script still passes the
 * first rule (one class of that name, one script beside it); builds must not drop duplicate {@code META-INF/plutus/*}
 * entries silently (CLAUDE.md; {@code checkPlutusResources}).
 */
public final class ValidatorScripts {
    private ValidatorScripts() {}

    /** Parsed once per validator class, without pinning its class loader (review F12). */
    private static final ClassValue<ValidatorOutput> OUTPUTS = new ClassValue<>() {
        @Override
        protected ValidatorOutput computeValue(Class<?> validator) {
            return ValidatorOutput.fromJson(find(validator));
        }
    };

    /** The resource JuLC writes the script of {@code validator} to. */
    public static String resource(Class<?> validator) {
        return "META-INF/plutus/" + validator.getSimpleName() + ".plutus.json";
    }

    /** The validator's compiled output (script, hash, parameters), read beside its class. */
    public static ValidatorOutput output(Class<?> validator) {
        return OUTPUTS.get(validator);
    }

    /** The script of {@code validator}, which takes no parameters (refused, as JuLC refuses, if it does). */
    public static PlutusV3Script load(Class<?> validator) {
        var out = output(validator);
        if (out.isParameterized()) {
            throw new IllegalArgumentException(validator.getSimpleName() + " is parameterized and requires " + out.paramList().size()
                    + " param(s): its unapplied script is not the contract (its address would lock funds); give its parameters");
        }
        return PlutusV3Script.builder().cborHex(out.cborHex()).build();
    }

    /** The script of {@code validator} with its deploy-time {@code params} applied, with JuLC's checks on their number. */
    public static PlutusV3Script load(Class<?> validator, PlutusData... params) {
        if (params.length == 0) return load(validator);
        var out = output(validator);
        if (!out.isParameterized()) {
            throw new IllegalArgumentException(validator.getSimpleName() + " is not parameterized, but " + params.length + " param(s) were provided");
        }
        if (out.paramList().size() != params.length) {
            throw new IllegalArgumentException(validator.getSimpleName() + " requires " + out.paramList().size() + " param(s), but "
                    + params.length + " were provided");
        }
        var data = new org.julclang.core.PlutusData[params.length];
        for (int i = 0; i < params.length; i++) data[i] = PlutusDataAdapter.fromClientLib(params[i]);
        return JulcScriptAdapter.fromProgram(JulcScriptAdapter.toProgram(out.cborHex()).applyParams(data));
    }

    /** The {@code cborHex} of the script beside {@code validator} (unapplied). */
    public static String cborHex(Class<?> validator) {
        return output(validator).cborHex();
    }

    private static String find(Class<?> validator) {
        URL where = null;
        var pd = validator.getProtectionDomain();
        if (pd != null && pd.getCodeSource() != null) where = pd.getCodeSource().getLocation();
        ClassLoader loader = validator.getClassLoader() != null ? validator.getClassLoader() : ClassLoader.getSystemClassLoader();
        return resolve(validator.getName(), validator.getSimpleName(), where, loader);
    }

    /**
     * The script JSON of the validator {@code name} (simple name {@code simple}) whose class comes from {@code where},
     * by the rules above; refuses with a {@link JoustException}.
     */
    static String resolve(String name, String simple, URL where, ClassLoader loader) {
        String resource = "META-INF/plutus/" + simple + ".plutus.json";
        String classFile = name.replace('.', '/') + ".class";
        try (Container own = Container.open(where)) {
            if (own != null) {
                int named = own.classesNamed(simple);
                if (named > 1) {
                    throw new JoustException(name + ": its code source " + where + " holds " + named + " classes named " + simple
                            + ", which share one script resource (" + resource + "): the script cannot be told apart; give the"
                            + " validators different simple names (JuLC keys scripts by simple name, bloxbean/julc#326)");
                }
                String beside = own.read(resource);
                if (beside == null) {
                    throw new JoustException(name + ": its code source " + where + " holds no " + resource + " (a partial build, or"
                            + " resources written elsewhere); refusing to take a copy from elsewhere on the class path, which may"
                            + " be another validator's of the same simple name");
                }
                return beside;
            }
        }
        // The validator's own location cannot be read: consider the copies on the class path.
        List<URL> copies;
        try {
            copies = Collections.list(loader.getResources(resource));
        } catch (IOException e) {
            throw new JoustException(name + ": cannot list " + resource, e);
        }
        if (copies.isEmpty()) throw new JoustException(name + ": no compiled script " + resource + " on the class path");
        var ours = new ArrayList<URL>();
        int unreadable = 0;
        for (URL copy : copies) {
            String s = copy.toString();
            URL base;
            try {
                base = s.endsWith(resource) ? new URL(s.substring(0, s.length() - resource.length())) : null;
            } catch (IOException e) {
                base = null;
            }
            try (Container c = Container.open(base)) {
                if (c == null) unreadable++;
                else if (c.has(classFile) && c.classesNamed(simple) == 1) ours.add(copy);
            }
        }
        URL chosen = ours.size() == 1 && unreadable == 0 ? ours.get(0)
                : ours.isEmpty() && copies.size() == 1 && unreadable == 1 ? copies.get(0)   // the documented fallback
                : null;
        if (chosen == null) {
            throw new JoustException(name + ": its code source (" + where + ") cannot be read, and the class path's copies of "
                    + resource + " cannot be told apart (" + copies.size() + " copies, " + ours.size() + " beside this class, "
                    + unreadable + " unreadable): validators with the same simple name share one script resource, so the runtime"
                    + " refuses to guess; copies: " + copies);
        }
        try (InputStream in = chosen.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JoustException(name + ": cannot read " + chosen, e);
        }
    }

    /** A classes directory or a jar (or a directory inside one), read by entry name. */
    private interface Container extends AutoCloseable {
        /** The entry's text, or null when there is none. */
        String read(String entry);

        boolean has(String entry);

        /** How many classes named {@code simple} (top-level, in any package) it holds. */
        int classesNamed(String simple);

        @Override
        void close();

        /** The container at {@code where}, or null when it cannot be read (null, jrt:, a nested jar, missing). */
        static Container open(URL where) {
            if (where == null) return null;
            try {
                if ("file".equals(where.getProtocol())) {
                    Path p = Path.of(where.toURI());
                    if (Files.isDirectory(p)) return new Dir(p);
                    if (Files.isRegularFile(p)) return new Jar(new JarFile(p.toFile()), "");
                    return null;
                }
                if ("jar".equals(where.getProtocol())) {
                    String spec = where.toString();
                    // A nested jar (jar:file:app.jar!/lib/x.jar!/): the JDK would open the outer jar, the wrong container.
                    if (spec.indexOf("!/") != spec.lastIndexOf("!/") || !spec.startsWith("jar:file:")) return null;
                    var conn = (JarURLConnection) where.openConnection();
                    conn.setUseCaches(false);
                    String prefix = conn.getEntryName() == null ? "" : conn.getEntryName();
                    if (!prefix.isEmpty() && !prefix.endsWith("/")) return null;
                    return new Jar(conn.getJarFile(), prefix);
                }
                return null;
            } catch (IOException | URISyntaxException | IllegalArgumentException | ClassCastException e) {
                return null;
            }
        }
    }

    private record Dir(Path root) implements Container {
        @Override
        public String read(String entry) {
            Path f = root.resolve(entry);
            try {
                return Files.isRegularFile(f) ? Files.readString(f) : null;
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public boolean has(String entry) {
            return Files.isRegularFile(root.resolve(entry));
        }

        @Override
        public int classesNamed(String simple) {
            String file = simple + ".class";
            try (Stream<Path> s = Files.walk(root)) {
                return (int) s.filter(f -> f.getFileName().toString().equals(file)).count();
            } catch (IOException e) {
                return Integer.MAX_VALUE;   // unknown: never taken for one
            }
        }

        @Override
        public void close() {
        }
    }

    private record Jar(JarFile jar, String prefix) implements Container {
        @Override
        public String read(String entry) {
            var e = jar.getJarEntry(prefix + entry);
            if (e == null) return null;
            try (var in = jar.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException x) {
                return null;
            }
        }

        @Override
        public boolean has(String entry) {
            return jar.getJarEntry(prefix + entry) != null;
        }

        @Override
        public int classesNamed(String simple) {
            String file = simple + ".class";
            return (int) jar.stream().map(e -> e.getName()).filter(n -> n.startsWith(prefix))
                    .filter(n -> n.equals(prefix + file) || n.endsWith("/" + file)).count();
        }

        @Override
        public void close() {
            try {
                jar.close();
            } catch (IOException e) {
                // nothing to do
            }
        }
    }
}
