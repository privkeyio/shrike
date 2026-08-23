package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.BlockHeader;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ElectrumServerTest {
    private static final String GENESIS_HEADER_HEX = "0100000000000000000000000000000000000000000000000000000000000000000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a29ab5f49ffff001d1dac2b7c";
    private static final long GENESIS_TIME_SECS = 1231006505L;

    private static final String BLOCK_800000_HEADER_HEX = "00601d3455bb9fbd966b3ea2dc42d0c22722e4c0c1729fad17210100000000000000000055087fab0c8f3f89f8bcfd4df26c504d81b0a88e04907161838c0c53001af09135edbd64943805175e955e06";
    private static final long BLOCK_800000_TIME_SECS = 1690168629L;

    /**
     * A v2 (BLAKE2b) header, 164 bytes rather than 80, taken from drongo's BlockHeaderPoWHashTest. It meets its
     * claimed target under the BLAKE2b hash, with a target inside the regtest proof of work limit.
     */
    private static final String BLOCK_800000_ID = "00000000000000000002a7c4c1e48d76c5a37902165a270156b7a8d72728a054";
    private static final String V2_HEADER_HEX = "000000a00b6ae048ff6a63b448cc325a81e22cd304766954f0833e3182dfe8c8cfeca202e44340a302bb650d3050411924e9e83230d3755ee9b2f51509cee40130e8a94f05dd886affff7f2000000000000000000000000000000000000000000000000000000000000000000100000000000000000000000000000000000000140000000000000000000000000000000000000000000000000000000000000000000000";
    private static final long V2_HEADER_TIME_SECS = 1787354373L;
    private static final int V2_HEADER_HEIGHT = 20;

    @BeforeEach
    public void setUp() {
        Network.set(Network.MAINNET);
    }

    @Test
    public void acceptsRealHeader() {
        assertNull(ElectrumServer.getTipValidationError(tip(800000, BLOCK_800000_HEADER_HEX), (BLOCK_800000_TIME_SECS + 3600) * 1000));
    }

    /**
     * Proof of work is checked against the header's own claimed target, so a genuine difficulty 1 header is valid on its own terms, however old it is and
     * whatever height it is announced at. Establishing that a header belongs to the chain at the announced height requires linkage from a known checkpoint.
     */
    @Test
    public void acceptsGenuineHeaderRegardlessOfAgeAndAnnouncedHeight() {
        assertNull(ElectrumServer.getTipValidationError(tip(0, GENESIS_HEADER_HEX), (GENESIS_TIME_SECS + 3600) * 1000));
        assertNull(ElectrumServer.getTipValidationError(tip(950000, GENESIS_HEADER_HEX), System.currentTimeMillis()));
    }

    /**
     * A v2 header carries 84 bytes of additional fields and has its proof of work measured against the BLAKE2b
     * hash rather than SHA256d. Tip validation reads the length from the header and defers the proof of work
     * check to drongo, so it needs no version handling of its own. This pins that, so a length assumption
     * reintroduced here could not silently start rejecting every server on the BLAKE2b chain.
     *
     * The header is one Bitcoin Knots actually mined on a regtest chain activating at height 20, rather
     * than a constructed one, so it also fails if drongo stops agreeing with a real node about the hash.
     */
    @Test
    public void acceptsVersion2Header() {
        //The claimed target is easier than the mainnet limit, so this header is only valid on regtest.
        //The surrounding setUp and tearDown leave the network as the other tests expect it.
        Network.set(Network.REGTEST);

        assertEquals(164, Utils.hexToBytes(V2_HEADER_HEX).length);
        assertNull(ElectrumServer.getTipValidationError(tip(V2_HEADER_HEIGHT, V2_HEADER_HEX), (V2_HEADER_TIME_SECS + 3600) * 1000));
    }

    /**
     * A block id comes from the header, not from re-deriving SHA256d by hand.
     *
     * Two things go wrong when it is derived by hand. Sha256Hash.twiceOf does not reverse and
     * BlockHeader.getHash does, so the hand-rolled version is byte reversed even for a v1 header, which
     * is why the id below reads back to front. And past the activation height the block id is not SHA256d
     * at all: getPoWHash runs the BLAKE2b pipeline for a v2 header and delegates to getHash for a v1 one,
     * so it is the accessor that is correct on both sides of the fork.
     */
    @Test
    public void testTheBlockIdComesFromTheHeader() {
        BlockHeader blockHeader = new BlockHeader(Utils.hexToBytes(BLOCK_800000_HEADER_HEX), 0);
        assertEquals(BLOCK_800000_ID, blockHeader.getPoWHash().toString());
        assertNotEquals(BLOCK_800000_ID, Sha256Hash.twiceOf(blockHeader.bitcoinSerialize()).toString(),
                "Deriving the id by hand must not silently agree, or this guards nothing");
    }

    @Test
    public void rejectsFutureTimestampedHeader() {
        assertNotNull(ElectrumServer.getTipValidationError(tip(800000, BLOCK_800000_HEADER_HEX), (BLOCK_800000_TIME_SECS - 5 * 60 * 60) * 1000));
    }

    @Test
    public void rejectsTamperedHeader() {
        byte[] tampered = Utils.hexToBytes(BLOCK_800000_HEADER_HEX);
        tampered[79] ^= 0x01;
        assertNotNull(ElectrumServer.getTipValidationError(tip(800000, Utils.bytesToHex(tampered)), (BLOCK_800000_TIME_SECS + 3600) * 1000));
    }

    @Test
    public void rejectsMalformedTips() {
        long now = (BLOCK_800000_TIME_SECS + 3600) * 1000;
        assertNotNull(ElectrumServer.getTipValidationError(tip(800000, null), now));
        assertNotNull(ElectrumServer.getTipValidationError(tip(-1, BLOCK_800000_HEADER_HEX), now));
        assertNotNull(ElectrumServer.getTipValidationError(tip(800000, "cafebabe"), now));
    }

    private BlockHeaderTip tip(int height, String hex) {
        BlockHeaderTip tip = new BlockHeaderTip();
        tip.height = height;
        tip.hex = hex;
        return tip;
    }

    @AfterEach
    public void tearDown() throws Exception {
        Network.set(null);
    }
}
