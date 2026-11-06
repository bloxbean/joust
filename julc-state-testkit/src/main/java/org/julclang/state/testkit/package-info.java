/**
 * Test kit for JOUST contracts (ADR-0001 D15, D16). {@link org.julclang.state.testkit.StateHarness} evaluates the
 * compiled generated validator, for every purpose a transaction triggers (MINT and/or SPEND), against transactions
 * shaped like the ones the runtime builds. {@link org.julclang.state.testkit.Attacks} mutates an accepted transaction
 * in every way the invariants forbid and checks each mutation is rejected.
 */
package org.julclang.state.testkit;
