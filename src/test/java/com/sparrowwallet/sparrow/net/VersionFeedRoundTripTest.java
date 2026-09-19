package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.tern.http.client.HttpResponseException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Serves the feed over a real HTTP server, so the shape the site publishes is known to parse rather than assumed.
 */
public class VersionFeedRoundTripTest {
    private HttpServer server;

    private String serve(String path, String contentType, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if(contentType != null) {
                exchange.getResponseHeaders().set("Content-Type", contentType);
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try(OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @AfterEach
    public void stopServer() {
        if(server != null) {
            server.stop(0);
        }
    }

    private VersionCheckService.VersionCheck request(String url) throws Exception {
        HttpClientService httpClientService = new HttpClientService(null);
        try {
            return httpClientService.requestJson(url, VersionCheckService.VersionCheck.class, null);
        } finally {
            httpClientService.stop();
        }
    }

    @Test
    public void theFeedTheSitePublishesParses() throws Exception {
        String url = serve("/version.json", "application/json", "{\"version\":\"2.5.5-blake2b.99\"}");
        VersionCheckService.VersionCheck check = request(url);

        Assertions.assertNotNull(check);
        Assertions.assertEquals("2.5.5-blake2b.99", check.version);
        Assertions.assertTrue(VersionCheckService.isNewer(check.version, "2.5.5-blake2b.24"));
    }

    @Test
    public void aFeedServedWithoutAJsonContentTypeStillParses() throws Exception {
        //GitHub Pages types an extensionless file as octet-stream, which is why the feed can sit at /version as upstream does
        String url = serve("/version", "application/octet-stream", "{\"version\":\"2.5.5-blake2b.99\"}");
        VersionCheckService.VersionCheck check = request(url);

        Assertions.assertNotNull(check);
        Assertions.assertEquals("2.5.5-blake2b.99", check.version);
    }

    @Test
    public void aMissingFeedIsNotAnUpdate() throws Exception {
        //The state at merge time until the site publishes the feed, and any time the site is down
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/version", exchange -> exchange.sendResponseHeaders(404, -1));
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/version";

        Assertions.assertThrows(HttpResponseException.class, () -> request(url), "a 404 must surface rather than parse as a version");
    }

    @Test
    public void anHtmlErrorPageIsNotAVersion() throws Exception {
        //What a misconfigured host or a captive portal serves instead of the feed. tern refuses the response
        //outright on its content type rather than trying to read a version out of it.
        String url = serve("/version", "text/html", "<html><body>Not found</body></html>");

        Assertions.assertThrows(HttpResponseException.class, () -> request(url));
    }

    @Test
    public void aFieldTheFeedDoesNotCarryIsIgnored() throws Exception {
        //The site may add fields later; an older client must not fail on them
        String url = serve("/version.json", "application/json",
                "{\"version\":\"2.5.5-blake2b.99\",\"notes\":\"https://example.invalid\",\"signatures\":{}}");
        VersionCheckService.VersionCheck check = request(url);

        Assertions.assertNotNull(check);
        Assertions.assertEquals("2.5.5-blake2b.99", check.version);
    }
}
