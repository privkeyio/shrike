package com.sparrowwallet.sparrow.net.cormorant.bitcoind;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * getdeploymentinfo as Bitcoin Knots actually returns it.
 *
 * A mismatched field name here does not fail loudly: it deserialises to null, which the activation
 * decision reads as "the node did not say", and the cross-check it exists to perform silently stops
 * happening. The response below was captured from a v29.4.1.knots20260508rc1 regtest node.
 */
public class DeploymentInfoTest {
    private static final String RESPONSE =
            "{\"hash\": \"148c00cc445f43f092bbca8fcba249e48d80bc3bf1d33708bcab3155fe58d09e\", \"height\": 30,"
            + " \"hardfork\": {\"height\": 20, \"active\": true}}";

    @Test
    public void testTheHardforkHeightIsRead() throws Exception {
        DeploymentInfo info = new ObjectMapper().readValue(RESPONSE, DeploymentInfo.class);
        Assertions.assertNotNull(info.hardfork(), "The hardfork object was not deserialised");
        Assertions.assertEquals(20, info.getHardforkHeight());
        Assertions.assertEquals(Boolean.TRUE, info.hardfork().active());
    }

    /**
     * A node with no schedule omits the object entirely, which must read as "did not say" rather than
     * failing to parse.
     */
    /**
     * The response as a node actually sends it, captured from Bitcoin Knots v29.4.1.knots20260508rc2 on
     * testnet4 past the activation height. Trimmed to one deployment, since the map is long and the
     * parser ignores it; everything else is verbatim. A synthetic fixture cannot catch a field being
     * named differently in practice, which is the failure this guards.
     */
    @Test
    public void testTheResponseANodeActuallySends() throws Exception {
        String json = """
                {
                  "hash": "0000000000019a4eb4b620ba7fd56a220e09d30655a64444976ecb6c27cfefdc",
                  "height": 161442,
                  "deployments": {
                    "bip34": {
                      "type": "buried",
                      "active": true,
                      "height": 1
                    }
                  },
                  "hardfork": {
                    "height": 149537,
                    "active": true
                  }
                }""";

        DeploymentInfo deploymentInfo = new ObjectMapper().readValue(json, DeploymentInfo.class);
        Assertions.assertEquals(149537, deploymentInfo.getHardforkHeight());
        Assertions.assertTrue(deploymentInfo.hardfork().active());
    }

    @Test
    public void testAnAbsentHardforkIsNotAnError() throws Exception {
        DeploymentInfo info = new ObjectMapper().readValue("{\"height\": 30}", DeploymentInfo.class);
        Assertions.assertNull(info.hardfork());
        Assertions.assertNull(info.getHardforkHeight());
    }
}
