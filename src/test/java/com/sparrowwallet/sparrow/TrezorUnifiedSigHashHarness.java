package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
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
import com.sparrowwallet.drongo.wallet.WalletModel;
import com.sparrowwallet.drongo.wallet.WalletNode;

import java.util.Base64;

/**
 * The wallet half of a hardware signing run, without the GUI.
 *
 * This is what Sparrow decides before a device ever sees the transaction: whether this wallet may opt
 * in at all, and what hash type to declare in the PSBT it hands to a particular device. The device half
 * is driven separately by lark's TrezorEmulatorHarness, so the two halves compose the same way they do
 * in the application, and neither harness decides the other's part.
 *
 * build  xpub fingerprint marked prevTxid prevVout prevValue destScriptHex destValue active
 *        prints the PSBT to hand the device, having taken the opt-in decision the send path takes
 * finalise xpub fingerprint marked signedPsbtBase64
 *        finalises what the device returned and prints the transaction
 */
public class TrezorUnifiedSigHashHarness {

    /** A watch only single sig wallet over a device's account, marked or not as the owner would mark it. */
    private static Wallet deviceWallet(String xpub, String fingerprint, boolean marked) throws Exception {
        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(ScriptType.P2WPKH);

        Keystore keystore = new Keystore("Trezor");
        keystore.setSource(KeystoreSource.HW_USB);
        keystore.setWalletModel(WalletModel.TREZOR_1);
        keystore.setKeyDerivation(new KeyDerivation(fingerprint, ScriptType.P2WPKH.getDefaultDerivationPath()));
        keystore.setExtendedPublicKey(com.sparrowwallet.drongo.ExtendedKey.fromDescriptor(xpub));
        keystore.setUnifiedSigHashSupported(marked);
        wallet.getKeystores().add(keystore);

        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2WPKH, wallet.getKeystores(), null));
        wallet.getNode(KeyPurpose.RECEIVE);
        return wallet;
    }

    /**
     * A 2-of-3 where two keys are software seeds and the third is an unmarked device.
     *
     * This is the shape that reaches the branch a single sig wallet never can: the two software keys meet
     * the threshold on their own, so the wallet declares the opt-in, and the device is then handed a copy
     * asking for the base type rather than being locked out. The result is one input carrying an opted-in
     * signature and a legacy one, which consensus allows and which is what makes the transaction
     * unreplayable while that device still signs the old way.
     */
    private static final String[] COSIGNERS = {
            "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor",
            "sample vibrant sound quantum ripple hidden pluck raven mirror ocean fabric noodle"};

    private static Wallet quorumWallet(String xpub, String fingerprint) throws Exception {
        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.MULTI_HD);
        wallet.setScriptType(ScriptType.P2WSH);

        for(String mnemonic : COSIGNERS) {
            DeterministicSeed seed = new DeterministicSeed(mnemonic, "", 0, DeterministicSeed.Type.BIP39);
            wallet.getKeystores().add(Keystore.fromSeed(seed, PolicyType.MULTI_HD, ScriptType.P2WSH.getDefaultDerivation()));
        }

        Keystore device = new Keystore("Trezor");
        device.setSource(KeystoreSource.HW_USB);
        device.setWalletModel(WalletModel.TREZOR_1);
        device.setKeyDerivation(new KeyDerivation(fingerprint, KeyDerivation.writePath(ScriptType.P2WSH.getDefaultDerivation())));
        device.setExtendedPublicKey(com.sparrowwallet.drongo.ExtendedKey.fromDescriptor(xpub));
        device.setUnifiedSigHashSupported(false);
        wallet.getKeystores().add(device);

        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.MULTI_HD, ScriptType.P2WSH, wallet.getKeystores(), 2));
        wallet.getNode(KeyPurpose.RECEIVE);
        return wallet;
    }

    private static WalletNode firstReceiveNode(Wallet wallet) {
        return wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
    }

    public static void main(String[] args) throws Exception {
        Network.set(Network.REGTEST);

        String mode = args[0];
        String xpub = args[1];
        String fingerprint = args[2];
        //"quorum" selects the 2-of-3 with an unmarked device; otherwise the single sig wallet, marked or not
        boolean quorum = "quorum".equals(args[3]);
        Wallet wallet = quorum ? quorumWallet(xpub, fingerprint)
                : deviceWallet(xpub, fingerprint, Boolean.parseBoolean(args[3]));

        if("scriptpubkey".equals(mode)) {
            System.out.println("SPK=" + Utils.bytesToHex(wallet.getOutputScript(firstReceiveNode(wallet)).getProgram()));
            return;
        }

        if("finalise".equals(mode)) {
            PSBT signed = new PSBT(Base64.getDecoder().decode(args[4]));
            wallet.finalise(signed);
            System.out.println("TX=" + Utils.bytesToHex(signed.extractTransaction().bitcoinSerialize()));
            return;
        }

        if("combine".equals(mode)) {
            //The device answered on the copy it was given, which declares the base type. Its signature goes
            //back into the transaction the wallet actually built, where a software cosigner then signs under
            //the opted-in type. One input, two hash types, which is what consensus allows.
            PSBT original = new PSBT(Base64.getDecoder().decode(args[4]));
            PSBT fromDevice = new PSBT(Base64.getDecoder().decode(args[5]));
            original.combine(fromDevice);
            wallet.sign(original);
            PSBTInput combined = original.getPsbtInputs().getFirst();
            System.out.println("SIGNATURES=" + combined.getPartialSignatures().size());
            System.out.println("HASH_TYPES=" + combined.getPartialSignatures().values().stream()
                    .map(signature -> String.format("0x%02x", signature.sighashFlags)).sorted().toList());
            wallet.finalise(original);
            System.out.println("TX=" + Utils.bytesToHex(original.extractTransaction().bitcoinSerialize()));
            return;
        }

        Sha256Hash prevTxid = Sha256Hash.wrap(args[4]);
        int prevVout = Integer.parseInt(args[5]);
        long prevValue = Long.parseLong(args[6]);
        byte[] destScript = Utils.hexToBytes(args[7]);
        long destValue = Long.parseLong(args[8]);
        boolean active = Boolean.parseBoolean(args[9]);
        Transaction prevTx = new Transaction(Utils.hexToBytes(args[10]));

        WalletNode receiveNode = firstReceiveNode(wallet);
        Script spk = wallet.getOutputScript(receiveNode);

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(prevTxid, prevVout, new Script(new byte[0]));
        transaction.getInputs().getFirst().setSequenceNumber(0xFFFFFFFDL);
        transaction.addOutput(destValue, new Script(destScript));

        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().getFirst();
        psbtInput.setWitnessUtxo(new TransactionOutput(null, prevValue, spk.getProgram()));
        psbtInput.setNonWitnessUtxo(prevTx);
        if(quorum) {
            psbtInput.setWitnessScript(ScriptType.MULTISIG.getOutputScript(
                    wallet.getDefaultPolicy().getNumSignaturesRequired(), receiveNode.getPubKeys()));
            for(Keystore keystore : wallet.getKeystores()) {
                psbt.getExtendedPublicKeys().put(keystore.getExtendedPublicKey(), keystore.getKeyDerivation());
                psbtInput.getDerivedPublicKeys().put(keystore.getPubKey(receiveNode),
                        keystore.getKeyDerivation().extend(receiveNode.getDerivation()));
            }
        } else {
            psbtInput.getDerivedPublicKeys().put(receiveNode.getPubKey(),
                    wallet.getKeystores().getFirst().getKeyDerivation().extend(receiveNode.getDerivation()));
        }
        //What PSBT(WalletTransaction) sets for a non-taproot input, before the opt-in is applied on top
        psbtInput.setSigHash(SigHash.ALL);

        //The decision under test, taken as the send path takes it: the chain must say the fork is live
        //and every key that will sign must be one this wallet can vouch for.
        boolean canSign = AppServices.canSignUnified(wallet);
        System.out.println("CAN_SIGN=" + canSign);
        AppServices.applyUnifiedSigHash(psbt, active && canSign);
        System.out.println("DECLARED=" + psbt.getPsbtInputs().getFirst().getSigHash());

        //And the per device step: a device that was never marked is handed a copy asking for the base
        //type, so it can sign alongside the others rather than being locked out.
        PSBT devicePsbt = AppServices.psbtForDevice(wallet, psbt, fingerprint);
        System.out.println("FOR_DEVICE=" + devicePsbt.getPsbtInputs().getFirst().getSigHash());
        System.out.println("PSBT=" + Base64.getEncoder().encodeToString(devicePsbt.serialize()));
        System.out.println("WALLET_PSBT=" + Base64.getEncoder().encodeToString(psbt.serialize()));
    }
}
