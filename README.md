# DepChain - Dependable Blockchain System

Sistema de blockchain com elevadas garantias de confiabilidade usando o algoritmo HotStuff.

**Projeto de Sistemas de Elevada Confiabilidade 2025-2026**  
*Made by David, Mehek and Chola*

## Arquitetura de Comunicação

A comunicação entre nós usa uma stack de camadas sobre UDP:

```
HotStuff Basic (consenso)
      ↓
    APL  (Authenticated Perfect Links - assinaturas digitais)
      ↓
    PL   (Perfect Links - sem duplicações)
      ↓
    SL   (Stubborn Links - retransmissão)
      ↓
    FL   (Fair Loss Links)
      ↓
    UDP
```

### Camadas implementadas:

- **FL (Fair Loss Links)**: Encapsula UDP, serializa/deserializa mensagens
- **SL (Stubborn Links)**: Retransmite mensagens periodicamente até serem confirmadas
- **PL (Perfect Links)**: Elimina duplicados usando ACKs e tracking de mensagens
- **APL (Authenticated Perfect Links)**: Adiciona assinaturas digitais RSA
- **HotStuff Basic**: PREPARE → PRE_COMMIT → COMMIT → DECIDE com QCs (2f+1 votos)
- **View Change**: timeout + `NEW_VIEW` com rotação de líder (`leader = view % n`)
- **Fault Injector (intrusive tests)**: regras de DROP / DELAY / DUPLICATE / CORRUPT no FL
- **Byzantine Modes**: `SILENT`, `EQUIVOCATE_LEADER`, `INVALID_VOTE_SIGNATURE`
- **Persistência de Estado**: view + cadeia + requests decididos persistidos em disco

## Configuração da Rede

- **n = 4 nós** (n = 3f + 1, onde f = 1)
- **f = 1** (tolerância a 1 nó Bizantino)
- **quorum = 3** (2f + 1)
- Portas: 5000, 5001, 5002, 5003

## Como Executar

### 1. Compilar o projeto

```bash
mvn clean compile
```

### 2. Demo local (todos os nós no mesmo processo)

```bash
mvn exec:java -Dexec.args="test"
```

### 2.1 Demo de fault injection

```bash
mvn exec:java -Dexec.args="demo_faults"
```

### 2.2 Demo de líder bizantino (equivocation)

```bash
mvn exec:java -Dexec.args="demo_byz"
```

### 2.3 Demo de persistência/restart

```bash
mvn exec:java -Dexec.args="demo_persist"
```

### 3. Testes automáticos

```bash
mvn test
```

### 4. Executar nós em terminais separados

Primeiro, gerar as chaves criptográficas:
```bash
mvn exec:java -Dexec.args="genkeys"
```

Depois, em 4 terminais diferentes:
```bash
# Terminal 1
mvn exec:java -Dexec.args="node 0 HONEST"

# Terminal 2
mvn exec:java -Dexec.args="node 1 HONEST"

# Terminal 3
mvn exec:java -Dexec.args="node 2 HONEST"

# Terminal 4
mvn exec:java -Dexec.args="node 3 HONEST"
```

Exemplo de nó bizantino:

```bash
mvn exec:java -Dexec.args="node 0 EQUIVOCATE_LEADER"
```

### Comandos interativos (quando um nó está a correr)

```
send <destId> <message>   - Enviar mensagem para outro nó
broadcast <message>       - Enviar para todos os nós
blockchain                - Mostrar blockchain local
quit                      - Parar o nó
```

### 5. Ver configuração

```bash
mvn exec:java -Dexec.args="config"
```

## Estrutura do Projeto

```
src/main/java/depchain/
├── App.java                    # Ponto de entrada
├── config/
│   └── NetworkConfig.java      # Configuração estática da rede (4 nós)
├── crypto/
│   ├── CryptoUtils.java        # Utilidades criptográficas (RSA, SHA-256)
│   └── KeyManager.java         # Gestão de chaves PKI
├── net/
│   ├── Message.java            # Classe de mensagem serializable
│   ├── MessageType.java        # Tipos de mensagem
│   ├── UdpTransport.java       # Transporte UDP base
│   ├── FairLossLinks.java      # FL - camada fair loss
│   ├── StubbornLinks.java      # SL - camada stubborn (retransmissão)
│   ├── PerfectLinks.java       # PL - camada perfect (sem duplicados)
│   └── AuthenticatedPerfectLinks.java  # APL - camada autenticada
└── node/
    └── Node.java               # Nó da blockchain
```

Relatório Stage 1 (draft LNCS content):
- `REPORT_STAGE1_LNCS.md`

## Estado Atual

- [x] HotStuff Basic com fases PREPARE/PRE_COMMIT/COMMIT/DECIDE
- [x] Quorum Certificates (QC) com 2f+1 assinaturas
- [x] Biblioteca cliente com requests assinados e replies de confirmação
- [x] Timeout-based failure detector e `NEW_VIEW`
- [x] Testes automáticos (consenso, failover, assinatura inválida)
- [x] Testes intrusivos com fault injection (drop/delay/duplicate/corrupt)
- [x] Testes Byzantine (equivocation e assinatura inválida)
- [x] Persistência/recovery de estado com teste de restart

## Próximos Passos (Stage 2)

- [ ] Camada transacional completa sobre o consenso
- [ ] Camada transacional/execução de Stage 2
- [ ] Métricas/benchmarking de latência e throughput
