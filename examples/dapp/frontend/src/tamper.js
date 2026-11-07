// What a malicious prepare service could do to a transaction before the page sees it. The page's verifier must refuse
// every variant (the dApp's "malicious service" switch and the e2e test use these).

const ATTACKER_KEY = 'ee'.repeat(28);

export function tamperings(CSL, txHex, walletAddresses) {
  const base = () => CSL.Transaction.from_hex(txHex);
  const thief = CSL.EnterpriseAddress.new(0, CSL.Credential.from_keyhash(CSL.Ed25519KeyHash.from_hex(ATTACKER_KEY))).to_address();
  const isWallet = (o) => walletAddresses.includes(o.address().to_bech32());
  const withBody = (edit) => {
    const tx = base();
    const b = CSL.TransactionBody.from_bytes(tx.body().to_bytes());
    edit(b, tx);
    const t = CSL.Transaction.new(b, tx.witness_set(), tx.auxiliary_data());
    return t.to_hex();
  };
  // CSL bodies have no set_outputs: a new body with these outputs and every other field copied.
  const withOutputs = (b, outs, fee = b.fee()) => {
    const n = CSL.TransactionBody.new_tx_body(b.inputs(), outs, fee);
    if (b.ttl_bignum()) n.set_ttl(b.ttl_bignum());
    if (b.validity_start_interval_bignum()) n.set_validity_start_interval_bignum(b.validity_start_interval_bignum());
    if (b.collateral()) n.set_collateral(b.collateral());
    if (b.collateral_return()) n.set_collateral_return(b.collateral_return());
    if (b.total_collateral()) n.set_total_collateral(b.total_collateral());
    if (b.required_signers()) n.set_required_signers(b.required_signers());
    if (b.reference_inputs()) n.set_reference_inputs(b.reference_inputs());
    if (b.script_data_hash()) n.set_script_data_hash(b.script_data_hash());
    if (b.network_id()) n.set_network_id(b.network_id());
    if (b.mint()) n.set_mint(b.mint());
    return n;
  };
  const out = {};

  out['an extra output to an attacker'] = (() => {
    const tx = base();
    const b = tx.body();
    const all = CSL.TransactionOutputs.new();
    let taken = false;
    for (let i = 0; i < b.outputs().len(); i++) {
      const o = b.outputs().get(i);
      const coin = BigInt(o.amount().coin().to_str());
      if (!taken && isWallet(o) && coin > 7_000_000n) {
        const v = o.amount();
        v.set_coin(CSL.BigNum.from_str((coin - 5_000_000n).toString()));
        const no = CSL.TransactionOutput.new(o.address(), v);
        if (o.has_plutus_data()) no.set_plutus_data(o.plutus_data());
        all.add(no);
        taken = true;
      } else all.add(o);
    }
    all.add(CSL.TransactionOutput.new(thief, CSL.Value.new(CSL.BigNum.from_str('5000000'))));
    return CSL.Transaction.new(withOutputs(b, all), tx.witness_set(), tx.auxiliary_data()).to_hex();
  })();
  out['the collateral return to an attacker'] = withBody((b) => {
    if (b.collateral_return()) b.set_collateral_return(CSL.TransactionOutput.new(thief, b.collateral_return().amount()));
    else b.set_collateral_return(CSL.TransactionOutput.new(thief, CSL.Value.new(CSL.BigNum.from_str('5000000'))));
  });
  out['another call'] = (() => {
    const tx = base();
    const ws = tx.witness_set();
    const rds = ws.redeemers();
    const r = rds.get(0);
    const c = r.data().as_constr_plutus_data();
    const other = CSL.PlutusData.new_constr_plutus_data(CSL.ConstrPlutusData.new(
      CSL.BigNum.from_str(String(Number(c ? c.alternative().to_str() : '0') + 1)), c ? c.data() : CSL.PlutusList.new()));
    const nr = CSL.Redeemers.new();
    nr.add(CSL.Redeemer.new(r.tag(), r.index(), other, r.ex_units()));
    for (let i = 1; i < rds.len(); i++) nr.add(rds.get(i));
    ws.set_redeemers(nr);
    return CSL.Transaction.new(tx.body(), ws, tx.auxiliary_data()).to_hex();
  })();
  out['a stake delegation certificate'] = withBody((b) => {
    const c = CSL.Certificates.new();
    c.add(CSL.Certificate.new_stake_registration(CSL.StakeRegistration.new(CSL.Credential.from_keyhash(CSL.Ed25519KeyHash.from_hex('aa'.repeat(28))))));
    b.set_certs(c);
  });
  out['is_valid = false'] = (() => {
    const tx = base();
    const t = CSL.Transaction.new(tx.body(), tx.witness_set(), tx.auxiliary_data());
    t.set_is_valid(false);
    return t.to_hex();
  })();
  out['an extra required signer'] = withBody((b) => {
    const rs = b.required_signers() ?? CSL.Ed25519KeyHashes.new();
    rs.add(CSL.Ed25519KeyHash.from_hex('ab'.repeat(28)));
    b.set_required_signers(rs);
  });
  out['a validity interval of days'] = withBody((b) => b.set_ttl(CSL.BigNum.from_str(String(Number(b.ttl_bignum()?.to_str() ?? '0') + 500_000))));
  out['phishing metadata'] = (() => {
    const tx = base();
    const aux = CSL.AuxiliaryData.new();
    const md = CSL.GeneralTransactionMetadata.new();
    md.insert(CSL.BigNum.from_str('674'), CSL.encode_json_str_to_metadatum('{"msg":["claim your prize at evil.example"]}', CSL.MetadataJsonSchema.BasicConversions));
    aux.set_metadata(md);
    return CSL.Transaction.new(tx.body(), tx.witness_set(), aux).to_hex();
  })();
  out['a foreign mint'] = withBody((b) => {
    const a = CSL.MintAssets.new();
    a.insert(CSL.AssetName.new(new Uint8Array([0xff])), CSL.Int.new_i32(1));
    const m = CSL.Mint.new();
    m.insert(CSL.ScriptHash.from_hex('dd'.repeat(28)), a);
    b.set_mint(m);
  });
  out['an inflated fee'] = (() => {
    const tx = base();
    const b = tx.body();
    const all = CSL.TransactionOutputs.new();
    let taken = false;
    for (let i = 0; i < b.outputs().len(); i++) {
      const o = b.outputs().get(i);
      const coin = BigInt(o.amount().coin().to_str());
      if (!taken && isWallet(o) && coin > 52_000_000n) {
        const v = o.amount();
        v.set_coin(CSL.BigNum.from_str((coin - 50_000_000n).toString()));
        all.add(CSL.TransactionOutput.new(o.address(), v));
        taken = true;
      } else all.add(o);
    }
    if (!taken) return null;
    return CSL.Transaction.new(withOutputs(b, all, CSL.BigNum.from_str((BigInt(b.fee().to_str()) + 50_000_000n).toString())), tx.witness_set(), tx.auxiliary_data()).to_hex();
  })();
  // A payout to the wallet (an output with a datum) sent to the wallet's payment key with the attacker's stake key.
  out['the payout at the wallet key with a stranger\'s stake key'] = (() => {
    const tx = base();
    const b = tx.body();
    const all = CSL.TransactionOutputs.new();
    let moved = false;
    for (let i = 0; i < b.outputs().len(); i++) {
      const o = b.outputs().get(i);
      const base0 = CSL.BaseAddress.from_address(o.address());
      if (!moved && isWallet(o) && o.has_plutus_data() && base0) {
        const addr = CSL.BaseAddress.new(0, base0.payment_cred(), CSL.Credential.from_keyhash(CSL.Ed25519KeyHash.from_hex(ATTACKER_KEY))).to_address();
        const no = CSL.TransactionOutput.new(addr, o.amount());
        no.set_plutus_data(o.plutus_data());
        all.add(no);
        moved = true;
      } else all.add(o);
    }
    if (!moved) return null;
    return CSL.Transaction.new(withOutputs(b, all), tx.witness_set(), tx.auxiliary_data()).to_hex();
  })();
  for (const k of Object.keys(out)) if (out[k] === null) delete out[k];
  return out;
}

/** A service whose prepared transactions are tampered with (one variant per call, in turn). */
export function maliciousService(CSL, service, walletAddresses, onTamper) {
  let n = 0;
  return {
    ...service,
    async prepare(request) {
      const p = await service.prepare(request);
      const variants = Object.entries(tamperings(CSL, p.cbor, walletAddresses()));
      const [what, cbor] = variants[n++ % variants.length];
      onTamper?.(what);
      return { ...p, cbor };
    },
  };
}
