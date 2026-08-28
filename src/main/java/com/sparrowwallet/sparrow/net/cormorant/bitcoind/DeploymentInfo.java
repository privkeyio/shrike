package com.sparrowwallet.sparrow.net.cormorant.bitcoind;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The BLAKE2b hardfork schedule as the connected node reports it.
 *
 * The object is absent on a node that carries no schedule, and on every node predating the deployment,
 * so the field may be null and callers must treat "the node did not say" as distinct from "the node said
 * no".
 *
 * Unknown properties are ignored here, so a schedule under a name this build does not know reads as
 * absent, and an absent schedule is indistinguishable from one that agrees. That fails open, which is the
 * one direction this cross-check exists to prevent, so the field name is covered by a test against the
 * response a node actually sends rather than a synthetic one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeploymentInfo(Hardfork blake2b) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hardfork(Integer height, Boolean active) {
    }

    public Integer getHardforkHeight() {
        return blake2b == null ? null : blake2b.height();
    }
}
