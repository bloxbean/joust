package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** WalletAccount fails closed (ADR-0013 r2): every public instance method of CCL's Account is overridden. */
class WalletAccountTest {

    @Test
    void overridesEveryPublicAccountMethod() {
        var missing = new ArrayList<String>();
        for (Method m : Account.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
            try {
                WalletAccount.class.getDeclaredMethod(m.getName(), m.getParameterTypes());
            } catch (NoSuchMethodException e) {
                missing.add(m.toString());
            }
        }
        assertTrue(missing.isEmpty(), "WalletAccount must override (answer from the address or throw): " + missing);
    }

    @Test
    void answersFromTheAddress_andHasNoKeys() {
        var keys = new Account(Networks.testnet());
        var w = WalletAccount.of(keys.baseAddress(), Networks.testnet());
        assertEquals(keys.baseAddress(), w.baseAddress());
        assertEquals(keys.enterpriseAddress(), w.enterpriseAddress());
        assertArrayEquals(Keys.pkh(keys), Keys.pkh(w));
        assertThrows(UnsupportedOperationException.class, w::hdKeyPair);
        assertThrows(UnsupportedOperationException.class, w::privateKeyBytes);
        assertThrows(UnsupportedOperationException.class, w::mnemonic);
        assertThrows(UnsupportedOperationException.class, () -> w.sign(new com.bloxbean.cardano.client.transaction.spec.Transaction()));
        // the enterprise form is accepted too; mainnet, script and garbage are refused
        assertEquals(keys.enterpriseAddress(), WalletAccount.of(keys.enterpriseAddress(), Networks.testnet()).baseAddress());
        var mainnet = new Account(Networks.mainnet());
        assertThrows(JoustException.class, () -> WalletAccount.of(mainnet.baseAddress(), Networks.testnet()));
        String script = com.bloxbean.cardano.client.address.AddressProvider.getEntAddress(
                com.bloxbean.cardano.client.address.Credential.fromScript(new byte[28]), Networks.testnet()).toBech32();
        var e = assertThrows(JoustException.class, () -> WalletAccount.of(script, Networks.testnet()));
        assertTrue(e.getMessage().contains("script"), e.getMessage());
        assertThrows(JoustException.class, () -> WalletAccount.of("not an address", Networks.testnet()));
    }

    @Test
    void afterConstruction_onlyAddressMethodsAnswer_everyOtherThrows() throws Exception {
        var keys = new Account(Networks.testnet());
        var w = WalletAccount.of(keys.baseAddress(), Networks.testnet());
        var answer = java.util.Set.of("baseAddress", "baseAddressAsBase16", "changeAddress", "getBaseAddress",
                "getEnterpriseAddress", "enterpriseAddress", "stakeAddress", "toString");
        for (Method m : Account.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
            Object[] args = new Object[m.getParameterCount()];
            for (int i = 0; i < args.length; i++) {
                Class<?> t = m.getParameterTypes()[i];
                args[i] = t == String.class ? "00" : t == com.bloxbean.cardano.client.transaction.spec.Transaction.class
                        ? new com.bloxbean.cardano.client.transaction.spec.Transaction() : null;
            }
            try {
                Object r = m.invoke(w, args);
                assertTrue(answer.contains(m.getName()), m.getName() + " answered " + r + " instead of throwing");
            } catch (java.lang.reflect.InvocationTargetException e) {
                assertFalse(answer.contains(m.getName()), m.getName() + " threw " + e.getCause());
                assertInstanceOf(UnsupportedOperationException.class, e.getCause(), m.getName());
            }
        }
    }
}
