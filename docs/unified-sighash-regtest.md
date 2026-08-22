# Reproducing the unified sighash regtest verification

How to run Shrike against a solo regtest chain that activates the fork at a low height, to confirm that transactions it sends after activation opt in to the unified signature hash and that the node accepts them.

This requires a build of Bitcoin Knots from the `hf-sighash-opt-in` branch of https://github.com/bitcoinknots/bitcoin/pull/357, which is based on `__base_29_blake2` and so carries the BLAKE2b proof of work as well. Build it separately and substitute its `bitcoind` and `bitcoin-cli` below. The setup is the same as [blake2b-regtest.md](blake2b-regtest.md); only the checks at the end differ.

## Start the node

```
mkdir -p "$HOME/unified-sighash-regtest"

bitcoind -regtest -daemon \
  -datadir=$HOME/unified-sighash-regtest \
  -blake2b_headline="BLAKE2b regtest" \
  -testactivationheight=blake2b@20 \
  -rpcuser=regtest -rpcpassword=regtest \
  -fallbackfee=0.0001
```

Both rule sets activate at the one deployment, so `-testactivationheight=blake2b@20` switches on the v2 header and the opt-in signature hash together at height 20.

## Confirm the fork is active

```
alias cli='bitcoin-cli -regtest -datadir=$HOME/unified-sighash-regtest -rpcuser=regtest -rpcpassword=regtest -rpcwallet=test'

cli createwallet test
ADDR=$(cli getnewaddress)
cli generatetoaddress 25 $ADDR
cli getdeploymentinfo | grep -A2 hardfork      # active: true
```

## Point Shrike at it

```
SPARROW_NETWORK=regtest ./sparrow
```

Choose the **Bitcoin Core** server type with URL `127.0.0.1:18443` and the RPC user and password above.

Shrike decides whether to opt in from the chain rather than from a configured height: `AppServices.isUnifiedSigHashActive()` returns true once the tip carries a v2 header, which is the same block the signature hash rules take effect at. Nothing needs configuring for this test, and nothing needs changing when the fork gets a real height.

## Send a transaction and check the hash type

Fund a Shrike receive address and mature it, as in the BLAKE2b document:

```
SHRIKE_ADDR=<receive address from Shrike>
cli generatetoaddress 1 $SHRIKE_ADDR
cli generatetoaddress 100 $ADDR
```

Send from Shrike to `cli getnewaddress`, then inspect what it produced:

```
cli getrawtransaction <txid> true | grep -A3 txinwitness
```

The first witness item is the signature. Its final byte is the hash type, and it must be `21`: `20` is the opt-in bit and `01` is SIGHASH_ALL. A transaction created before height 20 carries `01` instead, which is still valid and still relayed, it simply has no replay protection.

That the node accepted the transaction at all is the other half of the check: a signature carrying `21` only verifies if the node computed the same unified message that Shrike signed.

## The wallet level check, without the GUI

`SparrowSendHarness` runs the same path `SendController` takes once the user clicks through: it builds
the PSBT, lets `AppServices` decide the hash type, then calls `Wallet.sign()` and `Wallet.finalise()`.
Only the JavaFX wiring is left out, so this exercises the decision and the signing together.

```
DEPS=$(./gradlew -q :printTestClasspath | tail -1)

java -cp "$DEPS" com.sparrowwallet.sparrow.SparrowSendHarness scriptpubkey
java -cp "$DEPS" com.sparrowwallet.sparrow.SparrowSendHarness send \
    <prevTxid> <prevVout> <prevValue> <destScriptHex> <destValue> true
```

Fund the printed scriptPubKey on the regtest chain and sign its output with the second command. It
prints the hash type the wallet declared, the byte on the signature, and the finalised transaction for
`cli testmempoolaccept`. Passing `false` for the last argument runs the same path with the fork
reported inactive, which must produce a `01` signature that the node still accepts.

## The library level check

`UnifiedSigHashTest` in drongo checks the digest against the 166 cross-implementation vectors from the reference branch, covering bare and P2SH, segwit v0, taproot key path and tapscript. Those run with the ordinary suite and need no node.

For an end to end check without the GUI, `UnifiedSignHarness` signs a single P2WPKH input through drongo's own PSBT path and prints the finalised transaction:

```
cd drongo
./gradlew testClasses
DEPS=$(./gradlew -q printTestClasspath | tail -1)
CP="build/classes/java/test:build/classes/java/main:$DEPS"

java -cp "$CP" com.sparrowwallet.drongo.psbt.UnifiedSignHarness scriptpubkey <privKeyHex>
java -cp "$CP" com.sparrowwallet.drongo.psbt.UnifiedSignHarness sign \
    <privKeyHex> <prevTxid> <prevVout> <prevValue> <destScriptHex> <destValue> 21
```

Fund the printed scriptPubKey on the regtest chain, sign its output with the second command, and hand the result to `cli testmempoolaccept`. Passing a different digest byte as a ninth argument stamps `21` on a signature made over the legacy message, which must be rejected for a signature failure rather than for missing inputs.
