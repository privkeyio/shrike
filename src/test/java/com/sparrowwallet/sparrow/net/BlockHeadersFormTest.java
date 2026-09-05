package com.sparrowwallet.sparrow.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.BlockHeader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Both forms a blockchain.block.headers response can take.
 *
 * Protocol 1.6 changed this field from concatenated hex to a list with one entry per header. Asking for 1.8 therefore changes the form this client
 * is sent by any server capping between the version it used to ask for and this one, so reading only the concatenated form would break those servers
 * on every chain, whether or not it has a v2 header anywhere in it.
 */
public class BlockHeadersFormTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    //A v1 header and a v2 header, so a run that mixes them is covered rather than only one length
    private static final String V1 = "010000006fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000982051fd1e4ba744bbbe680e1fee14677ba1a3c3540bf7b1cdb606e857233e0e61bc6649ffff001d01e36299";
    private static final String V2 = "000000a01f1e1d1c1b1a191817161514131211100f0e0d0c0b0a0908070605040302010000112233445566778899aabbccddeeff00102030405060708090a0b0c0d0e0f0a8913577ffff00200df0ad0b3a000000efcdab89ffeeddccbbaa998877665544332211005802000003005c000000000000000000000000000000000040d10c008967452301efcdab8967452301efcdab8967452301efcdab8967452301efcdab";

    private BlockHeaders read(String json) throws Exception {
        return MAPPER.readValue(json, BlockHeaders.class);
    }

    private List<BlockHeader> headersOf(BlockHeaders b, int count) {
        return b.getHeaders(count);
    }

    @Test
    public void the_concatenated_form_still_reads() throws Exception {
        BlockHeaders b = read("{\"count\":2,\"max\":2016,\"hex\":\"" + V1 + V2 + "\"}");
        List<BlockHeader> headers = headersOf(b, 2);
        Assertions.assertNotNull(headers);
        Assertions.assertEquals(2, headers.size());
        Assertions.assertFalse(headers.get(0).isHeaderV2());
        Assertions.assertTrue(headers.get(1).isHeaderV2());
    }

    @Test
    public void the_list_form_reads_the_same_headers() throws Exception {
        BlockHeaders concatenated = read("{\"count\":2,\"max\":2016,\"hex\":\"" + V1 + V2 + "\"}");
        BlockHeaders list = read("{\"count\":2,\"max\":2016,\"hex\":[\"" + V1 + "\",\"" + V2 + "\"]}");

        Assertions.assertEquals(concatenated.hex, list.hex);
        List<BlockHeader> a = headersOf(concatenated, 2);
        List<BlockHeader> b = headersOf(list, 2);
        Assertions.assertNotNull(b);
        Assertions.assertEquals(a.size(), b.size());
        for(int i = 0; i < a.size(); i++) {
            Assertions.assertArrayEquals(a.get(i).bitcoinSerialize(), b.get(i).bitcoinSerialize());
        }
    }

    @Test
    public void an_empty_list_is_an_empty_run() throws Exception {
        BlockHeaders b = read("{\"count\":0,\"max\":2016,\"hex\":[]}");
        Assertions.assertEquals("", b.hex);
        Assertions.assertNotNull(headersOf(b, 0));
        Assertions.assertTrue(headersOf(b, 0).isEmpty());
    }

    /**
     * A list entry has to be one whole header. Refusing here rather than joining means a set of entries that are individually wrong but add up to a
     * plausible run cannot be read as one.
     */
    @Test
    public void a_list_entry_that_is_not_one_header_is_refused() throws Exception {
        String half = V1.substring(0, V1.length() / 2);
        Assertions.assertNull(read("{\"count\":1,\"max\":2016,\"hex\":[\"" + half + "\"]}").hex);
        Assertions.assertNull(read("{\"count\":1,\"max\":2016,\"hex\":[\"" + V1 + V2 + "\"]}").hex);
        Assertions.assertNull(read("{\"count\":1,\"max\":2016,\"hex\":[\"zz\"]}").hex);
    }

    @Test
    public void neither_a_string_nor_a_list_is_refused() throws Exception {
        Assertions.assertNull(read("{\"count\":1,\"max\":2016,\"hex\":17}").hex);
        Assertions.assertNull(read("{\"count\":1,\"max\":2016,\"hex\":null}").hex);
        Assertions.assertNull(read("{\"count\":1,\"max\":2016,\"hex\":[1,2]}").hex);
    }

    @Test
    public void the_client_asks_for_a_version_a_v2_chain_will_serve() {
        Assertions.assertEquals("1.8", ElectrumServer.SUPPORTED_VERSIONS[ElectrumServer.SUPPORTED_VERSIONS.length - 1]);
    }
}
