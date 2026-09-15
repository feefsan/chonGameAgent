package chon.group.game.gateway;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Bridges network threads and the game thread without sharing mutable state. */
public class GameActionQueue {

    private final ConcurrentLinkedQueue<GameAction> actions = new ConcurrentLinkedQueue<>();

    public void offer(GameAction action) {
        actions.offer(action);
    }

    public GameAction poll() {
        return actions.poll();
    }
}