package org.julclang.state.processor.ir;

/**
 * A method parameter or record component.
 *
 * @param name the Java name
 * @param type source text of its type, as the generated code writes it
 * @param kind coarse category, used to pick encodings and equality in generated code
 */
public record ParamIR(String name, String type, TypeKind kind) {

    public enum TypeKind { INTEGER, BYTES, BOOLEAN, STRING, MERKLE_MAP, OTHER }
}
