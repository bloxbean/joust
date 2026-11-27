// The docs' sidebar: the site's order, which the AI artifacts (llms.txt, llms-full.txt) follow too.
// Java (with cardano-client-lib) is the first-class path; the npm path (GraalVM Web Image) is experimental.
// Experimental is said once per page (an Aside at its top) and in the JavaScript groups' labels, not as item badges.
export const SIDEBAR = [
  {
    label: 'Start here',
    items: [
      { label: 'Why JOUST', slug: 'start/why-joust' },
      { label: 'How JOUST works', slug: 'start/how-it-works' },
      { label: 'Install JOUST', slug: 'start/install' },
      { label: 'Quick start: Java & Gradle', slug: 'start/quick-start-java' },
      { label: 'Your first contract', slug: 'start/first-contract' },
      { label: 'Quick start: JavaScript & TypeScript', slug: 'start/quick-start-npm' },
    ],
  },
  {
    label: 'Tutorials: Java',
    items: [
      { label: 'Vault: deposits, time and payouts', slug: 'tutorials/vault' },
      { label: 'Escrow and crowdfunding', slug: 'tutorials/escrow-crowdfund' },
      { label: 'Token balances in a MerkleMap', slug: 'tutorials/token-merklemap' },
      { label: 'Bank: owned objects', slug: 'tutorials/bank-owned-objects' },
      { label: 'Pool: batched intents', slug: 'tutorials/pool-batched-intents' },
      { label: 'DEX: native assets and LP tokens', slug: 'tutorials/dex-assets' },
      { label: 'Multisig treasury', slug: 'tutorials/multisig-treasury' },
      { label: 'Oracle and composition', slug: 'tutorials/oracle-composition' },
      { label: 'Staked vault and governance', slug: 'tutorials/staked-vault' },
      { label: 'Upgrading a contract', slug: 'tutorials/upgrades' },
    ],
  },
  {
    label: 'Tutorials: JavaScript (experimental)',
    items: [
      { label: 'A counter dApp in the browser', slug: 'tutorials/counter-dapp' },
      { label: 'Deploy to preview', slug: 'tutorials/deploy-preview' },
    ],
  },
  {
    label: 'Writing contracts',
    items: [
      { label: 'State and lifecycle', slug: 'contracts/state-and-lifecycle' },
      { label: 'Ctx, funds and payouts', slug: 'contracts/ctx-and-funds' },
      { label: 'Authorization', slug: 'contracts/authorization' },
      { label: 'Time', slug: 'contracts/time' },
      { label: 'Require and errors', slug: 'contracts/require-and-errors' },
      { label: 'Params, invariants and events', slug: 'contracts/params-invariants-events' },
      { label: 'MerkleMaps', slug: 'contracts/merklemaps' },
      { label: 'Owned objects', slug: 'contracts/owned-objects' },
      { label: 'Keyed objects', slug: 'contracts/keyed-objects' },
      { label: 'Batched intents', slug: 'contracts/batched-intents' },
      { label: 'Native assets, mints and sends', slug: 'contracts/assets-mints-sends' },
      { label: 'Reads and composition', slug: 'contracts/reads-and-composition' },
      { label: 'Deposits', slug: 'contracts/deposits' },
      { label: 'Staking and governance', slug: 'contracts/staking-and-governance' },
      { label: 'Upgrades and migration', slug: 'contracts/upgrades' },
      { label: 'Fees and script size', slug: 'contracts/fees-and-size' },
      { label: 'Security model', slug: 'contracts/security-model' },
      { label: 'Testing', slug: 'contracts/testing' },
    ],
  },
  {
    label: 'Building apps: Java',
    items: [
      { label: 'Gradle builds', slug: 'apps/gradle-builds' },
      { label: 'The Java off-chain runtime', slug: 'apps/java-runtime' },
    ],
  },
  {
    label: 'Tools',
    items: [
      { label: 'The playground', slug: 'apps/playground' },
      { label: 'The dev host', slug: 'apps/dev-host' },
    ],
  },
  {
    label: 'Building apps: JavaScript (experimental)',
    items: [
      { label: 'JavaScript clients', slug: 'apps/javascript-clients' },
      { label: 'Wallets', slug: 'apps/wallets' },
      { label: 'Pages: worker, Vite, static builds', slug: 'apps/pages' },
      { label: 'The joust CLI and projects', slug: 'apps/cli-and-projects' },
      { label: 'Networks and chain providers', slug: 'apps/networks-and-providers' },
      { label: 'Keyed objects in JavaScript', slug: 'apps/keyed-objects-js' },
      { label: 'MerkleMap mirrors', slug: 'apps/map-mirrors' },
    ],
  },
  {
    label: 'Languages',
    items: [
      { label: 'Java', slug: 'languages/java' },
      { label: '.jsc', slug: 'languages/jsc', badge: { text: 'Preview', variant: 'caution' } },
      { label: '.jsc reference', slug: 'languages/jsc-reference' },
      { label: '.jsc standard library', slug: 'languages/jsc-std' },
      { label: 'TypeScript', slug: 'languages/typescript' },
      { label: 'Editors', slug: 'languages/editors' },
    ],
  },
  {
    label: 'Reference: Java',
    items: [
      { label: 'Annotations', slug: 'reference/annotations' },
      { label: 'State API (julc-state-api)', slug: 'reference/state-api' },
      { label: 'Java runtime API', slug: 'reference/java-runtime-api' },
    ],
  },
  {
    label: 'Reference: JavaScript (experimental)',
    items: [
      { label: '@julclang/state-client', slug: 'reference/state-client' },
      { label: '@julclang/joust-runtime', slug: 'reference/joust-runtime' },
      { label: '@julclang/joust (CLI, Vite)', slug: 'reference/joust-cli' },
      { label: 'joust.config.json', slug: 'reference/config' },
    ],
  },
  {
    label: 'Reference',
    items: [
      { label: 'Contract description', slug: 'reference/description-format' },
      { label: 'Examples', slug: 'reference/examples' },
      { label: 'Troubleshooting', slug: 'reference/troubleshooting' },
      { label: 'Glossary', slug: 'reference/glossary' },
    ],
  },
  {
    label: 'AI',
    items: [
{ label: 'Using JOUST with AI', slug: 'ai' },
{ label: 'AI starter pack', slug: 'ai/starter-pack' },
{ label: 'llms.txt', link: '/llms.txt', attrs: { target: '_blank' } },
    ],
  },
  {
    label: 'Project',
    items: [
{ label: 'Status and roadmap', slug: 'project/status-and-roadmap' },
{ label: 'Architecture decisions', slug: 'project/architecture-decisions' },
    ],
  },
];
