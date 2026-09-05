package com.sparrowwallet.sparrow.net;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.BlockHeader;
import com.sparrowwallet.drongo.protocol.HeaderChainState;
import com.sparrowwallet.drongo.protocol.ProtocolException;

import java.util.ArrayList;
import java.util.List;

/**
 * The blockchain.block.headers response: a run of consecutive block headers, with the maximum number of headers the server will return. The run
 * arrives as concatenated hex below protocol 1.6 and as a list of one hex string per header at 1.6 and above, never both.
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
     * Protocol 1.6 moved the run out of {@code hex} and into a field of its own, one hex string per header, and a server answers in whichever form
     * the negotiated version calls for. Raising the version this client asks for therefore changes the form it is sent by any server capping between
     * the old maximum and the new one, so reading only the older field would break those servers on every chain, fork or not.
     *
     * The list is joined back into the concatenated form, which is what the rest of this class, and every caller that reads {@code hex}, already
     * expects. Nothing is lost: the run is walked by each header's own length either way, and an entry that is not one whole header is refused here
     * rather than concatenated into a run that happens to add up.
     */
    @JsonSetter("headers")
    public void setHeaders(JsonNode value) {
        if(value == null || !value.isArray()) {
            return;
        }

        StringBuilder joined = new StringBuilder();
        for(JsonNode element : value) {
            if(!element.isTextual() || new BlockHeaders(element.asText()).getHeaders(1) == null) {
                hex = null;
                return;
            }
            joined.append(element.asText());
            //A server cannot make the join outgrow the largest run this call can legitimately return, whatever it puts in the list
            if(joined.length() > 2L * HeaderChainState.RETARGET_INTERVAL * BlockHeader.V2_LENGTH) {
                hex = null;
                return;
            }
        }
        hex = joined.toString();
    }

    public BlockHeaders() {
    }

    private BlockHeaders(String hex) {
        this.hex = hex;
    }

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
