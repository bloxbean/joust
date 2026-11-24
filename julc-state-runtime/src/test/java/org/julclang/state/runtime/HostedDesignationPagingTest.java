package org.julclang.state.runtime;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.bloxbean.cardano.client.common.model.Networks;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** #156 Codex review: a hosted designation is found anywhere in the complete wallet listing, not only in its first 2,000. */
class HostedDesignationPagingTest {
    @Test void aHostedDesignationPastTheFirstTwoThousandOutputsIsFound() {
        var payer = WalletAccount.of(com.bloxbean.cardano.client.address.AddressProvider.getEntAddress(
                com.bloxbean.cardano.client.address.Credential.fromKey(java.util.HexFormat.of().parseHex("11".repeat(28))),
                Networks.testnet()).toBech32(), Networks.testnet());
        var all = new ArrayList<Utxo>();
        for (int i = 0; i <= 2000; i++) {
            var u = new Utxo();
            u.setTxHash(String.format("%064x", i));
            u.setOutputIndex(0);
            u.setAddress(payer.baseAddress());
            u.setAmount(List.of(Amount.lovelace(BigInteger.valueOf(6_000_000))));
            all.add(u);
        }
        UtxoService utxos = (UtxoService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{UtxoService.class},
                (p, m, a) -> {
                    if (!m.getName().equals("getUtxos") || a.length != 3) throw new UnsupportedOperationException(m.getName());
                    int count = (Integer) a[1], page = (Integer) a[2];
                    int from = Math.min(all.size(), (page - 1) * count), to = Math.min(all.size(), from + count);
                    return Result.success("ok").withValue(new ArrayList<>(all.subList(from, to)));
                });
        BackendService backend = (BackendService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{BackendService.class},
                (p, m, a) -> {
                    if (m.getName().equals("getUtxoService")) return utxos;
                    throw new UnsupportedOperationException(m.getName());
                });
        var rt = StateRuntime.builder(backend).chainTime(new SlotChainTime(0, 0, 1000, null)).build();
        String last = String.format("%064x", 2000) + "#0";
        var d = rt.inScope(CallScope.hosted(10), () -> Designation.given(rt, payer, last));
        assertEquals(last, d.ref());
        // A provider keeps the earlier bound.
        var e = assertThrows(JoustException.class, () -> Designation.given(rt, payer, last));
        assertTrue(e.getMessage().contains("2,000"), e.getMessage());
    }
}
