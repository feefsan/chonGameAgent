package chon.group.game.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import chon.group.game.Game;
import chon.group.game.core.environment.Environment;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP/JSON gateway for agents running outside the game JVM.
 *
 * <p>
 * Every connecting client is given its own {@link ExternalAgentController}: the
 * first connection controls the protagonist, and each subsequent connection
 * controls the next available agent from the current level (slot {@code n}
 * maps to {@code Level.getAgents().get(n - 1)}).
 * </p>
 */
public class GameGateway implements AutoCloseable {

    private final int requestedPort;
    private final ExternalJoystick protagonistJoystick;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final CopyOnWriteArrayList<ClientSession> connectedClients = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ExternalAgentController> controllers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean protagonistClaimed = new AtomicBoolean(false);
    private final AtomicInteger nextBotSlot = new AtomicInteger(1);
    private volatile boolean running;
    private ServerSocket serverSocket;
    private volatile String latestObservation;

    public GameGateway(int port, ExternalJoystick protagonistJoystick) {
        this.requestedPort = port;
        this.protagonistJoystick = protagonistJoystick;
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
        ExternalAgentController control = assignController();
        controllers.add(control);
        try (socket;
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            client = new ClientSession(writer);
            connectedClients.add(client);
            client.sendControl(helloMessage(control));
            if (latestObservation != null) {
                client.offerObservation(latestObservation);
            }
            clients.submit(client::writeLatestObservation);
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonNode message = mapper.readTree(line);
                    if ("action".equals(message.path("type").asText())) {
                        control.getActionQueue().offer(toGameAction(message));
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
            /* The game thread finishes releasing the bound agent and frees the slot. */
            control.close();
        }
    }

    /** Assigns the protagonist to the first client and the next free bot slot to every other. */
    private ExternalAgentController assignController() {
        if (protagonistClaimed.compareAndSet(false, true)) {
            return new ExternalAgentController(0, protagonistJoystick);
        }
        int slot = nextBotSlot.getAndIncrement();
        return new ExternalAgentController(slot, new ExternalJoystick());
    }

    private String helloMessage(ExternalAgentController control) {
        String role = control.isProtagonist() ? "protagonist" : ("agent-" + control.getSlot());
        return "{\"type\":\"hello\",\"protocolVersion\":1,\"controls\":\"" + role + "\"}";
    }

    /**
     * Applies queued actions to each client's own joystick. Must only be called
     * from the game thread.
     */
    public void processPendingActions(long currentTick) {
        for (ExternalAgentController control : controllers) {
            GameActionQueue queue = control.getActionQueue();
            GameAction action;
            while ((action = queue.poll()) != null) {
                if (action.expectedTick() >= 0 && action.expectedTick() > currentTick) {
                    queue.offer(action);
                    break;
                }
                applyExternalAction(control.getJoystick(), action);
            }
        }
    }

    /**
     * Moves every bot bound to a connected client and reclaims controllers whose
     * client disconnected. Must only be called from the game thread.
     */
    public void updateControlledAgents(Game game) {
        Environment environment = game.getEnvironment();
        if (environment.getCurrentLevel() != null) {
            environment.getCurrentLevel().getAgents().forEach(agent -> {
                if (!agent.isDead()) {
                    agent.setExternallyControlled(true);
                    agent.idle();
                }
            });
        }
        for (ExternalAgentController control : controllers) {
            if (control.isClosed()) {
                if (control.isProtagonist()) {
                    protagonistClaimed.set(false);
                    control.getJoystick().clear();
                } else {
                    control.release();
                }
                controllers.remove(control);
                continue;
            }
            control.update(environment);
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

    private void applyExternalAction(ExternalJoystick joystick, GameAction action) {
        String name = action.name().toUpperCase();
        switch (name) {
            case "MOVE" -> applyExternalMovement(joystick, action.direction());
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

    private void applyExternalMovement(ExternalJoystick joystick, String direction) {
        if (direction == null) {
            return;
        }
        try {
            GameCommand command = GameCommand.valueOf(direction.toUpperCase());
            joystick.hold(command);
            releaseOtherDirections(joystick, command);
        } catch (IllegalArgumentException exception) {
            // Invalid actions are ignored by the external joystick.
        }
    }

    private void releaseOtherDirections(ExternalJoystick joystick, GameCommand activeCommand) {
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