package org.julclang.state.api;

import org.julclang.state.api.Send;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.Address;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.Value;
import org.julclang.stdlib.annotation.OnchainLibrary;

/** Builders for the send list a method returns (ADR-0007 D1). */
@OnchainLibrary
public class Sends {

    public static JulcList<Send> none() {
        return JulcList.empty();
    }

    public static JulcList<Send> of(Address to, Value value, OutputDatum datum) {
        JulcList<Send> empty = JulcList.empty();
        return empty.prepend(new Send(to, value, datum));
    }

    public static JulcList<Send> add(JulcList<Send> sends, Address to, Value value, OutputDatum datum) {
        return sends.prepend(new Send(to, value, datum));
    }
}
