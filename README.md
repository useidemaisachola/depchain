# DepChain Stage 2

Made by:
- David Pinheiro (`ist117363`)
- Mehakpreet Khosa (`ist1107242`)
- Inês Cachola (`ist1106953`)

DepChain is a Java 17 / Maven project that implements a permissioned replicated blockchain with:
- 4 nodes, HotStuff-style consensus (Byzantine fault tolerant)
- a transaction-based application layer (accounts, balances, nonces)
- an embedded EVM service (Hyperledger Besu) that runs the provided ERC-20 contract (ISTCoin)

Default config:
- `n = 4`, `f = 1`, quorum `2f + 1 = 3`

## Requirements

- Java 17+
- Maven 3.x+

```text
java -version
mvn -version
```

## Build & Tests

Compile:

```text
mvn clean compile
```

Run all tests:

```text
mvn clean test
```

Run one specific test:

```text
mvn -Dtest=EvmServiceTest test
```

## Shell note

The `mvn exec:java` examples below use a quoted `-Dexec.args=...` format that works in both PowerShell and bash/zsh.

```text
mvn exec:java '-Dexec.args=config'
```

## Stage 2 Transaction Model (high level)

- Client requests carry exactly one signed transaction.
- Accounts are EOAs with an EVM address deterministically derived from their public key.
- Each transaction includes: `from`, `to`, `value`, `data` (EVM calldata), `gasPrice`, `gasLimit`, `nonce`.
- Nodes validate signature + sender + nonce + balance and apply fees according to gas used.
- The genesis file deploys the ISTCoin contract once at startup (see `src/main/resources/genesis.json`).

### Why 6 static clients in genesis?

`NUM_STATIC_CLIENTS = 6` is a Stage 2 requirement requested by staff.

We keep 6 pre-known client identities in the genesis because it makes the system deterministic and easy to test/demo under a fixed set of keys:
- 4 “main” clients (one per node/leader identity) to submit transactions in a controlled way across different views/leaders.
- 2 extra clients to represent “external” participants / adversarial scenarios that should be rejected (spoofing, replay/wrong nonce, insufficient balance, etc.).

In practice, not every demo needs to spawn all 6 clients at the same time — the requirement is that the genesis and the code support at least these 6 static client identities.

### Replay protection (nonce)

Replay protection is enforced by the sender nonce:
- Nodes reject transactions with a stale nonce (already executed) and avoid having two pending transactions with the same `(sender, nonce)`.
- The execution layer increments the sender nonce for every processed transaction (even if EVM execution fails), so the exact same signed transaction cannot be replayed after it is processed.

## Demos

### Stage 2 demos (recommended)

Stage 2 demo: DepCoin transfer (consensus + accounts + fees):

```text
mvn exec:java '-Dexec.args=demo_stage2_transfer'
```

What it does:
- Starts 4 nodes in-process.
- Starts 1 client, sends 1 DepCoin transfer transaction, prints balances on each node.

Stage 2 demo: ERC-20 transfer (consensus + EVM contract call):

```text
mvn exec:java '-Dexec.args=demo_stage2_erc20'
```

What it does:
- Starts 4 nodes in-process.
- Starts 1 client, sends an ISTCoin `transfer(to, amount)` call (EVM calldata), prints `balanceOf(to)` on each node.

Stage 2 demo: byzantine client spoof attempt rejected:

```text
mvn exec:java '-Dexec.args=demo_stage2_byzclient'
```

What it does:
- Starts 4 nodes in-process.
- Starts 1 attacker client and tries to submit a tx whose `from` is another account.
- Expected result: nodes reject as invalid transaction.

Stage 2 demo: 6 static clients submit transactions (requires `genkeys`):

```text
mvn exec:java '-Dexec.args=genkeys'
mvn exec:java '-Dexec.args=demo_stage2_6clients'
```

What it does:
- Starts 4 nodes in-process using the key material in `keys/`.
- Starts 6 clients (`client0..client5`) using `keys/clientX.{key,pub}`.
- Each client submits 1 DepCoin transfer to the next client.

