# Writing the JOUST docs

The site is Astro + Starlight. Pages are in `src/content/docs/<slug>.mdx`; the sidebar (and so every slug) is in
`sidebar.mjs`. The landing page is `src/pages/index.astro`. `npm run dev` serves it; `bash scripts/check-site.sh`
builds a private copy and checks every internal link (run it before you finish).

## Who reads this

Two readers, often the same person on different days:
- **Beginners**: a Java or JavaScript developer new to Cardano, or new to JOUST. They need the why, a working first
  run, and the next step. Explain each Cardano term once, where it is first needed (UTxO, datum, redeemer, minting
  policy, reference script, CIP-30), and link to the glossary.
- **Advanced users**: building real apps. They need precise rules, limits, exact signatures and the reason behind
  each design choice. Link to the ADR (`docs/adr/` in the repo) for the full argument.

## Voice

- Plain, direct, confident. Short sentences. Active voice. Second person ("you write a state record").
- British spelling (behaviour, optimise, licence), as in the rest of the repository.
- No marketing filler ("revolutionary", "seamless", "simply"), no emojis, no exclamation marks.
- Say what something is for before how it works. Lead with the code when the code says it best.
- Every claim must be true of the code today. When something is planned or experimental, say so in an `Aside`.

## Accuracy rules (the most important part)

- **Never invent an API.** Every class, method, annotation, element, option, CLI flag, file name and default you
  mention must exist in the repository. Open the source and check it. Prefer code taken from `examples/` (real,
  tested contracts) over code you write; when you write code, make it consistent with the examples' style and
  with the APIs as they are.
- Contract code is the JuLC subset of Java: `BigInteger` (never `int`/`long`), records and sealed interfaces, no
  generic records, immutable locals (loop accumulators aside), `Require.that(cond, "msg")`, `byte[]` compared with
  `ByteStringLib.equals` (never `==` or `.equals`). See `CLAUDE.md` (Gotchas) and `julc-state-api`.
- Cite the source when it helps an advanced reader: `julc-state-api/src/main/java/org/julclang/state/api/Ctx.java`,
  `examples/token/...`, ADR-0002 §D9. Link to GitHub: `https://github.com/bloxbean/joust/blob/main/<path>`.
- Numbers (script sizes, budgets, fees) only from the repository (examples/README.md tables, ADRs), with the
  context they were measured in.

## Facts every page can rely on

- **Status:** pre-release. Maven snapshots are published to the BloxBean snapshot repository
  (`https://repo.bloxbean.org/maven/snapshots`, version `0.1.0-pre1-<sha7>-SNAPSHOT`). The npm packages
  (`@julclang/joust`, `@julclang/joust-runtime`, `@julclang/state-client`, `create-joust`) are **not on npmjs.org
  yet**: until they are, install them from a local registry (see below). Say this in an `Aside type="caution"` on any
  page that tells the reader to install something.
- **Java path:** JDK 25 and Gradle. JOUST needs a JuLC build that is not published yet: build it into mavenLocal
  with `.github/ci/build-julc.sh` from a JOUST checkout (README "Development snapshots"). Dependencies: see the
  README and any `examples/<name>/build.gradle`.
- **npm path:** Node 22 or newer, no JDK, no GraalVM. Until the packages are published: in a JOUST checkout,
  `bash js/publish-local.sh` publishes them to a local registry at `http://127.0.0.1:4873/` (it needs the two Wasm
  engines built first: `./gradlew npmPublishLocal -PgraalvmHome=...` does both). Then, with
  `npm_config_registry=http://127.0.0.1:4873/`, `npm create joust@0.1.0-pre1-snapshot my-app`.
- **Yaci DevKit** is the local network: `yaci-devkit up --enable-yaci-store` (Yaci Store's Blockfrost-compatible API
  on `:8080`, admin API on `:10000`, Yaci Viewer explorer on `:5173`).
- **Preview** (public testnet) through Blockfrost: `BLOCKFROST_PREVIEW_PROJECT_ID` (never in a page; `joust dev` and
  `joust serve` proxy it at `/chain`) and `JOUST_MNEMONIC` (the deployer key, for `joust deploy`).
- **Names:** JOUST (Java On UPLC STate, "jowst"); JuLC (the Java to UPLC compiler JOUST builds on, julc.dev);
  cardano-client-lib (CCL); Yaci DevKit; Yaci Store. Artifacts `org.julclang:julc-state-{api,processor,runtime,testkit}`;
  packages `org.julclang.state.*`.

## Page shape

```mdx
---
title: Token balances in a MerkleMap
description: One sentence, under 160 characters: what the reader gets from this page. (Used in llms.txt too.)
---

import { Tabs, TabItem, Aside, Steps, Card, CardGrid, LinkCard, FileTree, Badge } from '@astrojs/starlight/components';

Opening paragraph: what this is and when you need it.

## Sections...

## Next steps
<LinkCard title="..." href="/contracts/merklemaps/" />
```

- Import only the components you use.
- **Languages side by side:** where Java, `.jsc` and JavaScript all apply, use
  `<Tabs syncKey="lang">` with `<TabItem label="Java">`, `<TabItem label=".jsc">`, `<TabItem label="JavaScript">`
  (the same labels everywhere, so a reader's choice follows them across pages). Use ```` ```java ````,
  ```` ```jsc ````, ```` ```js ````, ```` ```ts ````, ```` ```sh ````, ```` ```json ```` fences. Give a fence a
  `title="examples/token/.../Token.java"` when it shows a real file.
- **Tutorials** use `<Steps>` for the ordered parts, end with a working result the reader can see, and say what each
  step taught. Build on a real example in `examples/`.
- **Guides** explain one feature fully: what, when, how (with code), the rules and limits, what is generated and
  checked on chain, pitfalls, and links to the reference and the ADR.
- **Reference** pages are exhaustive and scannable: tables of every element, option and default, from the source.
- Tables for comparisons and options; `Aside` (note, tip, caution, danger) for anything a reader must not miss.

## MDX pitfalls

- In prose, `{`, `}` and `<` start JSX: put them in code spans (`` `Map<Key, Int>` ``) or escape them (`\{`).
- Leave a blank line around components and inside them before markdown content.
- Internal links are absolute and end with a slash: `[MerkleMaps](/contracts/merklemaps/)`. Only link to slugs in
  `sidebar.mjs` (plus `/ai/`, `/llms.txt`, `/llms-full.txt`, `/ai/catalog.json`). Anchors: `#section-slug`.
