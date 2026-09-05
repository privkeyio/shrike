package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHash;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.BlockSummary;
import org.girod.javafx.svgimage.SVGImage;
import org.girod.javafx.svgimage.SVGLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.*;

public enum FeeRatesSource {
    ELECTRUM_SERVER("Server", false) {
        @Override
        public Map<Integer, Double> getBlockTargetFeeRates(Map<Integer, Double> defaultblockTargetFeeRates) {
            return Collections.emptyMap();
        }

        @Override
        public boolean supportsNetwork(Network network) {
            return true;
        }
    },
    /*
        A mempool instance that follows the BLAKE2b fork, which is the reason for its presence here and is not
        apparent from the name. It runs the same API as the instances that stopped at the activation height,
        so it differs from those only in the host it asks.
     */
    MEMPOOL_GUIDE("mempool.guide", true) {
        @Override
        public Map<Integer, Double> getBlockTargetFeeRates(Map<Integer, Double> defaultblockTargetFeeRates) {
            String url = getApiUrl() + "v1/fees/precise";
            return getThreeTierFeeRates(this, defaultblockTargetFeeRates, url);
        }

        @Override
        public Double getNextBlockMedianFeeRate() throws Exception {
            String url = getApiUrl() + "v1/fees/mempool-blocks";
            return requestNextBlockMedianFeeRate(this, url);
        }

        @Override
        public BlockSummary getBlockSummary(Sha256Hash blockId) throws Exception {
            String url = blockSummaryUrl(getApiUrl(), blockId);
            return requestBlockSummary(this, url);
        }

        @Override
        public Map<Integer, BlockSummary> getRecentBlockSummaries() throws Exception {
            String url = getApiUrl() + "v1/blocks";
            return requestBlockSummaries(this, url);
        }

        @Override
        public List<BlockTransactionHash> getRecentMempoolTransactions() throws Exception {
            String url = getApiUrl() + "mempool/recent";
            return requestRecentMempoolTransactions(this, url);
        }

        private String getApiUrl() {
            String url = AppServices.isUsingProxy() ? "http://mempool5nxspkxjk3n5afqh7zswbv4i324z76ltmw3cvfmniw45mnhad.onion/api/" : "https://mempool.guide/api/";
            if(Network.get() != Network.MAINNET && supportsNetwork(Network.get())) {
                url = url.replace("/api/", "/" + Network.get().getName() + "/api/");
            }
            return url;
        }

        /*
            Testnet3 is absent because this instance does not serve it: every /testnet/api/ path returns the
            frontend with a 200 and text/html rather than a 404, so it would fail as a parse error at runtime
            instead of being declined here.
         */
        @Override
        public boolean supportsNetwork(Network network) {
            return network == Network.MAINNET || network == Network.TESTNET4 || network == Network.SIGNET;
        }
    },
    MINIMUM("Minimum (1 sat/vB)", false) {
        @Override
        public Map<Integer, Double> getBlockTargetFeeRates(Map<Integer, Double> defaultblockTargetFeeRates) {
            Map<Integer, Double> blockTargetFeeRates = new LinkedHashMap<>();
            for(Integer blockTarget : defaultblockTargetFeeRates.keySet()) {
                blockTargetFeeRates.put(blockTarget, 1.0);
            }

            return blockTargetFeeRates;
        }

        @Override
        public boolean supportsNetwork(Network network) {
            return true;
        }
    };

    private static final Logger log = LoggerFactory.getLogger(FeeRatesSource.class);
    public static final int BLOCKS_IN_HALF_HOUR = 3;
    public static final int BLOCKS_IN_HOUR = 6;
    public static final int BLOCKS_IN_TWO_HOURS = 12;

    private final String name;
    private final boolean external;

    FeeRatesSource(String name, boolean external) {
        this.name = name;
        this.external = external;
    }

    public abstract Map<Integer, Double> getBlockTargetFeeRates(Map<Integer, Double> defaultblockTargetFeeRates);

    public Double getNextBlockMedianFeeRate() throws Exception {
        throw new UnsupportedOperationException(name + " does not support retrieving the next block median fee rate");
    }

    /**
     * @param blockId the block id in display order, as BlockHeader.getHash() returns it, which is the
     *                order explorers use in a URL. Sha256Hash.toString() renders that order directly, so
     *                nothing here should reverse it again.
     */
    public BlockSummary getBlockSummary(Sha256Hash blockId) throws Exception {
        throw new UnsupportedOperationException(name + " does not support block summaries");
    }

    public Map<Integer, BlockSummary> getRecentBlockSummaries() throws Exception {
        throw new UnsupportedOperationException(name + " does not support block summaries");
    }

    public List<BlockTransactionHash> getRecentMempoolTransactions() throws Exception {
        throw new UnsupportedOperationException(name + " does not support recent mempool transactions");
    }

    public abstract boolean supportsNetwork(Network network);

