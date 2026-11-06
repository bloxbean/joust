/**
 * The JOUST annotation processor (ADR-0001): reads {@code @StateContract} classes ({@link
 * org.julclang.state.processor.ContractReader}), validates them, builds the State IR ({@code ir}) and generates the
 * JuLC validator and the typed off-chain client from it ({@code gen}).
 */
package org.julclang.state.processor;