### Infra demos (consensus / network)

Fault injection demo:

```text
mvn exec:java '-Dexec.args=demo_faults'
```

Byzantine leader equivocation demo:

```text
mvn exec:java '-Dexec.args=demo_byz'
```

Persistence / restart demo:

```text
mvn exec:java '-Dexec.args=demo_persist'
```

## Manual Run (nodes + interactive client)

### 1) Generate keys

```text
mvn exec:java '-Dexec.args=genkeys'
```

This generates RSA keypairs for:
- nodes: `keys/node0..node3` (`.key` + `.pub`)
- static clients: `keys/client0..client5` (`.key` + `.pub`)

### 2) Start the 4 nodes (4 terminals)

```text
mvn exec:java '-Dexec.args=node 0 HONEST'
mvn exec:java '-Dexec.args=node 1 HONEST'
mvn exec:java '-Dexec.args=node 2 HONEST'
mvn exec:java '-Dexec.args=node 3 HONEST'
```

### 3) Start a static client

Static clients are `0..(NUM_STATIC_CLIENTS-1)` (by default: `0..5`).

```text
mvn exec:java '-Dexec.args=client 0'
```

### 4) Interactive client commands

Inside the client:

```text
address
transfer 0x0000000000000000000000000000000000000001 1000
ist_transfer 0x0000000000000000000000000000000000000001 100
quit
```

## Tests that demonstrate Stage 2 deliverables

The Stage 2 test suite lives under `src/test/java/depchain`.

Per-file overview:

- `AccountModelTest`: account model invariants (EOA/contract), and deterministic EVM address derivation from public keys.
- `BlockPersistenceTest`: block hashing/JSON, on-disk persistence via `BlockStore`, and EVM world-state snapshot/restore.
- `ByzantineClientTest`: invalid/Byzantine client scenarios (spoofed `from`, replay/wrong nonce, insufficient balance, duplicate pending nonce).
- `EndToEndTest`: end-to-end cluster execution (consensus + EVM) for both DepCoin transfers and ISTCoin ERC-20 calls.
- `EvmServiceTest`: EVM service + ISTCoin deployment and ERC-20 behavior, including approval/frontrunning-related scenarios.
- `GasFeeTest`: gas fee calculation/enforcement (including out-of-gas behavior) and nonce increment rules.
- `GenesisLoaderTest`: loading `genesis.json` deterministically, funding genesis accounts, and deploying ISTCoin at genesis.
- `TransactionExecutionTest`: integration path “client tx → consensus → EVM execution”, checking balances/nonces and sequential commits.
- `TransactionOrderingTest`: pending transaction ordering by `gasPrice × gasLimit` (highest fee first) for proposal selection.
- `TransactionTest`: transaction creation/type detection, signing/verification, serialization round-trip, fee helpers, and validation guards.
- `WorldStateTest`: world-state CRUD, snapshot/rollback, and deterministic serialization.

If you need to point to a single “run everything” command for submission, use `mvn clean test`.

## What The Main Layers Do

- `FL` (`FairLossLinks`): thin layer over UDP; also where fault injection happens
- `SL` (`StubbornLinks`): keeps retransmitting messages until ACK-driven stop
- `PL` (`PerfectLinks`): removes duplicates and sends ACKs
- `APL` (`AuthenticatedPerfectLinks`): signs outgoing node messages and verifies incoming node signatures

## Persistence

Persistence is optional.

When enabled, a node stores:
- current view
- last committed hash
- last committed height
- local blockchain values
- decided request IDs
- replied request IDs

Manual node runs use the `state/` directory by default.
Tests use temporary directories instead.

## Scope

This repository includes both:
- Stage 1 infrastructure: consensus, communication layers, fault/byzantine experiments, and persistence/restart recovery.
- Stage 2 application layer: transaction validation/execution with nonces + fees, plus EVM execution (ISTCoin ERC-20) integrated with consensus.