    public String getName() {
        return name;
    }

    /**
     * The source to use when the user has not chosen one.
     *
     * mempool.space stopped at the activation height, so past the activation height it holds neither these blocks
     * nor this mempool. Asking it for a block summary returns 404 for a block that exists, and asking it for
     * fee rates prices a transaction against a mempool this wallet is not broadcasting into. mempool.guide
     * runs the same API and kept up, which is why it is the default here and not upstream.
     */
    public static FeeRatesSource getDefault() {
        return MEMPOOL_GUIDE;
    }

    public boolean isExternal() {
        return external;
    }

    private static Map<Integer, Double> getThreeTierFeeRates(FeeRatesSource feeRatesSource, Map<Integer, Double> defaultblockTargetFeeRates, String url) {
        if(log.isInfoEnabled()) {
            log.info("Requesting fee rates from " + url);
        }

        Map<Integer, Double> blockTargetFeeRates = new LinkedHashMap<>();
        HttpClientService httpClientService = AppServices.getHttpClientService();
        try {
            ThreeTierRates threeTierRates = feeRatesSource.getThreeTierRates(url, httpClientService);
            Double lastRate = null;
            for(Integer blockTarget : defaultblockTargetFeeRates.keySet()) {
                if(blockTarget < BLOCKS_IN_HALF_HOUR) {
                    blockTargetFeeRates.put(blockTarget, threeTierRates.fastestFee);
                } else if(blockTarget < BLOCKS_IN_HOUR) {
                    blockTargetFeeRates.put(blockTarget, threeTierRates.halfHourFee);
                } else if(blockTarget < BLOCKS_IN_TWO_HOURS || defaultblockTargetFeeRates.get(blockTarget) > threeTierRates.hourFee) {
                    blockTargetFeeRates.put(blockTarget, threeTierRates.hourFee);
                } else if(threeTierRates.minimumFee != null && defaultblockTargetFeeRates.get(blockTarget) < threeTierRates.minimumFee) {
                    blockTargetFeeRates.put(blockTarget, threeTierRates.minimumFee + (threeTierRates.hourFee > threeTierRates.minimumFee ? threeTierRates.hourFee * 0.2 : 0.0));
                } else {
                    blockTargetFeeRates.put(blockTarget, defaultblockTargetFeeRates.get(blockTarget));
                }

                if(lastRate != null) {
                    blockTargetFeeRates.put(blockTarget, Math.min(lastRate, blockTargetFeeRates.get(blockTarget)));
                }
                lastRate = blockTargetFeeRates.get(blockTarget);
            }

            if(threeTierRates.minimumFee != null) {
                blockTargetFeeRates.put(Integer.MAX_VALUE, threeTierRates.minimumFee);
            }
        } catch (Exception e) {
            if(log.isDebugEnabled()) {
                log.warn("Error retrieving recommended fee rates from " + url, e);
            } else {
                log.warn("Error retrieving recommended fee rates from " + url + " (" + e.getMessage() + ")");
            }
        }

        return blockTargetFeeRates;
    }

    protected ThreeTierRates getThreeTierRates(String url, HttpClientService httpClientService) throws Exception {
        return httpClientService.requestJson(url, ThreeTierRates.class, null);
    }

    protected static Double requestNextBlockMedianFeeRate(FeeRatesSource feeRatesSource, String url) throws Exception {
        if(log.isInfoEnabled()) {
            log.info("Requesting next block median fee rate from " + url);
        }

        HttpClientService httpClientService = AppServices.getHttpClientService();
        try {
            MempoolBlock[] mempoolBlocks = feeRatesSource.requestMempoolBlocks(url, httpClientService);
            return mempoolBlocks.length > 0 ? mempoolBlocks[0].medianFee : null;
        } catch (Exception e) {
            if(log.isDebugEnabled()) {
                log.warn("Error retrieving next block median fee rate from " + url, e);
            } else {
                log.warn("Error retrieving next block median fee rate from " + url + " (" + e.getMessage() + ")");
            }

            throw e;
        }
    }

    protected MempoolBlock[] requestMempoolBlocks(String url, HttpClientService httpClientService) throws Exception {
        return httpClientService.requestJson(url, MempoolBlock[].class, null);
    }

    /**
     * The block summary URL for an explorer.
     *
     * blockId is in display order, which is what BlockHeader.getHash() returns and what an explorer
     * expects in a path, and Sha256Hash.toString() renders that order directly. This existed inline at
     * two call sites reversing the bytes, which was correct only while the caller passed a wire-order
     * hash; keeping it in one place means the convention is stated once rather than assumed twice.
     */
    protected static String blockSummaryUrl(String apiUrl, Sha256Hash blockId) {
        return apiUrl + "v1/block/" + blockId;
    }

