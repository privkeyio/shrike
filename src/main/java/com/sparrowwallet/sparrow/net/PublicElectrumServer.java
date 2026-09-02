package com.sparrowwallet.sparrow.net;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.sparrow.io.Server;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public enum PublicElectrumServer {
    BLOCKSTREAM_INFO("blockstream.info", "ssl://blockstream.info:700", Network.MAINNET),
    ELECTRUM_BLOCKSTREAM_INFO("electrum.blockstream.info", "ssl://electrum.blockstream.info:50002", Network.MAINNET),
    LUKECHILDS_CO("bitcoin.lu.ke", "ssl://bitcoin.lu.ke:50002", Network.MAINNET),
    EMZY_DE("electrum.emzy.de", "ssl://electrum.emzy.de:50002", Network.MAINNET),
    BITAROO_NET("electrum.bitaroo.net", "ssl://electrum.bitaroo.net:50002", Network.MAINNET),
    DIYNODES_COM("electrum.diynodes.com", "ssl://electrum.diynodes.com:50022", Network.MAINNET),
    SETHFORPRIVACY_COM("fulcrum.sethforprivacy.com", "ssl://fulcrum.sethforprivacy.com:50002", Network.MAINNET),
    TESTNET_ARANGUREN_ORG("testnet.aranguren.org", "ssl://testnet.aranguren.org:51002", Network.TESTNET),
    TESTNET_QTORNADO_COM("testnet.qtornado.com", "ssl://testnet.qtornado.com:51002", Network.TESTNET),
    SIGNET_MEMPOOL_SPACE("mempool.space", "ssl://mempool.space:60602", Network.SIGNET),
    TESTNET4_MEMPOOL_SPACE("mempool.space", "ssl://mempool.space:40002", Network.TESTNET4),
    TESTNET4_C3_SOFT("blackie.c3-soft.com", "ssl://blackie.c3-soft.com:57010", Network.TESTNET4),
    FRIGATE_2140_DEV("frigate.2140.dev", "ssl://frigate.2140.dev:50002", Network.MAINNET, List.of(PolicyType.SINGLE_HD, PolicyType.MULTI_HD, PolicyType.SINGLE_SP));

    PublicElectrumServer(String name, String url, Network network) {
        this(name, url, network, List.of(PolicyType.SINGLE_HD, PolicyType.MULTI_HD));
    }

    PublicElectrumServer(String name, String url, Network network, List<PolicyType> supportedPolicyTypes) {
        this.server = new Server(url, name);
        this.network = network;
        this.supportedPolicyTypes = supportedPolicyTypes;
    }

    /*
        Empty, so the public server option is never offered. No server listed above has adopted the fork, and
        connecting to one would show this wallet blocks and balances that diverge past the activation height.
        That is worse than a stale fee or a dead explorer link: it is the whole wallet reading the wrong
        history. Since there is no public server that has adopted the fork, the honest answer is to offer none
        rather than one that misleads. Connect a Knots node, or a private Electrum server indexing it. The
        entries are left in place rather than deleted so that upstream changes to this file continue to merge.
     */
    public static final List<Network> SUPPORTED_NETWORKS = List.of();

    private final Server server;
    private final Network network;
    private final List<PolicyType> supportedPolicyTypes;

    public Server getServer() {
        return server;
    }

    public String getUrl() {
        return server.getUrl();
    }

    public Network getNetwork() {
        return network;
    }

    public boolean isSupportedPolicyType(PolicyType policyType) {
        return supportedPolicyTypes.contains(policyType);
    }

    public boolean supportsAllPolicyTypes(List<PolicyType> policyTypes) {
        return policyTypes.stream().allMatch(this::isSupportedPolicyType);
    }

    public static List<PublicElectrumServer> getServers() {
        //SUPPORTED_NETWORKS is the single gate. Filtering only by network would still hand back servers on a
        //network where the option is withdrawn.
        if(!supportedNetwork()) {
            return List.of();
        }

        return Arrays.stream(values()).filter(server -> server.network == Network.get()).collect(Collectors.toList());
    }

    public static boolean supportedNetwork() {
        return SUPPORTED_NETWORKS.contains(Network.get());
    }

    public static PublicElectrumServer fromServer(Server server) {
        for(PublicElectrumServer publicServer : values()) {
            if(publicServer.getServer().equals(server)) {
                return publicServer;
            }
        }

        return null;
    }

    public static boolean isPublicServer(HostAndPort hostAndPort) {
        for(PublicElectrumServer publicServer : values()) {
            if(publicServer.getServer().getHostAndPort().equals(hostAndPort)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public String toString() {
        return server.getAlias();
    }
}
