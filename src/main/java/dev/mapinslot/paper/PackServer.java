package dev.mapinslot.paper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/** Minimaler HTTP-Server, der das aktuelle Resource Pack ausliefert. */
final class PackServer {

    private final MapInSlotPlugin plugin;
    private HttpServer server;

    PackServer(MapInSlotPlugin plugin) {
        this.plugin = plugin;
    }

    void start(String bind, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
    }

    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] data = plugin.getPackBytes();
        try {
            if (data == null) {
                ex.sendResponseHeaders(503, -1);
                return;
            }
            ex.getResponseHeaders().add("Content-Type", "application/zip");
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        } finally {
            ex.close();
        }
    }
}
