package com.sparrowwallet.sparrow.net;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
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
     * Protocol 1.6 changed this field from concatenated hex to a list with one entry per header, and a server answers in whichever form the
     * negotiated version calls for. Raising the version this client asks for therefore changes the form it is sent by any server capping between
     * the old maximum and the new one, so reading only the older form would break those servers on every chain, fork or not.
     *
     * A list is joined back into the concatenated form, which is what the rest of this class reads. Nothing is lost: the run is walked by each
     * header's own length either way, and a list entry that is not one whole header is refused here rather than being concatenated into a run that
     * happens to add up.
     */
    @JsonSetter("hex")
    public void setHex(JsonNode value) {
        if(value == null || value.isNull()) {
            hex = null;
        } else if(value.isTextual()) {
            hex = value.asText();
        } else if(value.isArray()) {
            StringBuilder joined = new StringBuilder();
            for(JsonNode element : value) {
                if(!element.isTextual() || new BlockHeaders(element.asText()).getHeaders(1) == null) {
                    hex = null;
                    return;
                }
                joined.append(element.asText());
            }
            hex = joined.toString();
        } else {
            hex = null;
        }
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
