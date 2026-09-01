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
}
