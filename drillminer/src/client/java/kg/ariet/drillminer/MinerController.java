package kg.ariet.drillminer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class MinerController {
    public enum State { STOPPED, GOING_TO_START, MINING_TO_POS2, RETURNING_TO_POS1, EATING, BUILDING_UP }

    private final MinerConfig config;
    private State state = State.STOPPED;
    private State resumeAfterEat = State.STOPPED;
    private State resumeAfterBuild = State.STOPPED;

    private int ticks = 0;
    private int commandTicks = 0;
    private int attackPulseTicks = 0;
    private int stuckTicks = 0;
    private int buildTicks = 0;
    private int oldSelectedSlot = -1;
    private Vec3d lastProgressPos = Vec3d.ZERO;
    private Vec3d lastDrillPulsePos = Vec3d.ZERO;

    public MinerController(MinerConfig config) {
        this.config = config;
    }

    public State getState() { return state; }
    public boolean isRunning() { return state != State.STOPPED; }

    public String getStatusText() {
        return switch (state) {
            case STOPPED -> "Остановлен";
            case GOING_TO_START -> "Иду к Pos 1";
            case MINING_TO_POS2 -> "Копаю к Pos 2";
            case RETURNING_TO_POS1 -> "Возвращаюсь к Pos 1";
            case EATING -> "Автоеда";
            case BUILDING_UP -> "Подъём блоками";
        };
    }

    public void start(MinecraftClient client) {
        if (client.player == null || config.pos1 == null || config.pos2 == null) {
            message(client, "Drill Miner: сначала задай Pos 1 и Pos 2");
            return;
        }
        releaseAll(client);
        state = near(client.player, config.pos1, 1.35) ? State.MINING_TO_POS2 : State.GOING_TO_START;
        ticks = 0;
        commandTicks = 0;
        attackPulseTicks = 0;
        stuckTicks = 0;
        lastProgressPos = pos(client.player);
        lastDrillPulsePos = pos(client.player);
        message(client, "Drill Miner: START");
    }

    public void stop(MinecraftClient client) {
        state = State.STOPPED;
        releaseAll(client);
        restoreSlot(client);
        message(client, "Drill Miner: STOP");
    }

    public void tick(MinecraftClient client) {
        if (state == State.STOPPED || client.player == null || client.world == null) return;
        if (client.currentScreen != null) {
            releaseMotion(client);
            return;
        }

        ticks++;
        commandTicks++;
        sendPeriodicCommand(client);

        if (state != State.EATING && config.autoEat && client.player.getHungerManager().getFoodLevel() <= config.foodThreshold) {
            if (beginEating(client)) return;
        }

        if (state == State.EATING) {
            tickEating(client);
            return;
        }
        if (state == State.BUILDING_UP) {
            tickBuildUp(client);
            return;
        }

        updateStuck(client);

        switch (state) {
            case GOING_TO_START -> tickGoToStart(client);
            case MINING_TO_POS2 -> tickMining(client);
            case RETURNING_TO_POS1 -> tickReturning(client);
            default -> { }
        }
    }

    private void tickGoToStart(MinecraftClient client) {
        if (config.pos1 == null) { stop(client); return; }
        if (near(client.player, config.pos1, 1.30)) {
            releaseMotion(client);
            state = State.MINING_TO_POS2;
            lastDrillPulsePos = pos(client.player);
            return;
        }
        moveToward(client, config.pos1, false);
        maybeRecoverOrBuild(client, State.GOING_TO_START);
    }

    private void tickMining(MinecraftClient client) {
        if (config.pos2 == null) { stop(client); return; }
        if (near(client.player, config.pos2, 1.45)) {
            releaseMotion(client);
            client.options.attackKey.setPressed(false);
            state = State.RETURNING_TO_POS1;
            return;
        }

        moveToward(client, config.pos2, true);

        double movedSincePulse = horizontalDistance(pos(client.player), lastDrillPulsePos);
        boolean pulseDue = movedSincePulse >= config.drillStepDistance || stuckTicks >= 12 || attackPulseTicks > 0;
        if (pulseDue) {
            if (attackPulseTicks <= 0) {
                attackPulseTicks = 3;
                lastDrillPulsePos = pos(client.player);
            }
            client.options.attackKey.setPressed(true);
            attackPulseTicks--;
        } else {
            client.options.attackKey.setPressed(false);
        }

        maybeRecoverOrBuild(client, State.MINING_TO_POS2);
    }

    private void tickReturning(MinecraftClient client) {
        client.options.attackKey.setPressed(false);
        if (config.pos1 == null) { stop(client); return; }
        if (near(client.player, config.pos1, 1.35)) {
            releaseMotion(client);
            if (config.loop) {
                state = State.MINING_TO_POS2;
                lastDrillPulsePos = pos(client.player);
                message(client, "Drill Miner: новый цикл");
            } else {
                stop(client);
            }
            return;
        }
        moveToward(client, config.pos1, false);
        maybeRecoverOrBuild(client, State.RETURNING_TO_POS1);
    }

    private void moveToward(MinecraftClient client, BlockPos target, boolean mining) {
        ClientPlayerEntity p = client.player;
        double tx = target.getX() + 0.5;
        double tz = target.getZ() + 0.5;
        double dx = tx - p.getX();
        double dz = tz - p.getZ();
        float yaw = (float)Math.toDegrees(Math.atan2(-dx, dz));
        p.setYaw(yaw);
        p.setHeadYaw(yaw);
        p.setPitch(mining ? 2.0f : 0.0f);

        client.options.forwardKey.setPressed(true);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);

        double dy = target.getY() - p.getY();
        if (dy > 0.75 && config.autoBuild) {
            startBuildUp(client, state);
        } else if (dy > 0.35) {
            client.options.jumpKey.setPressed(true);
        } else {
            client.options.jumpKey.setPressed(false);
        }
    }

    private void updateStuck(MinecraftClient client) {
        if (ticks % 10 != 0) return;
        Vec3d now = pos(client.player);
        if (horizontalDistance(now, lastProgressPos) < 0.12) stuckTicks += 10;
        else stuckTicks = Math.max(0, stuckTicks - 10);
        lastProgressPos = now;
    }

    private void maybeRecoverOrBuild(MinecraftClient client, State resume) {
        if (stuckTicks < 20) return;
        client.options.jumpKey.setPressed(true);
        if (stuckTicks >= 45 && config.autoBuild && findBlockSlot(client) >= 0) {
            startBuildUp(client, resume);
            stuckTicks = 0;
        }
    }

    private void startBuildUp(MinecraftClient client, State resume) {
        int slot = findBlockSlot(client);
        if (slot < 0 || state == State.BUILDING_UP) return;
        resumeAfterBuild = resume;
        oldSelectedSlot = client.player.getInventory().getSelectedSlot();
        client.player.getInventory().setSelectedSlot(slot);
        state = State.BUILDING_UP;
        buildTicks = 0;
        releaseMotion(client);
    }

    private void tickBuildUp(MinecraftClient client) {
        buildTicks++;
        ClientPlayerEntity p = client.player;
        p.setPitch(82.0f);
        client.options.attackKey.setPressed(false);
        client.options.forwardKey.setPressed(false);
        client.options.useKey.setPressed(true);
        client.options.jumpKey.setPressed(true);

        if (buildTicks >= 14) {
            client.options.useKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            restoreSlot(client);
            state = resumeAfterBuild;
            stuckTicks = 0;
            lastProgressPos = pos(p);
        }
    }

    private boolean beginEating(MinecraftClient client) {
        int slot = findFoodSlot(client);
        if (slot < 0) return false;
        resumeAfterEat = state;
        oldSelectedSlot = client.player.getInventory().getSelectedSlot();
        client.player.getInventory().setSelectedSlot(slot);
        state = State.EATING;
        releaseMotion(client);
        client.options.attackKey.setPressed(false);
        client.options.useKey.setPressed(true);
        return true;
    }

    private void tickEating(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.attackKey.setPressed(false);
        client.options.useKey.setPressed(true);

        if (client.player.getHungerManager().getFoodLevel() >= 19 || !client.player.getInventory().getSelectedStack().contains(DataComponentTypes.FOOD)) {
            client.options.useKey.setPressed(false);
            restoreSlot(client);
            state = resumeAfterEat == State.STOPPED ? State.MINING_TO_POS2 : resumeAfterEat;
            lastProgressPos = pos(client.player);
        }
    }

    private int findFoodSlot(MinecraftClient client) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (!s.isEmpty() && s.contains(DataComponentTypes.FOOD)) return i;
        }
        return -1;
    }

    private int findBlockSlot(MinecraftClient client) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = client.player.getInventory().getStack(i);
            if (!s.isEmpty() && s.getItem() instanceof BlockItem) return i;
        }
        return -1;
    }

    private void restoreSlot(MinecraftClient client) {
        if (client.player != null && oldSelectedSlot >= 0 && oldSelectedSlot < 9) {
            client.player.getInventory().setSelectedSlot(oldSelectedSlot);
        }
        oldSelectedSlot = -1;
    }

    private void sendPeriodicCommand(MinecraftClient client) {
        int intervalTicks = Math.max(200, config.commandIntervalSeconds * 20);
        if (commandTicks < intervalTicks) return;
        commandTicks = 0;
        String cmd = config.autoCommand == null ? "" : config.autoCommand.trim();
        if (cmd.isEmpty() || client.getNetworkHandler() == null) return;
        while (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (!cmd.isBlank()) client.getNetworkHandler().sendChatCommand(cmd);
    }

    private void releaseMotion(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }

    private void releaseAll(MinecraftClient client) {
        releaseMotion(client);
        client.options.attackKey.setPressed(false);
        client.options.useKey.setPressed(false);
    }

    private static boolean near(ClientPlayerEntity player, BlockPos pos, double radius) {
        double dx = player.getX() - (pos.getX() + 0.5);
        double dy = player.getY() - pos.getY();
        double dz = player.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dz * dz <= radius * radius && Math.abs(dy) <= 1.75;
    }

    private static Vec3d pos(ClientPlayerEntity player) {
        return new Vec3d(player.getX(), player.getY(), player.getZ());
    }

    private static double horizontalDistance(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void message(MinecraftClient client, String text) {
        if (client.player != null) client.player.sendMessage(Text.literal(text), true);
    }
}
