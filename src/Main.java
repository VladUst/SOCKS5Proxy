import java.io.IOException;

import static java.lang.Integer.parseInt;

public class Main {
    private static final int DEFAULT_PORT = 4321;

    public static void main(String[] args) throws IOException {
        int port;
        if (args.length == 1) {
            port = parseInt(args[0].trim());
        }
        else{
            port = DEFAULT_PORT;
        }
        SocksServer server = new SocksServer(port);
        server.start();
    }
}
