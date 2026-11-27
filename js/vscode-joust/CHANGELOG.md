# Changelog

The extension has its own version, `major.minor.patch` (the Visual Studio Marketplace accepts no other form). Each
version bundles the `.jsc` compiler of one JOUST release, named in its entry. A project that uses its own
`@julclang/joust` gets that version's compiler instead (see the README's "Which compiler runs").

## 0.1.1

- Bundles the compiler of **JOUST 0.1.0-pre3**.
- The first version on the Visual Studio Marketplace (`julc.vscode-joust`), a pre-release. 0.1.0's publication there
  was stopped by a Marketplace rate limit; 0.1.0 is attached to the JOUST 0.1.0-pre2 release on GitHub only.

## 0.1.0

- Bundles the compiler of **JOUST 0.1.0-pre2**.
- The first version, attached to the JOUST 0.1.0-pre2 release on GitHub, a pre-release: diagnostics as you type,
  hover, go to definition, references, completion, quick fixes, rename, formatting, semantic highlighting, the outline,
  the generated Java, the build on save with the ABI and `joust.lock` checks, Accept ABI… and JOUST: Build
  (ADR-0021 M5a–M5e).
