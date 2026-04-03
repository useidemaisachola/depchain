package depchain;

import depchain.blockchain.Transaction;
import depchain.client.ClientRequest;
import depchain.node.Node;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for transaction fee ordering.
 *
 * Verifies that pending transactions are ordered by gasPrice × gasLimit
 * (descending) so that higher-fee transactions are proposed first.
 */
class TransactionOrderingTest {

    private static final Address SENDER =
            Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final Address RECEIVER =
            Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

    // -------------------------------------------------------------------------
    // extractFee
    // -------------------------------------------------------------------------

    @Test
    void extractFee_returnsGasPriceTimesGasLimit() {
        Transaction tx = Transaction.create(SENDER, RECEIVER, Wei.ZERO,
                Bytes.EMPTY, 10L, 21_000L, 0);
        ClientRequest req = encodeAsRequest(tx);

        assertEquals(10L * 21_000L, Node.extractFee(req));
    }

    @Test
        void extractFee_returnsZeroForEmptyData() {
                // Not expected in Stage 2 flows (transaction-only), but extractFee must be robust.
                ClientRequest req = new ClientRequest(1, UUID.randomUUID().toString(), "",
                                System.currentTimeMillis(), "localhost", 9999,
                                new byte[32], new byte[64]);
                assertEquals(0L, Node.extractFee(req));
        }

    // -------------------------------------------------------------------------
    // Ordering
    // -------------------------------------------------------------------------

    @Test
    void pendingQueueOrdersTransactionsByFeeDescending() {
        // Three transactions with different fees.
        Transaction txLow  = Transaction.create(SENDER, RECEIVER, Wei.ZERO,
                Bytes.EMPTY, 1L,   21_000L, 0); // fee = 21 000
        Transaction txHigh = Transaction.create(SENDER, RECEIVER, Wei.ZERO,
                Bytes.EMPTY, 100L, 21_000L, 1); // fee = 2 100 000
        Transaction txMid  = Transaction.create(SENDER, RECEIVER, Wei.ZERO,
                Bytes.EMPTY, 10L,  21_000L, 2); // fee = 210 000

        ClientRequest reqLow  = encodeAsRequest(txLow);
        ClientRequest reqHigh = encodeAsRequest(txHigh);
        ClientRequest reqMid  = encodeAsRequest(txMid);

        // Same comparator used by Node.
        PriorityQueue<ClientRequest> queue = new PriorityQueue<>(
                Comparator.<ClientRequest>comparingLong(r -> Node.extractFee(r)).reversed());

        // Add in ascending fee order to confirm ordering is not insertion-order.
        queue.offer(reqLow);
        queue.offer(reqHigh);
        queue.offer(reqMid);

        assertEquals(reqHigh.getRequestId(), queue.poll().getRequestId(),
                "Highest-fee transaction must be proposed first");
        assertEquals(reqMid.getRequestId(), queue.poll().getRequestId(),
                "Mid-fee transaction must be proposed second");
        assertEquals(reqLow.getRequestId(), queue.poll().getRequestId(),
                "Lowest-fee transaction must be proposed last");
    }

    @Test
    void pendingQueueWithEqualFeesPreservesAllEntries() {
        // Equal fees → all three must still be present (no de-duplication).
        Transaction tx1 = Transaction.create(SENDER, RECEIVER, Wei.ZERO,
                Bytes.EMPTY, 5L, 21_000L, 0);
        Transaction tx2 = Transaction.create(SENDER, RECEIVER, Wei.ZERO,
                Bytes.EMPTY, 5L, 21_000L, 1);
        Transaction tx3 = Transaction.create(SENDER, RECEIVER, Wei.ZERO,
                Bytes.EMPTY, 5L, 21_000L, 2);

        PriorityQueue<ClientRequest> queue = new PriorityQueue<>(
                Comparator.<ClientRequest>comparingLong(r -> Node.extractFee(r)).reversed());
        queue.offer(encodeAsRequest(tx1));
        queue.offer(encodeAsRequest(tx2));
        queue.offer(encodeAsRequest(tx3));

        assertEquals(3, queue.size());
        // All should have the same fee.
        long fee1 = Node.extractFee(queue.poll());
        long fee2 = Node.extractFee(queue.poll());
        long fee3 = Node.extractFee(queue.poll());
        assertEquals(5L * 21_000L, fee1);
        assertEquals(fee1, fee2);
        assertEquals(fee2, fee3);
    }

    @Test
    void highGasLimitHighPriorityOverHighGasPrice() {
        // fee = gasPrice * gasLimit; product determines order, not either alone.
        Transaction txHighPrice = Transaction.create(SENDER, RECEIVER, Wei.ZERO,
                Bytes.EMPTY, 1000L, 21_000L,  0); // fee = 21 000 000
        Transaction txHighLimit = Transaction.create(SENDER, RECEIVER, Wei.ZERO,
                Bytes.EMPTY, 1L,   30_000_000L, 1); // fee = 30 000 000

        PriorityQueue<ClientRequest> queue = new PriorityQueue<>(
                Comparator.<ClientRequest>comparingLong(r -> Node.extractFee(r)).reversed());
        queue.offer(encodeAsRequest(txHighPrice));
        queue.offer(encodeAsRequest(txHighLimit));

        Transaction first = decodeTransaction(queue.poll());
        assertEquals(30_000_000L, first.getGasLimit(),
                "Transaction with higher gasPrice*gasLimit must come first");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Wraps a Transaction in a ClientRequest by Base64-encoding its serialized
     * bytes — the same encoding that Node.extractFee expects.
     */
    static ClientRequest encodeAsRequest(Transaction tx) {
        String data = Base64.getEncoder().encodeToString(tx.serialize());
        return new ClientRequest(1, UUID.randomUUID().toString(), data,
                System.currentTimeMillis(), "localhost", 9999,
                new byte[32], new byte[64]);
    }

    private static Transaction decodeTransaction(ClientRequest req) {
        byte[] bytes = Base64.getDecoder().decode(req.getData());
        return Transaction.deserialize(bytes);
    }
}
