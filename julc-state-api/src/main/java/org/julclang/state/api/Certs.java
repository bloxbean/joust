package org.julclang.state.api;

import org.julclang.ledger.Credential;
import org.julclang.ledger.DRep;
import org.julclang.ledger.Delegatee;
import org.julclang.ledger.TxCert;
import org.julclang.stdlib.lib.ByteStringLib;
import org.julclang.stdlib.annotation.OnchainLibrary;

/** Exact certificate checks for {@link Certify} handlers (ADR-0010 r2), the same on the JVM and on-chain. */
@OnchainLibrary
public class Certs {

    /** A stake registration (with or without a deposit). */
    public static boolean isRegistration(TxCert cert) {
        return switch (cert) {
            case TxCert.RegStaking r -> true;
            default -> false;
        };
    }

    /** A stake delegation (alone or with registration) to exactly {@code pool}, and only stake: no vote delegation. */
    public static boolean delegatesOnlyTo(TxCert cert, byte[] pool) {
        return switch (cert) {
            case TxCert.DelegStaking d -> stakeOnlyTo(d.delegatee(), pool);
            case TxCert.RegDeleg d -> stakeOnlyTo(d.delegatee(), pool);
            default -> false;
        };
    }

    /** A vote delegation (alone or with registration) to exactly {@code drep}, and only votes. */
    public static boolean delegatesVoteTo(TxCert cert, DRep drep) {
        return switch (cert) {
            case TxCert.DelegStaking d -> voteOnlyTo(d.delegatee(), drep);
            case TxCert.RegDeleg d -> voteOnlyTo(d.delegatee(), drep);
            default -> false;
        };
    }

    /** DRep registration or update of the contract's own credential. */
    public static boolean isDRepLifecycle(TxCert cert) {
        return switch (cert) {
            case TxCert.RegDRep r -> true;
            case TxCert.UpdateDRep u -> true;
            default -> false;
        };
    }

    static boolean stakeOnlyTo(Delegatee d, byte[] pool) {
        return switch (d) {
            case Delegatee.Stake s -> ByteStringLib.equals(s.poolId().hash(), pool);
            default -> false;
        };
    }

    static boolean voteOnlyTo(Delegatee d, DRep drep) {
        return switch (d) {
            case Delegatee.Vote v -> sameDRep(v.dRep(), drep);
            default -> false;
        };
    }

    /** DRep equality that also holds on the JVM (records with byte arrays do not compare by value there). */
    public static boolean sameDRep(DRep a, DRep b) {
        return switch (a) {
            case DRep.DRepCredential ca -> switch (b) {
                case DRep.DRepCredential cb -> sameCredential(ca.credential(), cb.credential());
                default -> false;
            };
            case DRep.AlwaysAbstain x -> switch (b) {
                case DRep.AlwaysAbstain y -> true;
                default -> false;
            };
            case DRep.AlwaysNoConfidence x -> switch (b) {
                case DRep.AlwaysNoConfidence y -> true;
                default -> false;
            };
        };
    }

    public static boolean sameCredential(Credential a, Credential b) {
        return switch (a) {
            case Credential.PubKeyCredential ka -> switch (b) {
                case Credential.PubKeyCredential kb -> ByteStringLib.equals(ka.hash().hash(), kb.hash().hash());
                default -> false;
            };
            case Credential.ScriptCredential sa -> switch (b) {
                case Credential.ScriptCredential sb -> ByteStringLib.equals(sa.hash().hash(), sb.hash().hash());
                default -> false;
            };
        };
    }
}
