# DepChain - Dependable Blockchain System

Sistema de blockchain com elevadas garantias de confiabilidade usando o algoritmo HotStuff.

**Projeto de Sistemas de Elevada Confiabilidade 2025-2026**  
*Made by David, Mehek and Chola*

## Arquitetura de Comunicação

A comunicação entre nós usa uma stack de camadas sobre UDP:

```
HotStuff (consenso) - a implementar
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

### 2. Testar comunicação (todos os nós no mesmo processo)

```bash
mvn exec:java -Dexec.args="test"
```

### 3. Executar nós em terminais separados

Primeiro, gerar as chaves criptográficas:
```bash
mvn exec:java -Dexec.args="genkeys"
```

Depois, em 4 terminais diferentes:
```bash
# Terminal 1
mvn exec:java -Dexec.args="node 0"

# Terminal 2
mvn exec:java -Dexec.args="node 1"

# Terminal 3
mvn exec:java -Dexec.args="node 2"

# Terminal 4
mvn exec:java -Dexec.args="node 3"
```

### Comandos interativos (quando um nó está a correr)

```
send <destId> <message>   - Enviar mensagem para outro nó
broadcast <message>       - Enviar para todos os nós
blockchain                - Mostrar blockchain local
quit                      - Parar o nó
```

### 4. Ver configuração

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

## Próximos Passos (Stage 1)

- [ ] Implementar HotStuff Basic (Prepare → Pre-commit → Commit → Decide)
- [ ] Implementar rotação de líder
- [ ] Implementar NEW-VIEW para mudança de líder
- [ ] Implementar Quorum Certificates (QC)
- [ ] Implementar cliente para submeter requests
- [ ] Testes com nós Byzantine
