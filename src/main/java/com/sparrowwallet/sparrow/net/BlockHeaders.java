package com.sparrowwallet.sparrow.net;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.BlockHeader;
import com.sparrowwallet.drongo.protocol.ProtocolException;

import java.util.ArrayList;
import java.util.List;

/**
 * The blockchain.block.headers response: a run of consecutive block headers as concatenated hex, with the maximum number of headers the server will return.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlockHeaders {
    /**
     * Substituted for a range the server returned an error for, and filtered out before a batched result is returned.
     */
    public static final BlockHeaders ERROR_HEADERS = new BlockHeaders();

    public int count;
    public String hex;
    public int max;

    /**
     * The headers this response carries, or null where its hex does not split into exactly the given number of consecutive headers. A header is 80 or
     * 164 bytes according to the v2 flag in its version word, so the split follows each header's own length rather than a fixed stride: past the fork
     * a fixed one reads every header after the first from the middle of its predecessor.
     */
    List<BlockHeader> getHeaders(int count) {
        //The width bound is checked before decoding rather than after, so that a response far larger than the range asked for is refused
        //without being turned into bytes first
        if(hex == null || count < 0 || hex.length() > 2L * count * BlockHeader.V2_LENGTH) {
            return null;
        }

        List<BlockHeader> headers = new ArrayList<>();
        try {
            byte[] bytes = Utils.hexToBytes(hex);
            int offset = 0;
            for(int i = 0; i < count; i++) {
                BlockHeader header = new BlockHeader(bytes, offset);
                headers.add(header);
                offset += header.getLength();
            }

            return offset == bytes.length ? headers : null;
        } catch(ProtocolException | IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "BlockHeaders{count=" + count + ", max=" + max + '}';
    }
}
