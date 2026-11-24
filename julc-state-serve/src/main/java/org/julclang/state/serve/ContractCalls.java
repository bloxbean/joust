package org.julclang.state.serve;

import com.bloxbean.cardano.client.account.Account;
import com.fasterxml.jackson.databind.JsonNode;
import org.julclang.state.runtime.StateContractHandle;
import org.julclang.state.runtime.StateRuntime;
import java.math.BigInteger;

/** Protocol-1 entry points common to generated clients and engine transition programs. */
public interface ContractCalls {
    StateContractHandle<?> handle(StateRuntime runtime);
    JsonNode viewJson(StateRuntime runtime, String instance, String view, JsonNode args);
    Object call(StateRuntime runtime, Account caller, String kind, JsonNode meta, String instance, String object,
                JsonNode args, BigInteger lovelace, JsonNode assets, Account[] signers);
}
