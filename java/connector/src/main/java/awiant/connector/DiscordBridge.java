package awiant.connector;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class DiscordBridge {

    private final String host;
    private final int port;
    private final Gson gson = new Gson();
    private final AtomicLong idGen = new AtomicLong();
    private volatile String pendingCommandId = null;
    private volatile WebSocket socket;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "discord-ws-reconnect");
        t.setDaemon(true);
        return t;
    });

    private final BlockingQueue<String> control = new LinkedBlockingQueue<>(1000);
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>(10000);
    private final Thread writer;

    public DiscordBridge(String host, int port) {
        this.host = host;
        this.port = port;
        this.writer = new Thread(this::writeLoop, "discord-ws-writer");
        this.writer.setDaemon(true);
        this.writer.start();
        connectInitial();
        scheduler.scheduleAtFixedRate(this::ensureConnected, 5, 5, TimeUnit.SECONDS);
    }

    private void connectInitial() { ensureConnected(); }

    private void ensureConnected() {
        if (socket != null && socket.isOutputClosed()) {
            socket = null;
        }
        if (socket != null) return;
        try {
            URI uri = URI.create("ws://" + host + ":" + port + "/mc");
            Connector.LOGGER.info("Connecting DiscordBridge WS to {}", uri);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            socket = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(uri, new WSListener())
                    .orTimeout(10, TimeUnit.SECONDS)
                    .join();
            Connector.LOGGER.info("DiscordBridge WS connected.");
        } catch (Exception e) {
            Connector.LOGGER.warn("DiscordBridge WS connect failed: {}", e.toString());
            socket = null;
        }
    }

    private final class WSListener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            Connector.LOGGER.info("WS open");
            ws.request(1);
            WebSocket.Listener.super.onOpen(ws);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String full = buf.toString();
                buf.setLength(0);
                handleInbound(full);
            }
            ws.request(1);
            return CompletableFuture.completedStage(null);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            Connector.LOGGER.warn("WS error: {}", error.toString());
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            Connector.LOGGER.info("WS closed: {} {}", statusCode, reason);
            socket = null;
            return CompletableFuture.completedStage(null);
        }
    }

    private void handleInbound(String text) {
        text = text.trim();
        if (text.isEmpty()) return;
        Connector.LOGGER.debug("WS recv: {}", text);
        JsonObject m;
        try {
            m = gson.fromJson(text, JsonObject.class);
        } catch (Exception ex) {
            Connector.LOGGER.warn("Bad JSON frame: {}", text);
            return;
        }
        if (m == null || !m.has("type")) {
            Connector.LOGGER.warn("Missing type: {}", text);
            return;
        }
        String type = m.get("type").getAsString();
        switch (type) {
            case "CMD" -> {
                String id = m.has("id") ? m.get("id").getAsString() : "";
                String body = m.has("body") ? m.get("body").getAsString() : "";
                onCommand(id, body);
            }
            default -> Connector.LOGGER.warn("Unknown frame type: {}", type);
        }
    }

    private void onCommand(String id, String bodyUtf8) {
        String cmd = bodyUtf8.trim();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            enqueueControl(json("type","ERR","id",id,"msg","server not ready"));
            return;
        }

        Connector.LOGGER.debug("CMD id={} body='{}'", id, cmd);
        pendingCommandId = id;

        server.execute(() -> {
            String res;
            try {
                String lower = cmd.toLowerCase();

                if (lower.startsWith("whitelist add ")) {
                    CommandHandler.addToWhitelist(server, cmd.substring("whitelist add ".length()).trim());
                    res = "ok";
                } else if (lower.startsWith("unwhitelist ")) {
                    CommandHandler.removeFromWhitelist(server, cmd.substring("unwhitelist ".length()).trim());
                    res = "ok";
                } else if (lower.startsWith("say ")) {
                    server.getPlayerList().broadcastSystemMessage(
                            Component.literal(cmd.substring("say ".length())), false);
                    res = "ok";
                } else if (lower.startsWith("kick ")) {
                    CommandHandler.kickPlayer(server, cmd.substring("kick ".length()).trim());
                    res = "ok";
                } else if (lower.startsWith("commandexec ")) {
                    List<String> r = CommandHandler.ExecuteCommand(server, cmd.substring("commandexec ".length()));
                    res = r.isEmpty() ? "ok" : r.get(0);
                } else if (lower.equals("list")) {
                    List<String> r = CommandHandler.ExecuteCommand(server, "list");
                    res = r.isEmpty() ? "ok" : r.get(0);
                } else {
                    server.getPlayerList().broadcastSystemMessage(Component.literal(cmd), false);
                    res = "ok";
                }

                Connector.LOGGER.debug("CMD id={} complete body='{}'", id, res);
                enqueueControl(json("type","RES","id",id,"body",res));

            } catch (Throwable t) {
                String msg = Optional.ofNullable(t.getMessage()).orElse("error");
                Connector.LOGGER.warn("CMD id={} error {}", id, msg, t);
                enqueueControl(json("type","ERR","id",id,"msg",msg));
            } finally {
                pendingCommandId = null;
            }
        });
    }


    private void enqueueEvent(JsonObject m) {
        // If a command response is pending, shed non-critical EVT (except status) to keep control responsive
        if (pendingCommandId != null) {
            String type = m.has("topic") ? m.get("topic").getAsString() : "";
            if (!"status".equals(type)) {
                return;
            }
        }
        String line = gson.toJson(m);
        if (!events.offer(line)) {
            Connector.LOGGER.warn("Event queue full, dropping EVT");
        }
    }

    private void enqueueControl(JsonObject m) {
        String line = gson.toJson(m);
        Connector.LOGGER.debug("Enqueue control: {}", line);
        try {
            if (!control.offer(line, 200, TimeUnit.MILLISECONDS)) {
                Connector.LOGGER.warn("Control queue saturated, dropping control frame: {}", line);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeLoop() {
        for (;;) {
            String line = null;
            try {
                WebSocket s = socket;
                if (s == null || s.isOutputClosed()) {
                    socket = null;
                    Thread.sleep(200);
                    continue;
                }

                line = control.poll(200, TimeUnit.MILLISECONDS);
                if (line == null) {
                    line = events.poll(200, TimeUnit.MILLISECONDS);
                    if (line == null) {
                        continue;
                    }
                }

                Connector.LOGGER.debug("WS send: {}", line);
                s.sendText(line, true).join();

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                Connector.LOGGER.warn("Writer error", t);
                socket = null;
                // optional: requeue line here if non-null and it's control
            }
        }
    }


    // Outbound events

    public void sendToDiscord(String message) { sendEventString("chat", message); }

    public void sendEventString(String topic, String msg) {
        enqueueEvent(json("type","EVT","topic",topic,"body",msg));
    }

    public boolean isConnected() {
        WebSocket s = socket;
        return s != null && !s.isOutputClosed();
    }

    private JsonObject json(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String k = String.valueOf(kv[i]);
            Object v = kv[i + 1];
            if (v == null) { o.addProperty(k, (String) null); continue; }
            if (v instanceof Number n) o.addProperty(k, n);
            else if (v instanceof Boolean b) o.addProperty(k, b);
            else o.addProperty(k, String.valueOf(v));
        }
        return o;
    }


    public void shutdown() {
        scheduler.shutdownNow();
        writer.interrupt();
        try { if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"); } catch (Exception ignore) {}
        socket = null;
    }
}