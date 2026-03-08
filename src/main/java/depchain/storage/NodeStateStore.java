package depchain.storage;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class NodeStateStore {

    private final Path stateFile;

    public NodeStateStore(int nodeId, String stateDirectory) {
        this.stateFile = Paths.get(stateDirectory).resolve("node" + nodeId + ".state");
    }

    public synchronized Optional<NodePersistentState> load() {
        if (!Files.exists(stateFile)) {
            return Optional.empty();
        }
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(stateFile))) {
            Object value = in.readObject();
            if (value instanceof NodePersistentState) {
                return Optional.of((NodePersistentState) value);
            }
            return Optional.empty();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to load node state from " + stateFile, e);
        }
    }

    public synchronized void save(NodePersistentState state) {
        try {
            Files.createDirectories(stateFile.getParent());
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(stateFile))) {
                out.writeObject(state);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save node state to " + stateFile, e);
        }
    }

    public synchronized void clear() {
        try {
            Files.deleteIfExists(stateFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to clear node state file " + stateFile, e);
        }
    }

    public Path getStateFile() {
        return stateFile;
    }
}
