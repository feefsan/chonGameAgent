package chon.group;

import chon.group.game.Game;
import chon.group.game.drawer.client.JavaFxDrawer;
import chon.group.game.drawer.service.GameDrawer;
import chon.group.game.drawer.service.GameMediator;
import chon.group.game.joystick.client.JavaFxJoystick;
import chon.group.game.joystick.client.ExternalJoystick;
import chon.group.game.joystick.client.Joystick;
import chon.group.game.joystick.service.JoystickMediator;
import chon.group.game.joystick.service.GameJoystick;
import chon.group.game.loader.GameSet;
import chon.group.game.sound.client.JavaFxPlayer;
import chon.group.game.sound.service.GameSoundManager;
import chon.group.game.gateway.GameGateway;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * The {@code Engine} class represents the main entry point of the application
 * and serves as the game engine for "Chon: The Learning Game."
 */
public class Engine extends Application {

    private static final boolean USE_EXTERNAL_JOYSTICK = true;
    private final chon.group.game.gateway.GameSnapshotBuilder snapshotBuilder = new chon.group.game.gateway.GameSnapshotBuilder();

    /**
     * Main entry point of the application.
     *
     * @param args command-line arguments passed to the application.
     */
    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage theStage) {
        try {
            GameSet gameSet = new GameSet();

            /* Set up the graphical canvas */
            Canvas canvas = new Canvas(gameSet.getCanvasWidth(), gameSet.getCanvasHeight());
            GraphicsContext gc = canvas.getGraphicsContext2D();

            /* Set up the scene and stage */
            StackPane root = new StackPane();
            Scene scene = new Scene(root, gameSet.getCanvasWidth(), gameSet.getCanvasHeight());
            theStage.setTitle("Chon: The Learning Game");
            theStage.setScene(scene);

            root.getChildren().add(canvas);

            /* Set up exactly one input source for the game. */
            Joystick joystickClient;
            ExternalJoystick externalJoystick = null;
            if (USE_EXTERNAL_JOYSTICK) {
                externalJoystick = new ExternalJoystick();
                joystickClient = externalJoystick;
            } else {
                joystickClient = new JavaFxJoystick(scene);
            }
            GameJoystick joystick = new JoystickMediator(joystickClient);

            GameSoundManager soundManager = new GameSoundManager(new JavaFxPlayer());
            GameDrawer mediator = new GameMediator(new JavaFxDrawer(gc));

            Game chonGame = new Game(
                    gameSet.getEnvironment(),
                    soundManager,
                    mediator,
                    gameSet.getMenu(),
                    joystick,
                    0);

            final GameGateway gateway = externalJoystick == null
                    ? null
                    : new GameGateway(8765, externalJoystick);
            if (gateway != null) {
                gateway.start();
            }

            // Start the game loop
            AnimationTimer timer = new AnimationTimer() {
                public void handle(long now) {
                    try {
                        if (gateway != null) {
                            gateway.processPendingActions(chonGame.getTick());
                            gateway.updateControlledAgents(chonGame);
                        }

                        chonGame.loop();

                        var snapshot = snapshotBuilder.build(
                                chonGame,
                                chonGame.getTick());

                        if (gateway != null) {
                            gateway.publish(snapshot);
                        }
                    } catch (RuntimeException exception) {
                        exception.printStackTrace();
                    }
                }
            };

            theStage.setOnCloseRequest(event -> {
                timer.stop();
                if (gateway != null) {
                    try {
                        gateway.close();
                    } catch (Exception exception) {
                        exception.printStackTrace();
                    }
                }
            });

            timer.start();

            theStage.show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}