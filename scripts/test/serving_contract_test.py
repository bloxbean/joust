"""Repository-level checks for the public serving API, its documentation and its published dependency."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "www/src/content/docs"


class ServingContractTest(unittest.TestCase):
    def test_ttl_accessor_units(self):
        page = (DOCS / "reference/java-runtime-api.mdx").read_text()
        row = next(line for line in page.splitlines() if line.startswith("| `ttlMs()`"))
        self.assertRegex(row, r"milliseconds|\bms\b")
        self.assertIn("callScope().ttlSlots()", row)

    def test_claim_store_documents_how_plain_builds_exclude_reservations(self):
        source = (ROOT / "julc-state-runtime/src/main/java/org/julclang/state/runtime/ClaimStore.java").read_text()
        avoids_doc = source[:source.index("default boolean avoids(")].rsplit("/**", 1)[1].lower()
        for term in ("reservation", "plain", "keyed", "claims"):
            self.assertIn(term, avoids_doc)

    def test_published_libraries_are_in_both_module_lists(self):
        build = (ROOT / "build.gradle").read_text()
        published = re.search(r"def publishedModules = \[(.*?)\]", build, re.S)[1]
        modules = set(re.findall(r"'([^']+)'", published))
        overview = (DOCS / "start/how-it-works.mdx").read_text()
        table = set(re.findall(r"^\| `(julc-state-[\w-]+)` \|", overview, re.M))
        self.assertFalse((modules - {"julc-state-gradle-plugin"}) - table)
        roadmap = (DOCS / "project/status-and-roadmap.mdx").read_text()
        paragraph = roadmap.split("The published Maven modules are", 1)[1].split("\n\n", 1)[0]
        self.assertFalse(modules - set(re.findall(r"`(julc-state-[\w-]+)`", paragraph)))

    def test_serve_exports_jackson_databind(self):
        build = (ROOT / "julc-state-serve/build.gradle").read_text()
        catalog = (ROOT / "gradle/libs.versions.toml").read_text()
        alias = re.search(r'^([\w-]+)\s*=\s*\{\s*module\s*=\s*"com.fasterxml.jackson.core:jackson-databind"', catalog, re.M)
        self.assertIsNotNone(alias, "Jackson Databind must be declared in the version catalog")
        accessor = re.escape(alias[1].replace("-", "."))
        self.assertRegex(build, rf"(?m)^\s*api\s+libs\.{accessor}\s*$")


if __name__ == "__main__":
    unittest.main()
