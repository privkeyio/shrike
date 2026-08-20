package com.sparrowwallet.sparrow.net.cormorant.bitcoind;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VerboseBlockHeader(String hash, int confirmations, int height, int version, String versionHex, String merkleroot, long time, long mediantime, long nonce,
                                 String bits, double difficulty, String chainwork, int nTx, String previousblockhash) {
}