    protected static BlockSummary requestBlockSummary(FeeRatesSource feeRatesSource, String url) throws Exception {
        if(log.isInfoEnabled()) {
            log.info("Requesting block summary from " + url);
        }

        HttpClientService httpClientService = AppServices.getHttpClientService();
        try {
            MempoolBlockSummary mempoolBlockSummary = feeRatesSource.requestBlockSummary(url, httpClientService);
            return mempoolBlockSummary.toBlockSummary();
        } catch (Exception e) {
            if(log.isDebugEnabled()) {
                log.warn("Error retrieving block summary from " + url, e);
            } else {
                log.warn("Error retrieving block summary from " + url + " (" + e.getMessage() + ")");
            }

            throw e;
        }
    }

    protected MempoolBlockSummary requestBlockSummary(String url, HttpClientService httpClientService) throws Exception {
        return httpClientService.requestJson(url, MempoolBlockSummary.class, null);
    }

    protected static Map<Integer, BlockSummary> requestBlockSummaries(FeeRatesSource feeRatesSource, String url) throws Exception {
        if(log.isInfoEnabled()) {
            log.info("Requesting block summaries from " + url);
        }

        Map<Integer, BlockSummary> blockSummaryMap = new LinkedHashMap<>();
        HttpClientService httpClientService = AppServices.getHttpClientService();
        try {
            MempoolBlockSummary[] blockSummaries = feeRatesSource.requestBlockSummaries(url, httpClientService);
            for(MempoolBlockSummary blockSummary : blockSummaries) {
                if(blockSummary.height != null) {
                    blockSummaryMap.put(blockSummary.height, blockSummary.toBlockSummary());
                }
            }
            return blockSummaryMap;
        } catch (Exception e) {
            if(log.isDebugEnabled()) {
                log.warn("Error retrieving block summaries from " + url, e);
            } else {
                log.warn("Error retrieving block summaries from " + url + " (" + e.getMessage() + ")");
            }

            throw e;
        }
    }

    protected MempoolBlockSummary[] requestBlockSummaries(String url, HttpClientService httpClientService) throws Exception {
        return httpClientService.requestJson(url, MempoolBlockSummary[].class, null);
    }

    protected List<BlockTransactionHash> requestRecentMempoolTransactions(FeeRatesSource feeRatesSource, String url) throws Exception {
        HttpClientService httpClientService = AppServices.getHttpClientService();
        try {
            MempoolRecentTransaction[] recentTransactions = feeRatesSource.requestRecentMempoolTransactions(url, httpClientService);
            return Arrays.stream(recentTransactions).sorted().map(tx -> (BlockTransactionHash)new BlockTransaction(tx.txid, 0, null, tx.fee, null)).toList();
        } catch (Exception e) {
            if(log.isDebugEnabled()) {
                log.warn("Error retrieving recent mempool transactions from " + url, e);
            } else {
                log.warn("Error retrieving recent mempool from " + url + " (" + e.getMessage() + ")");
            }

            throw e;
        }
    }

    protected MempoolRecentTransaction[] requestRecentMempoolTransactions(String url, HttpClientService httpClientService) throws Exception {
        return httpClientService.requestJson(url, MempoolRecentTransaction[].class, null);
    }

    @Override
    public String toString() {
        return name;
    }

    public String getDescription() {
        return switch(this) {
            case ELECTRUM_SERVER -> "server";
            case MINIMUM -> "settings";
            default -> getName().toLowerCase(Locale.ROOT);
        };
    }

    public SVGImage getSVGImage() {
        try {
            URL url = AppServices.class.getResource("/image/feeratesource/" + getDescription() + "-icon.svg");
            if(url != null) {
                return SVGLoader.load(url);
            }
        } catch(Exception e) {
            log.error("Could not load fee rates source image for " + name);
        }

        return null;
    }

    protected record ThreeTierRates(Double fastestFee, Double halfHourFee, Double hourFee, Double minimumFee) {}

    protected record MempoolBlock(Integer nTx, Double medianFee) {}

    protected record MempoolBlockSummary(String id, Integer height, Long timestamp, Integer tx_count, Integer weight, MempoolBlockSummaryExtras extras) {
        public Double getMedianFee() {
            return extras == null ? null : extras.medianFee();
        }

        public BlockSummary toBlockSummary() {
            if(height == null || timestamp == null) {
                throw new IllegalStateException("Height = " + height + ", timestamp = " + timestamp + ": both must be specified");
            }
            return new BlockSummary(height, new Date(timestamp * 1000), getMedianFee(), tx_count, weight);
        }
    }

    private record MempoolBlockSummaryExtras(Double medianFee) {}

    protected record MempoolRecentTransaction(Sha256Hash txid, Long fee, Long vsize) implements Comparable<MempoolRecentTransaction> {
        private Double getFeeRate() {
            return fee == null || vsize == null ? 0.0d : (double)fee / vsize;
        }

        @Override
        public int compareTo(MempoolRecentTransaction o) {
            return Double.compare(o.getFeeRate(), getFeeRate());
        }
    }
}
