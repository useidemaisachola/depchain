# DepChain Stage 1

Made by:
- David Pinheiro (`ist117363`)
- Mehakpreet Khosa (`ist1107242`)
- Inês Cachola (`ist1106953`)

DepChain is a Java 17 / Maven project that implements a permissioned blockchain-style replicated system with 4 nodes and a HotStuff-style consensus flow.

This project is configured for:
- `n = 4` nodes
- `f = 1` Byzantine fault
- quorum `= 2f + 1 = 3`
- leader formula `leader = view % NUM_NODES`

## What The Project Has

- communication stack: `UDP -> FL -> SL -> PL -> APL`
- HotStuff-style phases: `PREPARE -> PRE_COMMIT -> COMMIT -> DECIDE`
- timeout-based view change with `NEW_VIEW`
- node-to-node RSA signatures
- client request signing
- hybrid encryption for client-to-node requests
- intrusive fault injection at the `FL` layer
- Byzantine modes:
  - `HONEST`
  - `SILENT`
  - `EQUIVOCATE_LEADER`
  - `INVALID_VOTE_SIGNATURE`
- optional persistent node state

## Requirements

- Java 17 or newer
- Maven 3.x or newer
- Any normal shell: PowerShell, bash, zsh, or similar

The project is compiled with Java release `17`, so Java `17+` is fine, including newer current Java versions.

Check your versions:

```text
java -version
mvn -version
```

## Project Layout

- `src/main/java/depchain/App.java`: entry point and demo commands
- `src/main/java/depchain/node/Node.java`: node logic and consensus flow
- `src/main/java/depchain/client/BlockchainClient.java`: client library and interactive client
- `src/main/java/depchain/net/*`: UDP and link layers
- `src/main/java/depchain/consensus/*`: blocks, votes, payloads, quorum certificates
- `src/main/java/depchain/storage/*`: persistent node state
- `src/test/java/depchain/DepChainStage1Test.java`: automated tests
- `keys/`: generated node key files
- `state/`: persistent node state for manual runs

## Build

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
mvn -Dtest=DepChainStage1Test#consensusAppendsToAllNodes test
```

## Shell Note

The `mvn exec:java` examples below use a quoted `-Dexec.args=...` argument format that works in both PowerShell and bash/zsh.

Example:

```text
mvn exec:java '-Dexec.args=client 100'
```

In PowerShell, this avoids the argument-splitting problem.

In bash/zsh on Linux or macOS, this also works, and this form works too:

```text
mvn exec:java -Dexec.args="client 100"
```

## Demo Commands

Show network config:

```text
mvn exec:java '-Dexec.args=config'
```

Run the basic in-process demo:

```text
mvn exec:java '-Dexec.args=test'
```

Run the fault demo:

```text
mvn exec:java '-Dexec.args=demo_faults'
```

Run the Byzantine leader demo:

```text
mvn exec:java '-Dexec.args=demo_byz'
```

Run the persistence / restart demo:

```text
mvn exec:java '-Dexec.args=demo_persist'
```

## Manual Run

### 1. Generate node keys

```text
mvn exec:java '-Dexec.args=genkeys'
```

This creates:
- `keys/node0.key`, `keys/node0.pub`
- `keys/node1.key`, `keys/node1.pub`
- `keys/node2.key`, `keys/node2.pub`
- `keys/node3.key`, `keys/node3.pub`

### 2. Start the 4 nodes in 4 terminals

Terminal 1:

```text
mvn exec:java '-Dexec.args=node 0 HONEST'
```

Terminal 2:

```text
mvn exec:java '-Dexec.args=node 1 HONEST'
```

Terminal 3:

```text
mvn exec:java '-Dexec.args=node 2 HONEST'
```

Terminal 4:

```text
mvn exec:java '-Dexec.args=node 3 HONEST'
```

Example of a Byzantine node:

```text
mvn exec:java '-Dexec.args=node 0 EQUIVOCATE_LEADER'
```

### 3. Start a client in another terminal

```text
mvn exec:java '-Dexec.args=client 100'
```

### 4. Use the interactive client

Inside the client:

```text
append hello
append second-value
send 0 direct-message
quit
```

### 5. Useful node commands

Inside a node terminal:

```text
send <destId> <message>
broadcast <message>
blockchain
quit
```

## Network Configuration

From `src/main/java/depchain/config/NetworkConfig.java`:

- `NUM_NODES = 4`
- `MAX_FAULTS = 1`
- `QUORUM_SIZE = 3`
- base port `5000`
- node ports:
  - node `0` -> `5000`
  - node `1` -> `5001`
  - node `2` -> `5002`
  - node `3` -> `5003`
- default host: `localhost`
- key directory: `keys`
- state directory: `state`

The client port is `6000 + clientId`.
Example:
- client `100` listens on port `6100`

## What Is Stored On The Blockchain

In this project, the application data being committed is a simple string, for example:

- `test-consensus-1`
- `hello`
- `second-value`

Each node keeps its own local chain as a list of decided values.

The internal `Block` object contains metadata such as:
- parent hash
- height
- view
- proposer id
- request id
- client id
- client reply address
- data string
- timestamp

## What Each Test Does

All tests are in `src/test/java/depchain/DepChainStage1Test.java`.

### `consensusAppendsToAllNodes`

- starts 4 honest nodes
- sends `test-consensus-1`
- checks that all nodes append the same decided value

### `timeoutViewChangeSurvivesLeaderCrash`

- starts only nodes `1`, `2`, and `3`
- the initial leader for view `0` is missing
- checks that timeout and leader rotation still let the request commit

### `invalidNodeSignatureIsRejected`

- starts node `2`
- sends a forged UDP message with an invalid signature
- checks that the node drops it

### `faultInjectionDropDelayDuplicateCorruptStillCommits`

- injects `drop`, `delay`, `duplicate`, and `corrupt` faults
- sends `test-faults-1`
- checks that all nodes still converge
- checks that the same request is not appended twice

### `byzantineEquivocatingLeaderStillMakesProgress`

- starts 4 nodes
- node `0` behaves as `EQUIVOCATE_LEADER`
- checks that the system still makes progress after view change

### `invalidVoteSignatureByzantineNodeDoesNotBreakConsensus`

- starts 4 nodes
- node `3` behaves as `INVALID_VOTE_SIGNATURE`
- checks that one bad voter does not stop the quorum from deciding

### `nodeStatePersistsAcrossRestart`

- starts 4 honest nodes with persistence enabled
- commits `test-persist-1`
- stops node `3`
- restarts node `3`
- checks that node `3` reloads the old chain
- commits `test-persist-2`
- checks that the cluster still converges after restart

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

## Current Scope

This repository is the Stage 1 project.

It covers:
- consensus
- communication layers
- Byzantine tolerance experiments
- persistence / restart recovery

It does not yet include a full Stage 2 transaction execution layer.
