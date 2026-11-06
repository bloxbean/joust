package org.julclang.state.processor.ir;

import java.util.List;

/**
 * A {@code @View} method.
 *
 * @param method     the Java method name
 * @param params     arguments after the state parameter
 * @param returnType source text of the return type
 */
public record ViewIR(String method, List<ParamIR> params, String returnType) {}
