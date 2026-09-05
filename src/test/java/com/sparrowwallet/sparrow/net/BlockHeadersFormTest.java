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
 * Protocol 1.6 moved the run out of hex and into a headers field of its own, one entry per header. Asking for 1.8 therefore changes the form this
 * client is sent by any server capping between the version it used to ask for and this one, so reading only the concatenated form would break those
 * servers, whether or not a v2 header has been reached.
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
        BlockHeaders list = read("{\"count\":2,\"max\":2016,\"headers\":[\"" + V1 + "\",\"" + V2 + "\"]}");

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
        BlockHeaders b = read("{\"count\":0,\"max\":2016,\"headers\":[]}");
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
        Assertions.assertNull(read("{\"count\":1,\"max\":2016,\"headers\":[\"" + half + "\"]}").hex);
        Assertions.assertNull(read("{\"count\":1,\"max\":2016,\"headers\":[\"" + V1 + V2 + "\"]}").hex);
        Assertions.assertNull(read("{\"count\":1,\"max\":2016,\"headers\":[\"zz\"]}").hex);
    }

    /** A headers field that is not a list of hex strings leaves nothing to read, rather than something partly read. */
    @Test
    public void a_headers_field_that_is_not_a_list_is_refused() throws Exception {
        Assertions.assertNull(read("{\"count\":1,\"max\":2016,\"headers\":[1,2]}").hex);
        Assertions.assertNull(read("{\"count\":1,\"max\":2016,\"headers\":\"" + V1 + "\"}").hex);
        Assertions.assertNull(read("{\"count\":1,\"max\":2016,\"headers\":null}").hex);
    }

    /** The two forms are exclusive, so a response carrying only the old field is unaffected by the new one existing. */
    @Test
    public void the_concatenated_form_is_not_disturbed_by_the_new_field() throws Exception {
        BlockHeaders b = read("{\"count\":1,\"max\":2016,\"hex\":\"" + V1 + "\"}");
        Assertions.assertEquals(V1, b.hex);
        Assertions.assertEquals(1, headersOf(b, 1).size());
    }

    /**
     * Not a shape written here from reading the protocol, but the response a Fulcrum 2.1.2 actually returned once this client asked for a range
     * reaching past 1.6. The first fixture written for this was invented, put the list under hex, and passed against a server that does not exist.
     */
    @Test
    public void a_response_a_real_server_sent() throws Exception {
        BlockHeaders b = read("{\"count\":3,\"headers\":[\"0100000000000000000000000000000000000000000000000000000000000000000000003ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4adae5494dffff7f2002000000\",\"0000002006226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910fdc2dac016f2b3f4f356b30114a22a291ac643b82dd8a6af247c6d23428791849e8889b6affff7f2000000000\",\"0000002062fab197b7b201627d8673f3e6cb278394f98ee25e25f79e6391233575804e112de13277c4f93d6276bf83aaa0b8147c9e5775339d97fd87f737b4a2c287c2c9e9889b6affff7f2000000000\"],\"max\":2016}");
        List<BlockHeader> headers = headersOf(b, 3);
        Assertions.assertNotNull(headers, "the run a real server sent did not split into headers");
        Assertions.assertEquals(3, headers.size());
        Assertions.assertEquals("0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206", headers.get(0).getHash().toString());
        for(int i = 1; i < headers.size(); i++) {
            Assertions.assertEquals(headers.get(i - 1).getHash(), headers.get(i).getPrevBlockHash(), "the run does not link");
        }
    }

    /** Every entry can be a valid header and the run still be larger than this call can legitimately return, so the join stops rather than growing. */
    @Test
    public void a_run_larger_than_the_call_can_return_is_refused() throws Exception {
        StringBuilder list = new StringBuilder("{\"count\":1,\"max\":2016,\"headers\":[");
        for(int i = 0; i < 4200; i++) {
            list.append(i == 0 ? "" : ",").append('"').append(V1).append('"');
        }
        list.append("]}");
        Assertions.assertNull(read(list.toString()).hex);
    }

    @Test
    public void the_client_asks_for_a_version_a_v2_header_server_will_serve() {
        Assertions.assertEquals("1.8", ElectrumServer.SUPPORTED_VERSIONS[ElectrumServer.SUPPORTED_VERSIONS.length - 1]);
    }
}
