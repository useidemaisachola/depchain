package depchain;

import depchain.net.Message;
import depchain.node.Node;

public class App {

    public static void main(String[] args) {
        if (args.length == 0) {
            return; // print usage
        }
        String mode = (String) args[0];
        String id = (String) args[1];
        int port = Integer.parseInt(args[2]);
        switch (mode) {
            case "listen":
                Node node = new Node(id, port);
                node.start();
                break;
            case "send":
                Node client = new Node(id, port);
                Message m = new Message(args[3]);
                int dest = Integer.parseInt(args[4]);

                client.send("localhost", dest, m.getPayload());
                break;
            default:
                System.out.println("Unknown mode: " + mode);
                break;
        }
    }
}
