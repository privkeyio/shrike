package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;

/**
 * Drives the wallet half of Shrike's send path against a real node, without the GUI.
 *
 * This is what SendController does once the user clicks through: build the PSBT for the transaction,
 * decide the hash type through AppServices, then Wallet.sign() and Wallet.finalise(). Only the JavaFX
 * wiring is left out.
 *
 * scriptpubkey             prints the wallet's first receive scriptPubKey, so the funding side and the
 *                          signing side cannot disagree about which output is being spent
 * send prevTxid prevVout prevValue destScriptHex destValue active
 *                          signs that output and prints the finalised transaction; active says whether
 *                          the chain tip reports the fork live
 */
public class SparrowSendHarness {
    private static final String MNEMONIC = "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor";
    private static final String MNEMONIC2 = "sample vibrant sound quantum ripple hidden pluck raven mirror ocean fabric noodle";

    /**
     * A 2-of-2 P2WSH wallet, both keys software seeds so the opt-in applies.
     *
     * The unified message takes the witnessScript as the script code for a segwit v0 input, where a
     * P2WPKH input takes the implied P2PKH script instead. Every other end to end test here spends
     * P2WPKH, so this is the one that exercises the other branch of that choice.
     */
    private static Wallet multisigWallet() throws Exception {
        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.MULTI_HD);
        wallet.setScriptType(ScriptType.P2WSH);
        for(String mnemonic : new String[] {MNEMONIC, MNEMONIC2}) {
            DeterministicSeed seed = new DeterministicSeed(mnemonic, "", 0, DeterministicSeed.Type.BIP39);
            wallet.getKeystores().add(Keystore.fromSeed(seed, PolicyType.MULTI_HD, wallet.getScriptType().getDefaultDerivation()));
        }
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.MULTI_HD, ScriptType.P2WSH, wallet.getKeystores(), 2));
        wallet.getNode(KeyPurpose.RECEIVE);
        return wallet;
    }

    /**
     * The same single sig wallet with the key removed and the keystore marked, which is what a watch only
     * import backed by a signer the owner speaks for looks like.
     *
     * The wallet holds no private key, so it never signs here. It only decides which hash type to declare in
     * the PSBT it exports, which is the whole point: the signing happens somewhere this wallet cannot see.
     */
    private static Wallet watchOnlyWallet(boolean marked) throws Exception {
        Wallet wallet = wallet();
        Keystore keystore = wallet.getKeystores().getFirst();
        keystore.setSeed(null);
        keystore.setSource(KeystoreSource.SW_WATCH);
        keystore.setUnifiedSigHashSupported(marked);
        return wallet;
    }

    private static Wallet wallet() throws Exception {
        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(ScriptType.P2WPKH);
        DeterministicSeed seed = new DeterministicSeed(MNEMONIC, "", 0, DeterministicSeed.Type.BIP39);
        Keystore keystore = Keystore.fromSeed(seed, PolicyType.SINGLE_HD, wallet.getScriptType().getDefaultDerivation());
        wallet.getKeystores().add(keystore);
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2WPKH, wallet.getKeystores(), 1));

        //getNode() fills to the lookahead index, so the receive nodes exist and
        //getWalletOutputScripts() can match the spent output to a key
        wallet.getNode(KeyPurpose.RECEIVE);

        return wallet;
    }

    private static WalletNode firstReceiveNode(Wallet wallet) {
        return wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
    }

    public static void main(String[] args) throws Exception {
        boolean mixed = args[0].startsWith("mixed-");
        //watchonly- decides with a marked watch only wallet and signs with a separate one holding the key,
        //which is the wallet-plus-external-signer split this mode exists to prove
        boolean watchOnly = args[0].startsWith("watchonly-") || args[0].startsWith("watchonlyunmarked-");
        boolean watchOnlyMarked = args[0].startsWith("watchonly-");
        String mode0 = mixed ? args[0].substring("mixed-".length())
                : (watchOnlyMarked ? args[0].substring("watchonly-".length())
                : (watchOnly ? args[0].substring("watchonlyunmarked-".length()) : args[0]));
        boolean multisig = mode0.endsWith("-multi");
        String mode = multisig ? mode0.substring(0, mode0.length() - "-multi".length()) : mode0;
        Wallet wallet = multisig ? multisigWallet() : (watchOnly ? watchOnlyWallet(watchOnlyMarked) : wallet());

        if("scriptpubkey".equals(mode)) {
            System.out.println("SPK=" + Utils.bytesToHex(wallet.getOutputScript(firstReceiveNode(wallet)).getProgram()));
            return;
        }

        if(!"send".equals(mode)) {
            throw new IllegalArgumentException("Unknown mode " + args[0]);
        }

        Sha256Hash prevTxid = Sha256Hash.wrap(args[1]);
        int prevVout = Integer.parseInt(args[2]);
        long prevValue = Long.parseLong(args[3]);
        byte[] destScript = Utils.hexToBytes(args[4]);
        long destValue = Long.parseLong(args[5]);
        boolean active = Boolean.parseBoolean(args[6]);

        WalletNode receiveNode = firstReceiveNode(wallet);
        Script spk = wallet.getOutputScript(receiveNode);

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(prevTxid, prevVout, new Script(new byte[0]));
        transaction.getInputs().getFirst().setSequenceNumber(0xFFFFFFFEL);
        transaction.addOutput(destValue, new Script(destScript));

        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().getFirst();
        psbtInput.setWitnessUtxo(new TransactionOutput(null, prevValue, spk.getProgram()));
        if(multisig) {
            //A P2WSH input carries the witnessScript; it is also the script code the unified message
            //commits to, where a P2WPKH input uses the implied P2PKH script instead.
            psbtInput.setWitnessScript(ScriptType.MULTISIG.getOutputScript(
                    wallet.getDefaultPolicy().getNumSignaturesRequired(), receiveNode.getPubKeys()));
        }
        //What PSBT(WalletTransaction) sets for a non-taproot input, which the opt-in is applied on top of
        psbtInput.setSigHash(SigHash.ALL);

        //The decision under test, taken exactly as the send path takes it: the chain must say the fork
        //is live and this wallet must hold every key that will sign.
        boolean optIn = active && AppServices.canSignUnified(wallet);
        System.out.println("CAN_SIGN=" + AppServices.canSignUnified(wallet));
        AppServices.applyUnifiedSigHash(psbt, optIn);
        System.out.println("DECLARED=" + psbt.getPsbtInputs().getFirst().getSigHash());

        if(mixed) {
            //Not something this wallet builds: it declares one hash type an input. Hand assembled here to test the
            //consensus claim that one opted-in signature is enough, by signing each key under a different type.
            //Nulling a seed leaves the keystore's key in the script while stopping it signing this pass.
            DeterministicSeed keep = wallet.getKeystores().get(1).getSeed();
            wallet.getKeystores().get(1).setSeed(null);
            psbt.getPsbtInputs().getFirst().setSigHash(SigHash.UNIFIED_ALL);
            wallet.sign(psbt);

            wallet.getKeystores().get(1).setSeed(keep);
            DeterministicSeed first = wallet.getKeystores().getFirst().getSeed();
            wallet.getKeystores().getFirst().setSeed(null);
            psbt.getPsbtInputs().getFirst().setSigHash(SigHash.ALL);
            wallet.sign(psbt);
            wallet.getKeystores().getFirst().setSeed(first);
        } else if(watchOnly) {
            //The watch only wallet decided and built this; it holds no key and cannot sign it. A separate
            //wallet holding the key signs the PSBT as it stands, which is what an external signer does with
            //the hash type the PSBT declares.
            Wallet signer = wallet();
            signer.sign(psbt);
            signer.finalise(psbt);
        } else {
            wallet.sign(psbt);
        }
        if(!watchOnly) {
            wallet.finalise(psbt);
        }
        Transaction finalTx = psbt.extractTransaction();

        TransactionWitness witness = finalTx.getInputs().getFirst().getWitness();
        if(witness == null || witness.getPushes().isEmpty()) {
            throw new IllegalStateException("No witness was produced");
        }
        //A P2WSH multisig witness starts with the empty element CHECKMULTISIG consumes, and ends with
        //the witnessScript, so take the first push that actually looks like a signature.
        byte[] signature = witness.getPushes().stream()
                .filter(push -> push.length >= 70 && push.length <= 73)
                .findFirst()
                .orElse(witness.getPushes().getFirst());

        System.out.println("SIGHASH_BYTE=" + String.format("%02x", signature[signature.length - 1]));
        //What the transaction view counts the replay protection label from, on the PSBT this run actually signed. The
        //first number is claimed as a protection so it counts only signatures that verify; the second is what is there.
        int[] counts = AppServices.signatureOptInCounts(psbt, wallet);
        System.out.println("VERIFIED_OPTED_IN=" + counts[0]);
        System.out.println("SIGNATURES_PRESENT=" + counts[2]);
        System.out.println("RAWTX=" + Utils.bytesToHex(finalTx.bitcoinSerialize()));
    }
}
