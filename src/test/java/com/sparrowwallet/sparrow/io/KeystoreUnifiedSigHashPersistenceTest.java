package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.ExtendedKey;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletModel;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.KeystoreUnifiedSigHashChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Whether the mark a user puts on a device survives being written and read back.
 *
 * A field added to Keystore reaches the JSON backend for nothing, since it serialises the object, and reaches the
 * database backend only if its column, its insert and its mapper are all changed together. Missing one of those
 * loses the setting silently at the next restart, which is exactly the failure a user would not think to report.
 */
public class KeystoreUnifiedSigHashPersistenceTest {
    private static final String[] XPUBS = {
            "tpubD8NXmKsmWp3a3DXhbihAYbYLGaRNVdTnr6JoSxxfXYQcmwVtW2hv8QoDwng6JtEonmJoL3cNEwfd2cLXMpGezwZ2vL2dQ7259bueNKj9C8n",
            "tpubD9429UXFGCTKJ9NdiNK4rC5ygqSUkginycYHccqSg5gkmyQ7PZRHNjk99M6a6Y3NY8ctEUUJvCu6iCCui8Ju3xrHRu3Ez1CKB4ZFoRZDdP9"};

    private Path tempDir;

    @BeforeEach
    public void setUp() throws Exception {
        Network.set(Network.TESTNET);
        tempDir = Files.createTempDirectory("sprw-unified-sighash");
    }

