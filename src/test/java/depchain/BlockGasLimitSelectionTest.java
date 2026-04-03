package depchain;

import depchain.blockchain.Transaction;
import depchain.client.ClientRequest;
import depchain.config.NetworkConfig;
import depchain.crypto.KeyManager;
import depchain.node.ByzantineBehavior;
import depchain.node.Node;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BlockGasLimitSelectionTest {

    private static final SecureRandom RNG = new SecureRandom();

    @Test
    void oversizedTransactionDoesNotBlockOtherSenders() throws Exception {
        long blockGasLimit = 50_000L;

        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(NetworkConfig.NUM_NODES);
        Path stateDirectory = Files.createTempDirectory("depchain-blockgas-test-");

        Node node = new Node(
            0,
            keyManagers.get(0),
            ByzantineBehavior.HONEST,
            stateDirectory.toString(),
            false,
            blockGasLimit
        );

        try {
            Address senderA = randomAddress();
            Address senderB = randomAddress();
            Address recipient = randomAddress();

            // Sender A: head tx does not fit in any block (gasLimit > blockGasLimit).
            Transaction a0 = Transaction.create(senderA, recipient, Wei.of(1), Bytes.EMPTY, 1L, 60_000L, 0);
            Transaction a1 = Transaction.create(senderA, recipient, Wei.of(1), Bytes.EMPTY, 1L, 21_000L, 1);

            // Sender B: normal tx that fits.
            Transaction b0 = Transaction.create(senderB, recipient, Wei.of(1), Bytes.EMPTY, 1L, 21_000L, 0);

            ClientRequest reqA0 = wrapAsRequest(a0);
            ClientRequest reqA1 = wrapAsRequest(a1);
            ClientRequest reqB0 = wrapAsRequest(b0);

            assertTrue(addToPendingPool(node, reqA0, a0));
            assertTrue(addToPendingPool(node, reqA1, a1));
            assertTrue(addToPendingPool(node, reqB0, b0));

            List<ClientRequest> selected = selectRequestsForNextBlock(node);

            // Sender A cannot contribute because its head (nonce 0) cannot fit.
            // Sender B must still be selected.
            assertEquals(1, selected.size(), "Only sender B's tx should be selected under the gas cap");
            Transaction first = decodeTx(selected.get(0));
            assertEquals(senderB, first.getFrom(), "Selected tx must come from sender B");
            assertTrue(first.getGasLimit() <= blockGasLimit);
        } finally {
            try { node.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    void selectionRespectsBlockGasLimitAcrossMultipleNonces() throws Exception {
        long blockGasLimit = 50_000L;

        Map<Integer, KeyManager> keyManagers = KeyManager.generateInMemory(NetworkConfig.NUM_NODES);
        Path stateDirectory = Files.createTempDirectory("depchain-blockgas-test-");

        Node node = new Node(
            0,
            keyManagers.get(0),
            ByzantineBehavior.HONEST,
            stateDirectory.toString(),
            false,
            blockGasLimit
        );

        try {
            Address sender = randomAddress();
            Address recipient = randomAddress();

            // Three consecutive transactions; only the first two can fit (21k + 21k = 42k; 3rd would exceed 50k).
            Transaction tx0 = Transaction.create(sender, recipient, Wei.ZERO, Bytes.EMPTY, 1L, 21_000L, 0);
            Transaction tx1 = Transaction.create(sender, recipient, Wei.ZERO, Bytes.EMPTY, 1L, 21_000L, 1);
            Transaction tx2 = Transaction.create(sender, recipient, Wei.ZERO, Bytes.EMPTY, 1L, 21_000L, 2);

            assertTrue(addToPendingPool(node, wrapAsRequest(tx0), tx0));
            assertTrue(addToPendingPool(node, wrapAsRequest(tx1), tx1));
            assertTrue(addToPendingPool(node, wrapAsRequest(tx2), tx2));

            List<ClientRequest> selected = selectRequestsForNextBlock(node);

            assertEquals(2, selected.size(), "Block selection must respect the cumulative gas cap");

            Transaction s0 = decodeTx(selected.get(0));
            Transaction s1 = decodeTx(selected.get(1));

            assertEquals(0L, s0.getNonce());
            assertEquals(1L, s1.getNonce());
            assertEquals(21_000L, s0.getGasLimit());
            assertEquals(21_000L, s1.getGasLimit());

            long totalGas = s0.getGasLimit() + s1.getGasLimit();
            assertTrue(totalGas <= blockGasLimit);
        } finally {
            try { node.close(); } catch (Exception ignored) {}
        }
    }

    // ---------------------------------------------------------------------
    // Reflection helpers (keep production APIs unchanged)
    // ---------------------------------------------------------------------

    private static boolean addToPendingPool(Node node, ClientRequest request, Transaction tx) throws Exception {
        Method m = Node.class.getDeclaredMethod("addToPendingPoolLocked", ClientRequest.class, Transaction.class);
        m.setAccessible(true);
        return (boolean) m.invoke(node, request, tx);
    }

    @SuppressWarnings("unchecked")
    private static List<ClientRequest> selectRequestsForNextBlock(Node node) throws Exception {
        Method m = Node.class.getDeclaredMethod("selectRequestsForNextBlockLocked");
        m.setAccessible(true);
        return (List<ClientRequest>) m.invoke(node);
    }

    private static ClientRequest wrapAsRequest(Transaction tx) {
        String data = Base64.getEncoder().encodeToString(tx.serialize());
        return new ClientRequest(
            0,
            UUID.randomUUID().toString(),
            data,
            System.currentTimeMillis(),
            "localhost",
            0,
            new byte[32],
            new byte[64]
        );
    }

    private static Transaction decodeTx(ClientRequest request) {
        byte[] bytes = Base64.getDecoder().decode(request.getData());
        return Transaction.deserialize(bytes);
    }

    private static Address randomAddress() {
        byte[] bytes = new byte[20];
        RNG.nextBytes(bytes);
        return Address.wrap(org.apache.tuweni.bytes.Bytes.wrap(bytes));
    }
}
