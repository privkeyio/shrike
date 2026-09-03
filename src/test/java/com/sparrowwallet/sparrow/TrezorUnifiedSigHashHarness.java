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

    private static WalletNode firstReceiveNode(Wallet wallet) {
        return wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
    }

    public static void main(String[] args) throws Exception {
        Network.set(Network.REGTEST);

        String mode = args[0];
        String xpub = args[1];
        String fingerprint = args[2];
        boolean marked = Boolean.parseBoolean(args[3]);
        Wallet wallet = deviceWallet(xpub, fingerprint, marked);

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
        psbtInput.getDerivedPublicKeys().put(receiveNode.getPubKey(),
                wallet.getKeystores().getFirst().getKeyDerivation().extend(receiveNode.getDerivation()));
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
    }
}
