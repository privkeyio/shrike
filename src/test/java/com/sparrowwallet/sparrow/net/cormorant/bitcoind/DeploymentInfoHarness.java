package com.sparrowwallet.sparrow.net.cormorant.bitcoind;

import com.github.arteam.simplejsonrpc.client.JsonRpcClient;
import com.sparrowwallet.sparrow.io.Server;

/**
 * Calls getdeploymentinfo through Sparrow's own transport and proxy against a running node, which is the
 * one part of the activation cross-check that unit tests cannot reach: the RPC binding, the wire call
 * and the deserialisation together.
 *
 * args: <url> <user:pass>
 */
public class DeploymentInfoHarness {
    public static void main(String[] args) {
        BitcoindTransport transport = new BitcoindTransport(new Server(args[0]), BitcoindClient.CORE_WALLET_NAME, args[1]);
        BitcoindClientService service = new JsonRpcClient(transport).onDemand(BitcoindClientService.class);

        DeploymentInfo info = service.getDeploymentInfo();
        System.out.println("HARDFORK_HEIGHT=" + info.getHardforkHeight());
        System.out.println("HARDFORK_ACTIVE=" + (info.blake2b() == null ? null : info.blake2b().active()));
    }
}
