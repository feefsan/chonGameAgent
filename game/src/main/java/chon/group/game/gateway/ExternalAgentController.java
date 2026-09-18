package chon.group.game.gateway;

import java.util.ArrayList;
import java.util.List;

import chon.group.game.core.agent.Agent;
import chon.group.game.core.agent.Direction;
import chon.group.game.core.environment.Environment;
import chon.group.game.core.environment.Level;
import chon.group.game.core.weapon.Shot;
import chon.group.game.joystick.GameCommand;
import chon.group.game.joystick.client.ExternalJoystick;
import chon.group.game.sound.Sound;
import chon.group.game.sound.SoundEvent;

/**
 * Binds one external client connection to exactly one controllable agent.
 *
 * <p>
 * Slot {@code 0} always maps to the protagonist, whose movement keeps being
 * driven by the game's normal input pipeline. Slots {@code 1} and above map
 * to {@code Level.getAgents().get(slot - 1)}, resolved fresh every tick since
 * the agent list changes across levels.
 * </p>
 */
public class ExternalAgentController {

    private final int slot;
    private final ExternalJoystick joystick;
    private final GameActionQueue actionQueue = new GameActionQueue();
    private volatile boolean closed;
    private Agent boundAgent;

    public ExternalAgentController(int slot, ExternalJoystick joystick) {
        this.slot = slot;
        this.joystick = joystick;
    }

    public int getSlot() {
        return slot;
    }

    public boolean isProtagonist() {
        return slot == 0;
    }

    public ExternalJoystick getJoystick() {
        return joystick;
    }

    public GameActionQueue getActionQueue() {
        return actionQueue;
    }

    public void close() {
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Applies the current joystick state to the bound agent. Bots only; the
     * protagonist keeps being handled by the normal game/joystick pipeline.
     * Must only be called from the game thread.
     */
    public void update(Environment environment) {
        if (isProtagonist()) {
            return;
        }

        Level level = environment.getCurrentLevel();
        int index = slot - 1;
        Agent agent = (level != null && index >= 0 && index < level.getAgents().size())
                ? level.getAgents().get(index)
                : null;

        if (boundAgent != null && boundAgent != agent) {
            boundAgent.setExternallyControlled(false);
        }
        boundAgent = agent;

        if (agent == null || agent.isDead()) {
            return;
        }

        agent.setExternallyControlled(true);
        applyMovement(agent);
        applyAttack(agent, level, environment);
        joystick.endFrame();
    }

    /** Releases the bound agent back to AI control. Must only be called from the game thread. */
    public void release() {
        if (boundAgent != null) {
            boundAgent.setExternallyControlled(false);
            boundAgent = null;
        }
    }

    private void applyMovement(Agent agent) {
        List<Direction> directions = new ArrayList<>();
        if (joystick.isHeld(GameCommand.RIGHT)) {
            directions.add(Direction.RIGHT);
        }
        if (joystick.isHeld(GameCommand.LEFT)) {
            directions.add(Direction.LEFT);
        }
        if (joystick.isHeld(GameCommand.DOWN)) {
            directions.add(Direction.DOWN);
        }
        if (joystick.isHeld(GameCommand.UP)) {
            directions.add(Direction.UP);
        }

        if (directions.isEmpty()) {
            agent.idle();
        } else {
            agent.move(directions);
        }
    }

    private void applyAttack(Agent agent, Level level, Environment environment) {
        if (level == null || !joystick.consumePress(GameCommand.ATTACK)) {
            return;
        }
        Shot shot = agent.useWeapon();
        if (shot == null) {
            return;
        }
        level.getShots().add(shot);
        Sound sound = agent.getSoundSet().get(SoundEvent.ATTACK);
        if (sound != null) {
            environment.getSounds().add(sound);
        }
    }
}
