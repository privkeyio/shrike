package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.Network;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The security hashrate figures, asked without a display.
 */
public class SecurityHashrateTest {
    @AfterEach
    public void tearDown() {
        Network.set(null);
    }

    /** At difficulty one a block is expected to take 2^256 / (0xffff * 2^208 + 1) hashes, just over 2^32. */
    @Test
    public void difficulty_one_is_about_two_to_the_32_hashes_a_block() {
        Assertions.assertEquals(4295032833.0 / 600, SecurityHashrate.getNetworkHashrate(0x1d00ffffL), 1);
    }

    /** The target of mainnet block 980138, which the node puts at about 46 PH/s since its last retarget. */
    @Test
    public void a_recent_mainnet_target_reads_tens_of_petahashes() {
        Assertions.assertEquals("45.3 PH/s", SecurityHashrate.format(SecurityHashrate.getNetworkHashrate(0x1900ad98L)));
    }

    @Test
    public void the_issued_supply_halves_with_the_subsidy() {
        Network.set(Network.MAINNET);
        Assertions.assertEquals(50 * 100_000_000L, SecurityHashrate.getIssuedSupply(0));
        Assertions.assertEquals(210_000 * 50 * 100_000_000L, SecurityHashrate.getIssuedSupply(209_999));
        Assertions.assertEquals(210_000 * 50 * 100_000_000L + 25 * 100_000_000L, SecurityHashrate.getIssuedSupply(210_000));
        //Four whole epochs and the 140001 blocks of the fifth up to 980000
        Assertions.assertEquals(1_968_750_000_000_000L + 140_001 * 312_500_000L, SecurityHashrate.getIssuedSupply(980_000));
    }

    @Test
    public void the_supply_stops_growing_once_the_subsidy_runs_out() {
        Network.set(Network.MAINNET);
        Assertions.assertEquals(2_099_999_997_690_000L, SecurityHashrate.getIssuedSupply(10_000_000));
    }

    @Test
    public void a_balance_gets_its_share_of_the_network_hashrate() {
        Network.set(Network.MAINNET);
        long supply = SecurityHashrate.getIssuedSupply(980_000);
        double network = SecurityHashrate.getNetworkHashrate(0x1900ad98L);
        Assertions.assertEquals(network, SecurityHashrate.getSecurityHashrate(supply, 980_000, 0x1900ad98L), 1);
        Assertions.assertEquals(network / 2, SecurityHashrate.getSecurityHashrate(supply / 2, 980_000, 0x1900ad98L), 1);
        Assertions.assertEquals(0, SecurityHashrate.getSecurityHashrate(0, 980_000, 0x1900ad98L));
    }

    @Test
    public void hashrates_are_given_in_the_largest_whole_unit() {
        Assertions.assertEquals("0 H/s", SecurityHashrate.format(0));
        Assertions.assertEquals("999 H/s", SecurityHashrate.format(999));
        Assertions.assertEquals("1.00 kH/s", SecurityHashrate.format(1000));
        Assertions.assertEquals("2.25 TH/s", SecurityHashrate.format(2.25e12));
        Assertions.assertEquals("43.0 PH/s", SecurityHashrate.format(43e15));
        Assertions.assertEquals("512 GH/s", SecurityHashrate.format(512e9));
    }
}
