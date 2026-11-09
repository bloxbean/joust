// One place for the site's identity: its URL (canonical links, llms.txt), repository and summary.
export const SITE = {
  url: process.env.JOUST_SITE_URL ?? 'https://joust.julc.dev',
  repo: 'https://github.com/bloxbean/joust',
  name: 'JOUST',
  tagline: 'State contracts for Cardano',
  description:
    'JOUST: write Cardano smart contracts in Java as state plus actions. The same method runs on chain and off; JOUST generates the validator and a typed client on cardano-client-lib. Also .jsc, and JavaScript (experimental).',
};
