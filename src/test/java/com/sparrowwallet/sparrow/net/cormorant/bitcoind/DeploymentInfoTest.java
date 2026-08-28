package com.sparrowwallet.sparrow.net.cormorant.bitcoind;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * getdeploymentinfo as Bitcoin Knots actually returns it.
 *
 * A mismatched field name here does not fail loudly: it deserialises to null, which the activation
 * decision reads as "the node did not say", and the cross-check it exists to perform silently stops
 * happening. That is why the fixture below is a verbatim capture rather than a synthetic response, and
 * why it keeps the surrounding fields it does not read.
 */
public class DeploymentInfoTest {
    /**
     * Captured verbatim from a regtest node started with -testactivationheight=blake2b@200. Trimmed to
     * one entry under "deployments", since that map is long and the parser ignores it; everything else is
     * as sent.
     */
    private static final String RESPONSE = """
            {
              "hash": "0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206",
              "height": 0,
              "deployments": {
                "bip34": {
                  "type": "buried",
                  "active": true,
                  "height": 1
                }
              },
              "blake2b": {
                "height": 200,
                "active": false
              }
            }""";

    @Test
    public void testTheScheduleReachesTheActivationDecision() throws Exception {
        DeploymentInfo deploymentInfo = new ObjectMapper().readValue(RESPONSE, DeploymentInfo.class);
        Assertions.assertNotNull(deploymentInfo.blake2b(), "The schedule was not deserialised");
        Assertions.assertEquals(200, deploymentInfo.getHardforkHeight());
        Assertions.assertEquals(Boolean.FALSE, deploymentInfo.blake2b().active());
    }

    /**
     * A node with no schedule omits the object entirely, which must read as "did not say" rather than
     * failing to parse.
     */
    @Test
    public void testAnAbsentScheduleIsNotAnError() throws Exception {
        DeploymentInfo deploymentInfo = new ObjectMapper().readValue("{\"height\": 30}", DeploymentInfo.class);
        Assertions.assertNull(deploymentInfo.blake2b());
        Assertions.assertNull(deploymentInfo.getHardforkHeight());
    }
}
