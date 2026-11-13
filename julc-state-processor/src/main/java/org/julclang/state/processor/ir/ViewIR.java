package org.julclang.state.processor.ir;

import java.util.List;

/**
 * A {@code @View} method.
 *
 * @param method     the Java method name
 * @param params     arguments after the state parameter
 * @param returnType source text of the return type
 * @param takesParams whether the view takes the {@code @Params} record right after the state (ADR-0009 D1): the client
 *                    passes the deployment's own params, never a caller's (#84 review C3)
 */
public record ViewIR(String method, List<ParamIR> params, String returnType, boolean takesParams) {
    public ViewIR(String method, List<ParamIR> params, String returnType) {
        this(method, params, returnType, false);
    }
}
