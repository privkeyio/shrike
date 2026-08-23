package com.sparrowwallet.sparrow.net.cormorant.bitcoind;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The hardfork schedule as the connected node reports it.
 *
 * The object is absent on a node that carries no schedule, and on every node predating the deployment,
 * so both fields may be null and callers must treat "the node did not say" as distinct from "the node
 * said no".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeploymentInfo(Hardfork hardfork) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hardfork(Integer height, Boolean active) {
    }

    public Integer getHardforkHeight() {
        return hardfork == null ? null : hardfork.height();
    }
}
