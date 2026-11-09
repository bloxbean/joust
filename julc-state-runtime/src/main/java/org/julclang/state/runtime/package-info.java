/**
 * Off-chain runtime shared by every generated {@code <Name>Client} (ADR-0001 D14): locate an instance's state UTxO,
 * run the contract method on the JVM, build the transaction (QuickTx), check it, submit it, wait for it, and retry on
 * contention without ever executing a call twice.
 */
package org.julclang.state.runtime;
