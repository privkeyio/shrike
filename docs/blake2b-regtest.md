# Reproducing the BLAKE2b regtest verification

How to run this fork against a solo regtest chain that activates the BLAKE2b proof of work at a low height, to confirm the wallet connects, syncs, sends and confirms across the activation.

This requires a build of Bitcoin Knots from the `__base_29_blake2` branch, which is not included in this repository. Build it separately and substitute its `bitcoind` and `bitcoin-cli` below.

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

The node does not create a wallet on its own, hence the `createwallet`. The alias names that wallet explicitly because once Sparrow connects, Cormorant loads a second wallet on the node, and unqualified wallet RPCs then fail with error -19 ("Multiple wallets are loaded").

Block 20 onwards carries the 164 byte v2 header and is hashed with BLAKE2b. `generatetoaddress` satisfies the coinbase headline requirement by itself, since the node builds the coinbase from its own block template.

Verbose `getblockheader` also reports a `header_version` field, 0 before activation and 2 after:

```
cli getblockheader $(cli getblockhash 19) | grep header_version        # 0
cli getblockheader $(cli getblockhash 20) | grep header_version        # 2
```

That field comes from [bitcoinknots PR #363](https://github.com/bitcoinknots/bitcoin/pull/363), which is not yet merged into `__base_29_blake2`, so it is absent on a plain build of that branch. The serialised length check above shows the same transition on any build and is the primary check.

## Point Sparrow at it

```
SPARROW_NETWORK=regtest ./sparrow
```

In the connection settings choose the **Bitcoin Core** server type, with URL `127.0.0.1:18443` and the RPC user and password above. Sparrow should connect and sync past the activation height.

## Send a transaction

Coinbase outputs need 100 confirmations before they can be spent, so mine to a Sparrow receive address and then mature it:

```
SPARROW_ADDR=<receive address from Sparrow>
cli generatetoaddress 1 $SPARROW_ADDR
cli generatetoaddress 100 $ADDR
```

The balance should appear in Sparrow once the funding block is mined, and become spendable after the 100 further blocks. Send from Sparrow to an address from `cli getnewaddress`, then confirm it:

```
cli generatetoaddress 1 $ADDR
cli gettransaction <txid>
```

The transaction should show one confirmation in both Sparrow and the node.
