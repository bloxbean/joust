// One place for the site's identity: its URL (canonical links, llms.txt), repository and summary.
export const SITE = {
  url: process.env.JOUST_SITE_URL ?? 'https://joust.julc.dev',
  repo: 'https://github.com/bloxbean/joust',
  name: 'JOUST',
  tagline: 'State contracts for Cardano',
  description:
    'JOUST: write Cardano smart contracts as state plus actions, in Java, .jsc or from JavaScript. The same method runs on chain and off; JOUST generates the validator, a typed client and the checks between them.',
};
