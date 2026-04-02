package depchain.blockchain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Persists committed blocks to disk and loads/validates the chain on restart.
 *
 * <p>Each block is stored as a JSON file named {@code block_NNNNNN.json}
 * (zero-padded height) inside the configured directory. Example:
 * <pre>  state/blocks/block_000000.json   ← genesis
 *   state/blocks/block_000001.json   ← first consensus block
 *   ...</pre>
 *
 * <p>Chain integrity is verified by checking that every block's
 * {@code previous_block_hash} matches the preceding block's {@code block_hash},
 * and that every block's hash can be recomputed from its declared content.
 */
public class BlockStore {

    private final Path directory;

    public BlockStore(String directory) {
        this.directory = Paths.get(directory);
    }

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    /**
     * Persists {@code block} to {@code block_NNNNNN.json}.
     * Creates the directory if it does not yet exist.
     *
     * @throws RuntimeException on I/O error
     */
    public void save(PersistedBlock block) {
        try {
            Files.createDirectories(directory);
            String filename = String.format("block_%06d.json", block.getHeight());
            Files.writeString(directory.resolve(filename), block.toJson());
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist block " + block.getHeight(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * Loads all persisted blocks in ascending height order.
     *
     * @return ordered list (empty if the directory does not exist)
     * @throws RuntimeException on I/O error
     */
    public List<PersistedBlock> loadChain() {
        if (!Files.exists(directory)) {
            return new ArrayList<>();
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(p -> p.getFileName().toString().matches("block_\\d{6}\\.json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(p -> {
                        try {
                            return PersistedBlock.fromJson(Files.readString(p));
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read block file: " + p, e);
                        }
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Failed to list block-store directory: " + directory, e);
        }
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    /**
     * Validates hash-chain integrity for {@code chain}.
     *
     * <p>Checks:
     * <ol>
     *   <li>First block has {@code height == 0}.</li>
     *   <li>Heights are consecutive (no gaps).</li>
     *   <li>Each block's {@code block_hash} matches the recomputed hash of its content.</li>
     *   <li>Each block's {@code previous_block_hash} equals the preceding block's {@code block_hash}.</li>
     * </ol>
     *
     * @return {@code true} if the chain is intact; {@code false} on any violation
     */
    public boolean validateChain(List<PersistedBlock> chain) {
        if (chain.isEmpty()) {
            return true;
        }

        PersistedBlock genesis = chain.get(0);
        if (genesis.getHeight() != 0) {
            return false;
        }
        if (!genesis.getBlockHash().equals(recomputeHash(genesis))) {
            return false;
        }

        for (int i = 1; i < chain.size(); i++) {
            PersistedBlock prev = chain.get(i - 1);
            PersistedBlock curr = chain.get(i);

            if (curr.getHeight() != prev.getHeight() + 1) {
                return false;
            }
            if (!curr.getBlockHash().equals(recomputeHash(curr))) {
                return false;
            }
            if (!curr.getPreviousBlockHash().equals(prev.getBlockHash())) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Re-creates a block from its data fields (triggering hash recomputation) and returns the hash. */
    private static String recomputeHash(PersistedBlock block) {
        return new PersistedBlock(
                block.getPreviousBlockHash(),
                block.getHeight(),
                block.getTransactions(),
                block.getWorldState()
        ).getBlockHash();
    }
}
