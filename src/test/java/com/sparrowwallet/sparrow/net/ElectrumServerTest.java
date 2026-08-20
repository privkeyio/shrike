package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private static final String V2_HEADER_HEX = "000000a01f1e1d1c1b1a191817161514131211100f0e0d0c0b0a0908070605040302010000112233445566778899aabbccddeeff00102030405060708090a0b0c0d0e0f0a8913577ffff00200df0ad0b3a000000efcdab89ffeeddccbbaa998877665544332211005802000003005c000000000000000000000000000000000040d10c008967452301efcdab8967452301efcdab8967452301efcdab8967452301efcdab";
    private static final long V2_HEADER_TIME_SECS = 2000000000L;
    private static final int V2_HEADER_HEIGHT = 840000;

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
     */
    @Test
    public void acceptsVersion2Header() {
        //The claimed target is easier than the mainnet limit, so this header is only valid on regtest.
        //The surrounding setUp and tearDown leave the network as the other tests expect it.
        Network.set(Network.REGTEST);

        assertEquals(164, Utils.hexToBytes(V2_HEADER_HEX).length);
        assertNull(ElectrumServer.getTipValidationError(tip(V2_HEADER_HEIGHT, V2_HEADER_HEX), (V2_HEADER_TIME_SECS + 3600) * 1000));
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
