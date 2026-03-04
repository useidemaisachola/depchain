package depchain;

import depchain.net.Message;
import depchain.node.Node;

public class App {

    public static void main(String[] args) {
        if (args.length == 0) {
            return;
        }
        String mode = (String) args[0];
        switch (mode) {
            case "listen":
                Node node = new Node("1", 8081);
                node.start();
                break;
            case "send":
                Node client = new Node("2", 8080);
                Message m = new Message("Oi");
                client.send("localhost", 8081, m.getContent());
                break;
            default:
                System.out.println("Unknown mode: " + mode);
                break;
        }
    }
}
