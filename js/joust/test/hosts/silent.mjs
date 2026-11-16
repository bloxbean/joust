// A test engine host that never reports (#80 review C10): the parent's startup timer must end it.
setInterval(() => {}, 1000);