    @AfterEach
    public void tearDown() throws Exception {
        Network.set(null);
        if(tempDir != null) {
            Files.walk(tempDir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    @Test
    public void marksSurviveADbRoundTrip() throws Exception {
        assertRoundTrip(PersistenceType.DB);
    }

    @Test
    public void marksSurviveAJsonRoundTrip() throws Exception {
        assertRoundTrip(PersistenceType.JSON);
    }

    /**
     * The path a user actually takes. Marking a device on an existing wallet is not a structural change, so the
     * settings save does not rewrite the wallet: it diffs the keystores and posts an event, and the database
     * backend writes the change from that event alone. A backend that only writes the mark when a keystore is
     * first inserted passes the round trip above and loses every later change, which is every real wallet.
     */
    @Test
    public void aMarkAddedLaterSurvivesOnADbWallet() throws Exception {
        File walletFile = tempDir.resolve("later." + PersistenceType.DB.getExtension()).toFile();
        Storage storage = new Storage(PersistenceType.DB, walletFile);
        Wallet wallet = markedWallet();
        wallet.getKeystores().forEach(keystore -> keystore.setUnifiedSigHashSupported(false));
        try {
            storage.setEncryptionPubKey(Storage.NO_PASSWORD_KEY);
            storage.saveWallet(wallet);

            //As the settings save does: apply the change to the wallet, then post the event that persists it
            Keystore marked = wallet.getKeystores().get(0);
            marked.setUnifiedSigHashSupported(true);
            EventManager.get().post(new KeystoreUnifiedSigHashChangedEvent(wallet, wallet.copy(), storage.getWalletId(wallet), List.of(marked)));
            storage.updateWallet(wallet);
        } finally {
            storage.closeAndWait();
        }

        Storage reopened = new Storage(PersistenceType.DB, walletFile);
        try {
            Wallet loaded = reopened.loadUnencryptedWallet().getWallet();
            Assertions.assertTrue(loaded.getKeystores().get(0).isUnifiedSigHashSupported(),
                    "a mark added after the wallet was created was not written");
            Assertions.assertFalse(loaded.getKeystores().get(1).isUnifiedSigHashSupported());
        } finally {
            reopened.closeAndWait();
        }
    }

    private void assertRoundTrip(PersistenceType persistenceType) throws Exception {
        File walletFile = tempDir.resolve("marked." + persistenceType.getExtension()).toFile();
        Storage storage = new Storage(persistenceType, walletFile);
        try {
            storage.setEncryptionPubKey(Storage.NO_PASSWORD_KEY);
            storage.saveWallet(markedWallet());
        } finally {
            storage.closeAndWait();
        }

        Storage reopened = new Storage(persistenceType, walletFile);
        try {
            Wallet loaded = reopened.loadUnencryptedWallet().getWallet();
            Assertions.assertEquals(2, loaded.getKeystores().size());
            Assertions.assertTrue(loaded.getKeystores().get(0).isUnifiedSigHashSupported(),
                    persistenceType + " lost the mark on the device");
            Assertions.assertFalse(loaded.getKeystores().get(1).isUnifiedSigHashSupported(),
                    persistenceType + " invented a mark on an unmarked device");
        } finally {
            reopened.closeAndWait();
        }
    }

    /**
     * Nothing writes the wallet unless something listens for the event.
     *
     * The settings save posts this event and returns. The database backend records the change against the wallet,
     * and WalletForm turns it into the data changed event that actually triggers the write, exactly as it does for
     * a keystore label. Miss either subscriber and the mark is applied in memory, shown correctly until the wallet
     * is closed, and never written, which no round trip through Storage would catch.
     */
    @Test
    public void bothTheBackendAndTheFormListenForTheChange() {
        assertSubscribes(com.sparrowwallet.sparrow.io.db.DbPersistence.class);
        assertSubscribes(com.sparrowwallet.sparrow.wallet.WalletForm.class);
    }

    private void assertSubscribes(Class<?> listener) {
        boolean subscribed = Arrays.stream(listener.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(com.google.common.eventbus.Subscribe.class))
                .anyMatch(method -> method.getParameterCount() == 1 && method.getParameterTypes()[0] == KeystoreUnifiedSigHashChangedEvent.class);
        Assertions.assertTrue(subscribed, listener.getSimpleName() + " does not listen for " + KeystoreUnifiedSigHashChangedEvent.class.getSimpleName());
    }

    /**
     * The upgrade every existing user takes. V11 runs against a keystore table that already holds rows, where a
     * migration that only works on an empty table fails and takes the wallet with it. Simulated by putting a
     * current wallet back into its pre-V11 shape and reopening it, since the released build that wrote such a
     * wallet is not available to the test.
     */
    @Test
    public void theMigrationRunsAgainstAWalletThatAlreadyHasKeystores() throws Exception {
        File walletFile = tempDir.resolve("upgrade." + PersistenceType.DB.getExtension()).toFile();
        Storage storage = new Storage(PersistenceType.DB, walletFile);
        try {
            storage.setEncryptionPubKey(Storage.NO_PASSWORD_KEY);
            storage.saveWallet(markedWallet());
        } finally {
            storage.closeAndWait();
        }

        String url = "jdbc:h2:" + walletFile.getAbsolutePath().replace("." + PersistenceType.DB.getExtension(), "") + ";DATABASE_TO_UPPER=false";
        String schema;
        try(Connection connection = DriverManager.getConnection(url, "sa", ""); Statement statement = connection.createStatement()) {
            try(ResultSet rs = statement.executeQuery("select TABLE_SCHEMA from INFORMATION_SCHEMA.TABLES where TABLE_NAME = 'keystore'")) {
                Assertions.assertTrue(rs.next(), "no keystore table to roll back");
                schema = rs.getString(1);
            }
            try(ResultSet rs = statement.executeQuery("select count(*) from `" + schema + "`.keystore")) {
                Assertions.assertTrue(rs.next() && rs.getInt(1) == 2, "the rolled back table must hold rows, or this proves nothing");
            }
            statement.execute("alter table `" + schema + "`.keystore drop column unifiedSigHashSupported");
            statement.execute("delete from `" + schema + "`.flyway_schema_history where version = '11'");
        }

        Storage reopened = new Storage(PersistenceType.DB, walletFile);
        try {
            Wallet loaded = reopened.loadUnencryptedWallet().getWallet();
            Assertions.assertEquals(2, loaded.getKeystores().size());
            //The mark itself is gone with the column, which is correct: an upgraded wallet starts unmarked
            Assertions.assertFalse(loaded.getKeystores().get(0).isUnifiedSigHashSupported());
            Assertions.assertFalse(loaded.getKeystores().get(1).isUnifiedSigHashSupported());
        } finally {
            reopened.closeAndWait();
        }
    }

    /**
     * Two devices, one marked and one not, so that a backend writing a constant rather than the field is caught.
     */
    private Wallet markedWallet() {
        Wallet wallet = new Wallet("Marked");
        wallet.setPolicyType(PolicyType.MULTI_HD);
        wallet.setScriptType(ScriptType.P2WSH);
        wallet.getKeystores().add(hardwareKeystore("Marked device", XPUBS[0], "00000001", true));
        wallet.getKeystores().add(hardwareKeystore("Unmarked device", XPUBS[1], "00000002", false));
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.MULTI_HD, ScriptType.P2WSH, wallet.getKeystores(), 2));

        return wallet;
    }

    private Keystore hardwareKeystore(String label, String xpub, String fingerprint, boolean unifiedSigHashSupported) {
        Keystore keystore = new Keystore(label);
        keystore.setSource(KeystoreSource.HW_AIRGAPPED);
        keystore.setWalletModel(WalletModel.SEEDSIGNER);
        keystore.setKeyDerivation(new KeyDerivation(fingerprint, "m/48'/1'/0'/2'"));
        keystore.setExtendedPublicKey(ExtendedKey.fromDescriptor(xpub));

        if(unifiedSigHashSupported) {
            keystore.setUnifiedSigHashSupported(true);
        }

        return keystore;
    }
}
