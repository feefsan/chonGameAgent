package chon.group.game.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import chon.group.game.joystick.GameCommand;
import chon.group.game.joystick.client.ExternalJoystick;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;

/** TCP/JSON gateway for agents running outside the game JVM. */
public class GameGateway implements AutoCloseable {

    private final int requestedPort;
    private final ExternalJoystick joystick;
    private final ObjectMapper mapper = new ObjectMapper();
    private final GameActionQueue actionQueue = new GameActionQueue();
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final CopyOnWriteArrayList<ClientSession> connectedClients = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private volatile String latestObservation;

    public GameGateway(int port, ExternalJoystick joystick) {
        this.requestedPort = port;
        this.joystick = joystick;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(requestedPort);
        running = true;
        Thread acceptor = new Thread(this::acceptClients, "chon-game-gateway");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int getPort() {
        return serverSocket == null ? requestedPort : serverSocket.getLocalPort();
    }

    public void publish(GameSnapshotBuilder.GameSnapshot snapshot) {
        String message;
        try {
            message = mapper.writeValueAsString(new ObservationMessage(snapshot));
        } catch (IOException exception) {
            exception.printStackTrace();
            return;
        }
        for (ClientSession client : connectedClients) {
            client.offerObservation(message);
        }
        latestObservation = message;
    }

    private void acceptClients() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                clients.submit(() -> handleClient(socket));
            } catch (IOException exception) {
                if (running) {
                    exception.printStackTrace();
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        ClientSession client = null;
        try (socket;
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            client = new ClientSession(writer);
            connectedClients.add(client);
            client.sendControl("{\"type\":\"hello\",\"protocolVersion\":1}");
            if (latestObservation != null) {
                client.offerObservation(latestObservation);
            }
            clients.submit(client::writeLatestObservation);
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonNode message = mapper.readTree(line);
                    if ("action".equals(message.path("type").asText())) {
                        actionQueue.offer(toGameAction(message));
                        client.sendControl("{\"type\":\"action_ack\",\"accepted\":true}");
                    } else {
                        client.sendControl("{\"type\":\"error\",\"message\":\"Unsupported message type\"}");
                    }
                } catch (IOException exception) {
                    client.sendControl("{\"type\":\"error\",\"message\":\"Invalid JSON\"}");
                }
            }
        } catch (SocketException exception) {
            if (running && !"Connection reset".equals(exception.getMessage())) {
                exception.printStackTrace();
            }
        } catch (IOException exception) {
            if (running) {
                exception.printStackTrace();
            }
        } finally {
            if (client != null) {
                connectedClients.remove(client);
                client.close();
            }
        }
    }

    private GameAction toGameAction(JsonNode message) {
        JsonNode action = message.path("action");
        return new GameAction(
                message.path("requestId").asText(""),
                message.path("agentId").asText(""),
                message.path("expectedTick").asLong(-1),
                action.path("name").asText(""),
                action.path("direction").asText(null));
    }

    public void processPendingActions(long currentTick) {
        GameAction action;
        while ((action = actionQueue.poll()) != null) {
            if (action.expectedTick() >= 0 && action.expectedTick() > currentTick) {
                actionQueue.offer(action);
                break;
            }
            applyExternalAction(action);
        }
    }

    private void applyExternalAction(GameAction action) {
        String name = action.name().toUpperCase();
        switch (name) {
            case "MOVE" -> applyExternalMovement(action.direction());
            case "ATTACK" -> joystick.press(GameCommand.ATTACK);
            case "CONFIRM" -> joystick.press(GameCommand.CONFIRM);
            case "PAUSE" -> joystick.press(GameCommand.PAUSE);
            case "MENU_UP", "UP" -> joystick.press(GameCommand.UP);
            case "MENU_DOWN", "DOWN" -> joystick.press(GameCommand.DOWN);
            case "MENU_LEFT", "LEFT" -> joystick.press(GameCommand.LEFT);
            case "MENU_RIGHT", "RIGHT" -> joystick.press(GameCommand.RIGHT);
            default -> {
            }
        }
    }

    private void applyExternalMovement(String direction) {
        if (direction == null) {
            return;
        }
        try {
            GameCommand command = GameCommand.valueOf(direction.toUpperCase());
            joystick.hold(command);
            releaseOtherDirections(command);
        } catch (IllegalArgumentException exception) {
            // Invalid actions are ignored by the external joystick.
        }
    }

    private void releaseOtherDirections(GameCommand activeCommand) {
        for (GameCommand direction : new GameCommand[] {
                GameCommand.UP, GameCommand.DOWN, GameCommand.LEFT, GameCommand.RIGHT }) {
            if (direction != activeCommand) {
                joystick.release(direction);
            }
        }
    }

    private record ObservationMessage(String type, int protocolVersion,
            GameSnapshotBuilder.GameSnapshot payload) {
        private ObservationMessage(GameSnapshotBuilder.GameSnapshot payload) {
            this("observation", 1, payload);
        }
    }

    private static class ClientSession {
        private final BufferedWriter writer;
        private final Object observationMonitor = new Object();
        private volatile String latestObservation;
        private volatile boolean closed;

        private ClientSession(BufferedWriter writer) {
            this.writer = writer;
        }

        private void sendControl(String message) throws IOException {
            synchronized (writer) {
                writer.write(message);
                writer.write('\n');
                writer.flush();
            }
        }

        private void offerObservation(String message) {
            synchronized (observationMonitor) {
                latestObservation = message;
                observationMonitor.notifyAll();
            }
        }

        private void writeLatestObservation() {
            try {
                while (!closed) {
                    String message;
                    synchronized (observationMonitor) {
                        while (!closed && latestObservation == null) {
                            observationMonitor.wait();
                        }
                        if (closed) {
                            return;
                        }
                        message = latestObservation;
                        latestObservation = null;
                    }
                    synchronized (writer) {
                        writer.write(message);
                        writer.write('\n');
                        writer.flush();
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                // The reader closes the session when the remote client disconnects.
            }
        }

        private void close() {
            closed = true;
            synchronized (observationMonitor) {
                observationMonitor.notifyAll();
            }
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        clients.close();
        if (serverSocket != null) {
            serverSocket.close();
        }
    }
}