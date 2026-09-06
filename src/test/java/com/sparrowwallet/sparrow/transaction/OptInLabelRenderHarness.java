package com.sparrowwallet.sparrow.transaction;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.SigHash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.sparrow.SparrowWallet;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5Brands;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import org.controlsfx.glyphfont.GlyphFontRegistry;

import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;

/**
 * What the replay protection labels actually say once the transaction view has drawn them.
 *
 * OptInStatusTest covers the decision; this covers the wiring between that decision and the two labels a user reads,
 * which no unit test can reach. Run it after changing either.
 *
 * A harness rather than a test because it needs a display, which the build does not have.
 */
public class OptInLabelRenderHarness {
    private static Wallet wallet() throws Exception {
        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(ScriptType.P2WPKH);
        DeterministicSeed seed = new DeterministicSeed(
                "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor", "", 0, DeterministicSeed.Type.BIP39);
        wallet.getKeystores().add(Keystore.fromSeed(seed, PolicyType.SINGLE_HD, ScriptType.P2WPKH.getDefaultDerivation()));
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2WPKH, wallet.getKeystores(), null));
        wallet.getNode(KeyPurpose.RECEIVE);
        return wallet;
    }

    /**
     * A 2 of 3 where one signer is marked and two are not, which is the wallet keystoreDecision answers
     * OPTED_IN_IF_MARKED_SIGNS for: the two unmarked ones could form a quorum between them and that transaction would
     * carry no opted-in signature. It is the ordinary partially marked multisig, and the label over it read
     * "because null" until the caveat was used in place of a reason it has none of.
     */
    private static Wallet partiallyMarkedMultisig() throws Exception {
        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.MULTI_HD);
        wallet.setScriptType(ScriptType.P2WSH);

        String[] words = {
                "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor",
                "sell arrive brand fluid cousin twin trap bar hen fine bicycle rack",
                "quantum lens tag pencil kingdom obey noise pigeon oyster shoulder ordinary tilt"};
        for(int i = 0; i < words.length; i++) {
            Keystore keystore = Keystore.fromSeed(new DeterministicSeed(words[i], "", 0, DeterministicSeed.Type.BIP39),
                    PolicyType.MULTI_HD, ScriptType.P2WSH.getDefaultDerivation());
            //A device rather than a seed this wallet holds: only then is its support the user's to state, and only
            //then can a signer be unmarked
            keystore.setSource(com.sparrowwallet.drongo.wallet.KeystoreSource.HW_USB);
            keystore.setLabel("Signer " + (i + 1));
            keystore.setUnifiedSigHashSupported(i == 0);
            wallet.getKeystores().add(keystore);
        }

        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.MULTI_HD, ScriptType.P2WSH, wallet.getKeystores(), 2));
        wallet.getNode(KeyPurpose.RECEIVE);
        return wallet;
    }

    /** The same transaction before anything has signed it, which is where a declaration is all there is to read. */
    private static PSBT unsignedPsbt(Wallet wallet, SigHash sigHash) throws Exception {
        WalletNode node = wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
        Script spk = wallet.getOutputScript(node);

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addOutput(90_000L, spk);

        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
        psbtInput.setWitnessUtxo(new TransactionOutput(null, 100_000L, spk.getProgram()));
        psbtInput.setSigHash(sigHash);
        for(Keystore keystore : wallet.getKeystores()) {
            psbtInput.getDerivedPublicKeys().put(keystore.getPubKey(node),
                    keystore.getKeyDerivation().extend(node.getDerivation()));
        }

        return psbt;
    }

    private static PSBT signedPsbt(Wallet wallet, SigHash sigHash) throws Exception {
        WalletNode node = wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
        Script spk = wallet.getOutputScript(node);

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addOutput(90_000L, spk);

        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
        psbtInput.setWitnessUtxo(new TransactionOutput(null, 100_000L, spk.getProgram()));
        psbtInput.setSigHash(sigHash);
        psbtInput.getDerivedPublicKeys().put(node.getPubKey(), wallet.getKeystores().get(0).getKeyDerivation()
                .extend(node.getDerivation()));
        psbtInput.sign(wallet.getKeystores().get(0).getKey(node));

        return psbt;
    }

    /** The labels as they stand on a view that has been built and had events delivered to it. */
    private static String labelText(Node contents) {
        Node node = contents.lookup("#signaturesOptIn");
        if(!(node instanceof Label label)) {
            return "(no label)";
        }

        //The glyph is half of what a user reads, and the mapping from level to glyph is asserted nowhere else
        return label.getText() + (label.getGraphic() == null ? " (no glyph)"
                : " [" + String.join(",", label.getGraphic().getStyleClass()) + "]");
    }

    /**
     * The event layer, which is where the last two wrong readings lived: the label was computed once and then a later
     * event changed the answer without it being read again. Built once, then driven the way the application drives it.
     */
    private static void driveEvents(Wallet wallet) throws Exception {
        PSBT psbt = signedPsbt(wallet, SigHash.UNIFIED_ALL);
        TransactionData data = new TransactionData("events", psbt);
        data.setSigningWallet(wallet);
        HeadersForm form = new HeadersForm(data);
        Node contents = form.getContents();
        new Scene((javafx.scene.Parent)contents);
        ((Region)contents).resize(900, 700);
        contents.applyCss();
        ((Region)contents).layout();
        System.out.println("  after building the view: \"" + labelText(contents) + "\"");

        //Edited the way the view edits it, through the PSBT rather than the transaction it assembles on demand.
        //Mutating that assembled transaction changes nothing, which is why an earlier version of this proved nothing.
        psbt.setFallbackLocktime(psbt.getTransaction().getLocktime() + 5);
        //Posted with the transaction the view holds, not a freshly assembled one: a PSBT builds a new object on every
        //call and Transaction compares by identity, so the handler's own guard rejects anything else
        com.sparrowwallet.sparrow.EventManager.get().post(
                new com.sparrowwallet.sparrow.event.TransactionChangedEvent(data.getTransaction()));
        System.out.println("  after the transaction was edited: \"" + labelText(contents) + "\"");

        //A combine arrives carrying nothing this wallet can vouch for
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            psbtInput.getPartialSignatures().clear();
        }
        com.sparrowwallet.sparrow.EventManager.get().post(new com.sparrowwallet.sparrow.event.PSBTCombinedEvent(psbt));
        System.out.println("  after a combine that removed the signatures: \"" + labelText(contents) + "\"");
    }

    private static void render(String name, PSBT psbt, Wallet signingWallet) throws Exception {
        TransactionData data = new TransactionData(name, psbt);
        data.setSigningWallet(signingWallet);
        HeadersForm form = new HeadersForm(data);
        Node contents = form.getContents();
        new Scene((javafx.scene.Parent)contents);
        ((Region)contents).resize(900, 700);
        contents.applyCss();
        ((Region)contents).layout();

        for(String id : new String[] {"#signingWalletOptIn", "#signaturesOptIn"}) {
            Node node = contents.lookup(id);
            if(node instanceof Label label) {
                System.out.println("  " + name + " " + id + " -> \"" + label.getText() + "\""
                        + (label.getGraphic() == null ? " (no glyph)" : " [" + String.join(",", label.getGraphic().getStyleClass()) + "]")
                        + (label.getTooltip() == null ? "" : System.lineSeparator()
                            + "      tooltip: " + label.getTooltip().getText()));
            }
        }
    }

    private static volatile boolean failed;

    public static void main(String[] args) throws Exception {
        System.setProperty(SparrowWallet.APP_HOME_PROPERTY, Files.createTempDirectory("shrike-render").toString());
        Network.set(Network.MAINNET);

        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                GlyphFontRegistry.register(new FontAwesome5());
                GlyphFontRegistry.register(new FontAwesome5Brands());
                //The view asks the application for the wallets that are open, so there has to be one to ask
                com.sparrowwallet.sparrow.AppServices.initialize(null);

                Wallet wallet = wallet();
                render("opted-in, wallet present", signedPsbt(wallet, SigHash.UNIFIED_ALL), wallet);
                render("legacy, wallet present", signedPsbt(wallet, SigHash.ALL), wallet);
                render("opted-in, no wallet to vouch", signedPsbt(wallet, SigHash.UNIFIED_ALL), null);

                //A PSBT carrying a derivation this wallet cannot follow, on an input it does own. The input is found
                //by its script, so the derivation is never consulted and the honest answer stands: the guard around
                //that lookup must not cost a transaction its reading. Where the lookup does throw, the labels fall
                //back to saying nothing was checked, which is what the fail closed path in updateOptInStatus is for.
                PSBT undrivable = signedPsbt(wallet, SigHash.UNIFIED_ALL);
                java.util.List<com.sparrowwallet.drongo.crypto.ChildNumber> path =
                        new java.util.ArrayList<>(wallet.getKeystores().get(0).getKeyDerivation().getDerivation());
                path.add(new com.sparrowwallet.drongo.crypto.ChildNumber(0, false));
                path.add(new com.sparrowwallet.drongo.crypto.ChildNumber(0, true));
                undrivable.getPsbtInputs().get(0).getDerivedPublicKeys().put(
                        com.sparrowwallet.drongo.crypto.ECKey.fromPrivate(
                                com.sparrowwallet.drongo.Utils.hexToBytes("33".repeat(32))),
                        new com.sparrowwallet.drongo.KeyDerivation(
                                wallet.getKeystores().get(0).getKeyDerivation().getMasterFingerprint(),
                                com.sparrowwallet.drongo.KeyDerivation.writePath(path)));
                render("opted-in, junk derivation attached to its own input", undrivable, wallet);

                //Finalised, declaring the opt-in, and carrying nothing that reads as a signature. Nothing has been
                //checked, so the file's own declaration must not come back as a promise.
                PSBT finalisedJunk = signedPsbt(wallet, SigHash.UNIFIED_ALL);
                for(PSBTInput psbtInput : finalisedJunk.getPsbtInputs()) {
                    psbtInput.getPartialSignatures().clear();
                    byte[] pubKeyShaped = new byte[33];
                    java.util.Arrays.fill(pubKeyShaped, (byte)0x11);
                    pubKeyShaped[0] = 0x02;
                    psbtInput.setFinalScriptWitness(new com.sparrowwallet.drongo.protocol.TransactionWitness(
                            null, java.util.List.of(pubKeyShaped)));
                }
                render("finalised, declares the opt-in, nothing readable", finalisedJunk, wallet);
                render("finalised, declares the opt-in, no wallet", finalisedJunk, null);

                //Nothing signed, and the wallet cannot promise the declaration survives: two unmarked signers could
                //form the quorum between them. The condition is what this case has to say, and it has no reason.
                Wallet multisig = partiallyMarkedMultisig();
                render("unsigned, declares the opt-in, partially marked 2 of 3", unsignedPsbt(multisig, SigHash.UNIFIED_ALL), multisig);

                //Every signer marked, so the declaration will survive whoever signs. What is left to say belongs to
                //the chain, and it is said separately rather than folded into a sentence about the signers.
                Wallet marked = partiallyMarkedMultisig();
                marked.getKeystores().forEach(keystore -> keystore.setUnifiedSigHashSupported(true));
                render("unsigned, declares the opt-in, every signer marked", unsignedPsbt(marked, SigHash.UNIFIED_ALL), marked);

                render("unsigned, declares the opt-in, no wallet to ask", unsignedPsbt(multisig, SigHash.UNIFIED_ALL), null);

                //Signed by a taproot script path and nothing else, declaring the opt-in. Those signatures were
                //dropped at parse, so this looked unsigned and the label reported the declaration as what the
                //transaction would be. It is signed, and nothing in it has been checked.
                render("signed by a taproot script path only, declares the opt-in",
                        com.sparrowwallet.sparrow.TapScriptSignedTest.signedByScriptPath(
                                com.sparrowwallet.drongo.protocol.SigHash.ALL.byteValue()), wallet);

                //One input asks for the opt-in and another does not. A PSBT from elsewhere can declare it on an input
                //this wallet will never sign and the old type on the ones it will, so every input has to declare it
                //before the transaction can be said to.
                Wallet mixedWallet = wallet();
                WalletNode mixedNode = mixedWallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
                Script mixedSpk = mixedWallet.getOutputScript(mixedNode);

                //Built with both inputs from the start. A PSBT rebuilds its transaction on every call, so adding an
                //input to the one it hands back adds it to a throwaway and the fixture quietly stays single input.
                Transaction mixedTransaction = new Transaction();
                mixedTransaction.setVersion(2);
                mixedTransaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
                mixedTransaction.addInput(Sha256Hash.ZERO_HASH, 1, new Script(new byte[0]));
                mixedTransaction.addOutput(90_000L, mixedSpk);

                PSBT mixed = new PSBT(mixedTransaction);
                if(mixed.getPsbtInputs().size() != 2) {
                    throw new IllegalStateException("the mixed fixture must carry two inputs, not " + mixed.getPsbtInputs().size());
                }
                for(int i = 0; i < mixed.getPsbtInputs().size(); i++) {
                    PSBTInput psbtInput = mixed.getPsbtInputs().get(i);
                    psbtInput.setWitnessUtxo(new TransactionOutput(null, 100_000L, mixedSpk.getProgram()));
                    psbtInput.setSigHash(i == 0 ? SigHash.UNIFIED_ALL : SigHash.ALL);
                }
                render("unsigned, one input asks for the opt-in and one does not", mixed, mixedWallet);

                //A wallet that holds none of these keys. Nothing can be vouched for, so nothing may be reported as
                //checked, however confident the file's own declaration is.
                Wallet stranger = new Wallet();
                stranger.setPolicyType(PolicyType.SINGLE_HD);
                stranger.setScriptType(ScriptType.P2WPKH);
                stranger.getKeystores().add(Keystore.fromSeed(new DeterministicSeed(
                        "sell arrive brand fluid cousin twin trap bar hen fine bicycle rack", "", 0,
                        DeterministicSeed.Type.BIP39), PolicyType.SINGLE_HD, ScriptType.P2WPKH.getDefaultDerivation()));
                stranger.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2WPKH,
                        stranger.getKeystores(), null));
                stranger.getNode(KeyPurpose.RECEIVE);
                render("opted-in, but the open wallet holds none of these keys", signedPsbt(wallet(), SigHash.UNIFIED_ALL), stranger);

                //The mapping from level to mark, which no unit test can assert because a glyph needs a display, and
                //which nothing else covers: swapped, a green tick sits over "Not replay protected"
                for(Object[] pair : new Object[][] {
                        {HeadersController.OptInLevel.PROTECTED, "success"},
                        {HeadersController.OptInLevel.UNPROTECTED, "warn-icon"},
                        {HeadersController.OptInLevel.UNCHECKED, "question-icon"}}) {
                    String actual = String.join(",", HeadersController.glyphFor((HeadersController.OptInLevel)pair[0]).getStyleClass());
                    if(!actual.contains((String)pair[1])) {
                        throw new IllegalStateException(pair[0] + " takes the wrong mark: " + actual);
                    }
                }
                System.out.println("  level to mark: each of the three takes its own");

                System.out.println("driving events through a built view:");
                driveEvents(wallet());
            } catch(Throwable t) {
                System.out.println("RENDER FAILED: " + t);
                t.printStackTrace(System.out);
                failed = true;
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
        //Non zero, or a run that threw halfway reads as a clean one to whoever is looking at the output
        System.exit(failed ? 1 : 0);
    }
}
