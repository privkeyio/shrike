package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Network;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The default source has to follow this chain.
 *
 * mempool.space follows the chain that kept SHA256d. Past the activation height it holds neither this chain's
 * blocks nor its mempool, so it answers 404 for a block that exists and prices transactions against a mempool
 * this wallet never broadcasts into. Both were reached with the source left unset, which is the state every
 * new install starts in, so the default is the thing worth pinning.
 */
public class FeeRatesSourceDefaultTest {
    @Test
    public void testTheDefaultFollowsThisChain() {
        Assertions.assertEquals(FeeRatesSource.MEMPOOL_GUIDE, FeeRatesSource.getDefault(),
                "the default must follow the BLAKE2b chain, not the one that kept SHA256d");
    }

    @Test
    public void testTheDefaultCoversTheNetworksTheForkRunsOn() {
        for(Network network : new Network[] {Network.MAINNET, Network.TESTNET4}) {
            Assertions.assertTrue(FeeRatesSource.getDefault().supportsNetwork(network),
                    network + " is a network this fork activates on, so the default must serve it");
        }
    }

    /**
     * Every call site that falls back reaches the same answer. Seven of them defaulted to mempool.space
     * independently, which is how fee rates and block summaries both ended up on the wrong chain.
     */
    @Test
    public void testTheFallbackIsStatedInOnePlace() throws Exception {
        java.nio.file.Path main = java.nio.file.Path.of("src/main/java/com/sparrowwallet/sparrow");
        long hardcoded;
        try(var paths = java.nio.file.Files.walk(main)) {
            hardcoded = paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return java.nio.file.Files.readString(p)
                                    .contains("feeRatesSource == null ? FeeRatesSource.MEMPOOL_SPACE");
                        } catch(Exception e) {
                            return false;
                        }
                    }).count();
        }
        Assertions.assertEquals(0, hardcoded,
                "a call site defaulting to mempool.space directly bypasses getDefault()");
    }

    /**
     * The explorer a transaction link opens has the same requirement. mempool.space and blockstream.info both
     * follow the chain that kept SHA256d, so a link to either names a block they do not have once the
     * transaction is mined past the activation height.
     */
    @Test
    public void testTheDefaultBlockExplorerFollowsThisChain() {
        Assertions.assertEquals(BlockExplorer.MEMPOOL_GUIDE, BlockExplorer.getDefault());
        Assertions.assertTrue(BlockExplorer.getDefault().getServer().getUrl().contains("mempool.guide"));
    }

    @Test
    public void testNoCallSiteDefaultsToAnExplorerOnTheOtherChain() throws Exception {
        java.nio.file.Path main = java.nio.file.Path.of("src/main/java/com/sparrowwallet/sparrow");
        long hardcoded;
        try(var paths = java.nio.file.Files.walk(main)) {
            hardcoded = paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().endsWith("BlockExplorer.java"))
                    .filter(p -> {
                        try {
                            return java.nio.file.Files.readString(p).contains("BlockExplorer.MEMPOOL_SPACE");
                        } catch(Exception e) {
                            return false;
                        }
                    }).count();
        }
        Assertions.assertEquals(0, hardcoded, "a call site naming mempool.space directly bypasses getDefault()");
    }

    /**
     * An existing config naming a source that has been removed must land on the default, not throw and not stay.
     *
     * Every config that had ever had the settings screen opened held "MEMPOOL_SPACE", because the screen selected
     * it by index and persisted it. Those configs outlive the enum value, so what they deserialize to is what
     * those users actually get.
     */
    @Test
    public void testAConfigNamingARemovedSourceFallsBackToTheDefault() {
        FeeRatesSource stored = new com.google.gson.Gson()
                .fromJson("\"MEMPOOL_SPACE\"", FeeRatesSource.class);
        Assertions.assertNull(stored, "a source no longer in the enum must read back as absent");

        FeeRatesSource effective = stored == null ? FeeRatesSource.getDefault() : stored;
        Assertions.assertEquals(FeeRatesSource.MEMPOOL_GUIDE, effective,
                "so the wallet uses the default rather than a source that follows the other chain");
    }

    /**
     * No source that reads the chain may be offered unless it follows this one. ELECTRUM_SERVER is the node the
     * user connected, and MINIMUM is a constant, so neither queries a third party.
     */
    @Test
    public void testEveryOfferedSourceFollowsThisChain() {
        for(FeeRatesSource source : FeeRatesSource.values()) {
            Assertions.assertTrue(
                    source == FeeRatesSource.ELECTRUM_SERVER || source == FeeRatesSource.MINIMUM
                            || source == FeeRatesSource.MEMPOOL_GUIDE,
                    source + " queries a third party, so it must be one that follows the BLAKE2b chain");
        }
    }

    /**
     * Broadcasting is the one that moves value. With a Tor proxy configured this path replaces the connected node
     * rather than supplementing it, so a source on the other chain does not merely mislead: an opted-in
     * transaction is refused there, but a legacy one relays, which is the replay this wallet exists to prevent.
     */
    @Test
    public void testEveryBroadcastSourceFollowsThisChain() {
        for(BroadcastSource source : BroadcastSource.values()) {
            Assertions.assertEquals(BroadcastSource.MEMPOOL_GUIDE, source,
                    source + " would put a transaction on the chain that kept SHA256d");
        }
    }

    /**
     * An explorer that does not have this chain's blocks shows nothing for a transaction mined past activation.
     * A custom URL can still be set in the settings, which is where anyone wanting the other chain should go.
     */
    @Test
    public void testEveryOfferedExplorerFollowsThisChainOrIsNone() {
        for(BlockExplorer explorer : BlockExplorer.values()) {
            Assertions.assertTrue(explorer == BlockExplorer.MEMPOOL_GUIDE || explorer == BlockExplorer.NONE,
                    explorer + " points at an explorer without this chain's blocks");
        }
    }

    /**
     * No public Electrum server follows this chain, and connecting to one that does not would show the wallet a
     * different chain's blocks and balances entirely. The option is withdrawn rather than filled with servers
     * that mislead; the entries stay in the enum only so upstream changes to that file keep merging.
     */
    @Test
    public void testThePublicServerOptionIsWithdrawn() {
        Assertions.assertTrue(PublicElectrumServer.SUPPORTED_NETWORKS.isEmpty(),
                "no public Electrum server indexes the BLAKE2b chain");
        Assertions.assertFalse(PublicElectrumServer.supportedNetwork());
        Assertions.assertTrue(PublicElectrumServer.getServers().isEmpty(),
                "and the list one caller divides by must be empty rather than full of other-chain servers");
    }
}
