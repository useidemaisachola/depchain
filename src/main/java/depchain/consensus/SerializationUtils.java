package depchain.consensus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

public final class SerializationUtils {

    private SerializationUtils() {
    }

    public static byte[] serialize(Serializable value) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(value);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize value", e);
        }
    }

    public static <T> T deserialize(byte[] data, Class<T> type) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            Object value = ois.readObject();
            if (!type.isInstance(value)) {
                throw new IllegalArgumentException("Unexpected payload type: " + value.getClass().getName());
            }
            return type.cast(value);
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize value", e);
        }
    }
}
