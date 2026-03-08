# DepChain Stage 1 Report (LNCS Draft Content)

## 1. Context and Scope
DepChain Stage 1 implements a permissioned blockchain consensus layer based on Basic HotStuff under static membership (`n=4`, `f=1`).  
The target of this stage is agreement and ordering of client append requests under crash and Byzantine threats.

## 2. Threat Model
- Byzantine blockchain members may deviate arbitrarily (silence, equivocation, malformed signatures).
- Network is unreliable and insecure: loss, delay, duplication, and corruption are possible.
- Client library is trusted by its local application process.
- Membership and node public keys are pre-distributed (static PKI).

## 3. Architecture and Design Decisions
Communication is layered:
- `UDP -> FL -> SL -> PL -> APL -> HotStuff Node Logic`.

Main decisions:
- `SL` performs periodic retransmission.
- `PL` deduplicates and ACKs to stop retransmission.
- `APL` enforces node signature verification.
- HotStuff phases implemented:
  - `PREPARE -> PRE_COMMIT -> COMMIT -> DECIDE`.
- Quorum Certificates are built from `2f+1` signed votes.
- View change is timeout-based, with `NEW_VIEW` and leader rotation `leader = view % n`.
- Client requests include signed metadata (`requestId`, timestamp, reply address, client public key).

## 4. Security and Dependability Mechanisms
- Node-to-node authenticity: RSA signatures and verification at `APL`.
- Client request authenticity: request-body signature checked against embedded client public key.
- Duplicate suppression and retransmission: `PL` + `SL`.
- Byzantine behavior handling:
  - invalid node signatures are dropped,
  - equivocation by a leader causes view change and progress under an honest next leader.
- Persistence/recovery:
  - node view and committed application state are persisted and reloaded on restart.

## 5. Guarantees
Under the Stage 1 assumptions:
- Safety:
  - messages without valid node signatures are rejected,
  - decisions only happen after quorum-certified phase progression.
- Liveness:
  - with eventual synchrony and at least `2f+1` correct nodes, requests commit after timeout-based leader change if needed.
- Recovery:
  - restarted nodes recover committed chain/view from persisted state.

## 6. Validation and Intrusive Testing
Automated tests include:
- base consensus replication,
- failover after initial leader crash,
- rejection of forged node signatures,
- intrusive fault injection (`drop`, `delay`, `duplicate`, `corrupt`) with eventual commit,
- Byzantine leader equivocation with eventual progress,
- Byzantine invalid vote signatures tolerated,
- node restart and state recovery.

Run:
```bash
mvn clean test
```

## 7. Demo Commands
- Baseline demo:
```bash
mvn exec:java -Dexec.args="test"
```
- Fault injection demo:
```bash
mvn exec:java -Dexec.args="demo_faults"
```
- Byzantine leader demo:
```bash
mvn exec:java -Dexec.args="demo_byz"
```
- Persistence/restart demo:
```bash
mvn exec:java -Dexec.args="demo_persist"
```

## 8. Limitations and Stage 2 Work
- No full transaction execution layer yet (Stage 2 scope).
- No dynamic membership/reconfiguration.
- No threshold signature aggregation (currently per-node signature maps in QCs).
