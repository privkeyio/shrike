package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Server;
import org.girod.javafx.svgimage.SVGImage;
import org.girod.javafx.svgimage.SVGLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.Locale;

public enum BlockExplorer {
    /*
        mempool.guide is a mempool instance following the BLAKE2b fork. mempool.space and blockstream.info
        were here and are gone: neither has adopted the fork, so a link to either for anything
        mined past the activation height names a block they do not have. A custom URL can still be entered
        in the settings for anyone who wants one.
     */
    MEMPOOL_GUIDE("https://mempool.guide"),
    NONE("http://none");

    /**
     * The explorer to open when the user has not chosen one. See the note above for why it is not mempool.space.
     */
    public static BlockExplorer getDefault() {
        return MEMPOOL_GUIDE;
    }

    private static final Logger log = LoggerFactory.getLogger(BlockExplorer.class);

    private final Server server;

    BlockExplorer(String url) {
        this.server = new Server(url);
    }

    public Server getServer() {
        return server;
    }

    public static SVGImage getSVGImage(Server server) {
        try {
            URL url = AppServices.class.getResource("/image/blockexplorer/" + server.getHost().toLowerCase(Locale.ROOT) + "-icon.svg");
            if(url != null) {
                return SVGLoader.load(url);
            }
        } catch(Exception e) {
            log.error("Could not load block explorer image for " + server.getHost());
        }

        return null;
    }
}
