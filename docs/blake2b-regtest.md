# Reproducing the BLAKE2b regtest verification

How to run Shrike against a solo regtest chain that activates the BLAKE2b proof of work at a low height, to confirm the wallet connects, syncs, sends and confirms across the activation.

This requires a build of Bitcoin Knots at `v29.4.1.knots20260508`, which is not included in this repository. Build it separately and substitute its `bitcoind` and `bitcoin-cli` below.

## Start the node

```
mkdir -p "$HOME/blake2b-regtest"

bitcoind -regtest -daemon \
  -datadir=$HOME/blake2b-regtest \
  -blake2b_headline="BLAKE2b regtest" \
  -testactivationheight=blake2b@20 \
  -rpcuser=regtest -rpcpassword=regtest \
  -fallbackfee=0.0001
```

`-blake2b_headline` is mandatory; the node refuses to start without it ("This version requires blake2b_headline set manually"). Its value is consensus critical on a real chain, but arbitrary on a solo chain, since the same node both mines and validates it.

`-testactivationheight=blake2b@20` sets the activation height. This is the form used by `test/functional/feature_powchange.py`; the `-powchangetime` option is not used here.

The RPC port is regtest's default, 18443.

## Confirm the header change

Mine up to the last pre-activation block, then across it:

```
alias cli='bitcoin-cli -regtest -datadir=$HOME/blake2b-regtest -rpcuser=regtest -rpcpassword=regtest -rpcwallet=test'

cli createwallet test
ADDR=$(cli getnewaddress)
cli generatetoaddress 19 $ADDR
cli getblockheader $(cli getblockhash 19) false | tr -d '\n' | wc -c   # 160 hex chars, 80 bytes

cli generatetoaddress 1 $ADDR
cli getblockheader $(cli getblockhash 20) false | tr -d '\n' | wc -c   # 328 hex chars, 164 bytes
```

The node does not create a wallet on its own, hence the `createwallet`. The alias names that wallet explicitly because once Shrike connects, Cormorant loads a second wallet on the node, and unqualified wallet RPCs then fail with error -19 ("Multiple wallets are loaded").

Block 20 onwards carries the 164 byte v2 header and is hashed with BLAKE2b. `generatetoaddress` satisfies the coinbase headline requirement by itself, since the node builds the coinbase from its own block template.

Verbose `getblockheader` also reports a `header_version` field, 0 before activation and 2 after:

```
cli getblockheader $(cli getblockhash 19) | grep header_version        # 0
cli getblockheader $(cli getblockhash 20) | grep header_version        # 2
```

That field ships in this release, so it is present on a plain build of the tag. Builds from the development branches that predate it do not report it, and there the serialized length check above shows the same transition and is the primary check.

## Point Shrike at it

```
SPARROW_NETWORK=regtest /opt/shrike/bin/Shrike     # installed from the published deb
SPARROW_NETWORK=regtest ./gradlew run             # or from a source checkout
```

The environment variable and the launch script keep their upstream names; only the application was renamed, so `SPARROW_NETWORK` is correct here despite the mismatch.

In the connection settings choose the **Bitcoin Core** server type, with URL `127.0.0.1:18443` and the RPC user and password above. Shrike should connect and sync past the activation height.

Shrike keeps its configuration for this test in `~/.shrike/regtest`, separate from both its mainnet directory and anything belonging to an upstream Sparrow install.

## Send a transaction

Coinbase outputs need 100 confirmations before they can be spent, so mine to a Shrike receive address and then mature it:

```
SHRIKE_ADDR=<receive address from Shrike>
cli generatetoaddress 1 $SHRIKE_ADDR
cli generatetoaddress 100 $ADDR
```

The balance should appear in Shrike once the funding block is mined, and become spendable after the 100 further blocks. Send from Shrike to an address from `cli getnewaddress`, then confirm it:

```
cli generatetoaddress 1 $ADDR
cli gettransaction <txid>
```

The transaction should show one confirmation in both Shrike and the node.
