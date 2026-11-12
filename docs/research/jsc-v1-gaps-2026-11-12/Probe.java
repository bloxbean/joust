import java.nio.file.*;
import org.julclang.state.dsl.*;
public class Probe {
  public static void main(String[] a) throws Exception {
    try (var s = Files.list(Path.of(a[0]))) {
      for (Path p : s.sorted().toList()) {
        String name = p.getFileName().toString();
        try {
          var r = JscMain.lowerWithWarnings(Files.readString(p), name);
          Files.writeString(Path.of(a[1], name.replace(".jsc", ".java")), r.java());
          System.out.println("OK    " + name + (r.warnings().isEmpty() ? "" : "  warnings=" + r.warnings()));
        } catch (JscException e) {
          System.out.println("ERROR " + e.format(name));
        }
      }
    }
  }
}
