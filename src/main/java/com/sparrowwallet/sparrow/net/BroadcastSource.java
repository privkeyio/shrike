package com.sparrowwallet.sparrow.net;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.tern.http.client.HttpResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

public enum BroadcastSource {
    /*
        None of the sources that were here, blockstream.info, mempool.space and mempool.emzy.de, has adopted
        the fork. This path replaces the connected node rather than supplementing it when a Tor proxy is
        configured, so broadcasting through one of them offered the transaction to nodes that have not
        adopted the fork: an opted-in transaction is refused there, but a legacy one relays, which is the
        replay this wallet exists to avoid. mempool.guide has adopted it and is the only source left.
     */
    MEMPOOL_GUIDE("mempool.guide", "https://mempool.guide", "http://mempool5nxspkxjk3n5afqh7zswbv4i324z76ltmw3cvfmniw45mnhad.onion") {
        public Sha256Hash broadcastTransaction(Transaction transaction) throws BroadcastException {
            String data = Utils.bytesToHex(transaction.bitcoinSerialize());
            return postTransactionData(data);
        }

        @Override
        public List<Network> getSupportedNetworks() {
            return List.of(Network.MAINNET, Network.SIGNET, Network.TESTNET4);
        }

        protected URL getURL(HostAndPort proxy) throws MalformedURLException, URISyntaxException {
            if(Network.get() == Network.MAINNET) {
                return new URI(getBaseUrl(proxy) + "/api/tx").toURL();
            } else if(Network.get() == Network.SIGNET) {
                return new URI(getBaseUrl(proxy) + "/signet/api/tx").toURL();
            } else if(Network.get() == Network.TESTNET4) {
                return new URI(getBaseUrl(proxy) + "/testnet4/api/tx").toURL();
            } else {
                throw new IllegalStateException("Cannot broadcast transaction to " + getName() + " on network " + Network.get());
            }
        }
    };

    private final String name;
    private final String tlsUrl;
    private final String onionUrl;

    private static final Logger log = LoggerFactory.getLogger(BroadcastSource.class);

    BroadcastSource(String name, String tlsUrl, String onionUrl) {
        this.name = name;
        this.tlsUrl = tlsUrl;
        this.onionUrl = onionUrl;
    }

    public String getName() {
        return name;
    }

    public String getTlsUrl() {
        return tlsUrl;
    }

    public String getOnionUrl() {
        return onionUrl;
    }

    public String getBaseUrl(HostAndPort proxy) {
        return (proxy == null ? getTlsUrl() : getOnionUrl());
    }

    public abstract Sha256Hash broadcastTransaction(Transaction transaction) throws BroadcastException;

    public abstract List<Network> getSupportedNetworks();

    protected abstract URL getURL(HostAndPort proxy) throws MalformedURLException, URISyntaxException;

    public Sha256Hash postTransactionData(String data) throws BroadcastException {
        //If a Tor proxy is configured, ensure we use a new circuit by configuring a random proxy password
        HttpClientService httpClientService = AppServices.getHttpClientService();
        httpClientService.changeIdentity();

        try {
            URL url = getURL(httpClientService.getTorProxy());

            if(log.isInfoEnabled()) {
                log.info("Broadcasting transaction to " + url);
            }

            String response = httpClientService.postString(url.toString(), null, "text/plain", data);

            try {
                return Sha256Hash.wrap(response.trim());
            } catch(Exception e) {
                throw new BroadcastException("Could not retrieve txid from broadcast, server returned: " + response);
            }
        } catch(HttpResponseException e) {
            throw new BroadcastException("Could not broadcast transaction, server returned " + e.getStatusCode() + ": " + e.getResponseBody());
        } catch(Exception e) {
            log.error("Could not post transaction via " + getName(), e);
            throw new BroadcastException("Could not broadcast transaction via " + getName(), e);
        }
    }

    public static final class BroadcastException extends Exception {
        public BroadcastException(String message) {
            super(message);
        }

        public BroadcastException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
