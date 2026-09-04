package kg.ariet.drillminer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MinerController {
    public enum State {
        STOPPED,
        GOING_TO_START,
        POSITIONING_LANE,
        MINING_LANE,
        RETURNING_TO_POS1,
        EATING,
        BUILDING_UP
    }

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

    // Rectangle sweep. The drill is treated as roughly 3 blocks wide and 2 blocks deep.
    private boolean sweepAlongX = true;
    private int minX, maxX, minZ, maxZ, sweepY;
    private final List<Integer> laneCoords = new ArrayList<>();
    private int laneIndex = 0;
    private boolean positiveTravel = true;
    private BlockPos laneStart;
    private BlockPos laneEnd;

    public MinerController(MinerConfig config) {
        this.config = config;
    }

    public State getState() { return state; }
    public boolean isRunning() { return state != State.STOPPED; }

    public String getStatusText() {
        return switch (state) {
            case STOPPED -> "Остановлен";
            case GOING_TO_START -> "Иду к Pos 1";
            case POSITIONING_LANE -> "Перехожу на ряд " + (laneIndex + 1) + "/" + Math.max(1, laneCoords.size());
            case MINING_LANE -> "Копаю ряд " + (laneIndex + 1) + "/" + Math.max(1, laneCoords.size());
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
        initSweep();
        if (laneCoords.isEmpty()) {
            message(client, "Drill Miner: область пустая");
            return;
        }

        laneIndex = 0;
        configureCurrentLane();
        state = near(client.player, config.pos1, 1.35) ? State.POSITIONING_LANE : State.GOING_TO_START;
        ticks = 0;
        commandTicks = 0;
        attackPulseTicks = 0;
        stuckTicks = 0;
        lastProgressPos = pos(client.player);
        lastDrillPulsePos = pos(client.player);
        message(client, "Drill Miner: START, рядов: " + laneCoords.size());
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
            client.options.attackKey.setPressed(false);
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
            case POSITIONING_LANE -> tickPositionLane(client);
            case MINING_LANE -> tickMiningLane(client);
            case RETURNING_TO_POS1 -> tickReturning(client);
            default -> { }
        }
    }

    private void initSweep() {
        minX = Math.min(config.pos1.getX(), config.pos2.getX());
        maxX = Math.max(config.pos1.getX(), config.pos2.getX());
        minZ = Math.min(config.pos1.getZ(), config.pos2.getZ());
        maxZ = Math.max(config.pos1.getZ(), config.pos2.getZ());
        sweepY = config.pos1.getY();

        int sizeX = maxX - minX + 1;
        int sizeZ = maxZ - minZ + 1;

        // Mine along the longer side so there are fewer turns.
        sweepAlongX = sizeX >= sizeZ;
        laneCoords.clear();

        if (sweepAlongX) {
            laneCoords.addAll(makeLaneCenters(minZ, maxZ));
            if (Math.abs(config.pos1.getZ() - maxZ) < Math.abs(config.pos1.getZ() - minZ)) {
                Collections.reverse(laneCoords);
            }
            positiveTravel = Math.abs(config.pos1.getX() - minX) <= Math.abs(config.pos1.getX() - maxX);
        } else {
            laneCoords.addAll(makeLaneCenters(minX, maxX));
            if (Math.abs(config.pos1.getX() - maxX) < Math.abs(config.pos1.getX() - minX)) {
                Collections.reverse(laneCoords);
            }
            positiveTravel = Math.abs(config.pos1.getZ() - minZ) <= Math.abs(config.pos1.getZ() - maxZ);
        }
    }

    private static List<Integer> makeLaneCenters(int min, int max) {
        List<Integer> out = new ArrayList<>();
        int width = max - min + 1;
        if (width <= 1) {
            out.add(min);
            return out;
        }
        if (width == 2) {
            out.add(min);
            out.add(max);
            return out;
        }

        // Center the 3-wide drill inside the selected rectangle as much as possible.
        int c = min + 1;
        while (c <= max - 1) {
            out.add(c);
            c += 3;
        }
        int last = out.get(out.size() - 1);
        if (last + 1 < max) {
            int edgeCenter = max - 1;
            if (edgeCenter != last) out.add(edgeCenter);
        }
        return out;
    }

    private void configureCurrentLane() {
        int cross = laneCoords.get(laneIndex);
        if (sweepAlongX) {
            laneStart = new BlockPos(positiveTravel ? minX : maxX, sweepY, cross);
            laneEnd = new BlockPos(positiveTravel ? maxX : minX, sweepY, cross);
        } else {
            laneStart = new BlockPos(cross, sweepY, positiveTravel ? minZ : maxZ);
            laneEnd = new BlockPos(cross, sweepY, positiveTravel ? maxZ : minZ);
        }
    }

    private void tickGoToStart(MinecraftClient client) {
        if (config.pos1 == null) { stop(client); return; }
        client.options.attackKey.setPressed(false);
        if (near(client.player, config.pos1, 1.30)) {
            releaseMotion(client);
            state = State.POSITIONING_LANE;
            return;
        }
        moveToward(client, config.pos1, false);
        maybeRecoverOrBuild(client, State.GOING_TO_START);
    }

    private void tickPositionLane(MinecraftClient client) {
        client.options.attackKey.setPressed(false);
        if (laneStart == null) { finishSweep(client); return; }

        if (near(client.player, laneStart, 0.95)) {
            releaseMotion(client);
            state = State.MINING_LANE;
            lastDrillPulsePos = pos(client.player);
            attackPulseTicks = 3; // hit immediately at the start of every row
            stuckTicks = 0;
            return;
        }

        moveToward(client, laneStart, false);
        maybeRecoverOrBuild(client, State.POSITIONING_LANE);
    }

    private void tickMiningLane(MinecraftClient client) {
        if (laneEnd == null) { finishSweep(client); return; }

        // The drill reaches about 2 blocks forward, so stopping around one block from the edge is enough.
        if (near(client.player, laneEnd, 1.05)) {
            releaseMotion(client);
            client.options.attackKey.setPressed(false);
            advanceLane(client);
            return;
        }

        moveToward(client, laneEnd, true);

        double movedSincePulse = horizontalDistance(pos(client.player), lastDrillPulsePos);
        if (attackPulseTicks > 0) {
            client.options.attackKey.setPressed(true);
            attackPulseTicks--;
        } else if (movedSincePulse >= config.drillStepDistance || stuckTicks >= 12) {
            attackPulseTicks = 2;
            lastDrillPulsePos = pos(client.player);
            client.options.attackKey.setPressed(true);
        } else {
            client.options.attackKey.setPressed(false);
        }

        maybeRecoverOrBuild(client, State.MINING_LANE);
    }

    private void advanceLane(MinecraftClient client) {
        laneIndex++;
        if (laneIndex >= laneCoords.size()) {
            finishSweep(client);
            return;
        }
        positiveTravel = !positiveTravel;
        configureCurrentLane();
        state = State.POSITIONING_LANE;
        attackPulseTicks = 0;
        lastDrillPulsePos = pos(client.player);
    }

    private void finishSweep(MinecraftClient client) {
        releaseMotion(client);
        client.options.attackKey.setPressed(false);
        state = State.RETURNING_TO_POS1;
        message(client, "Drill Miner: квадрат пройден, возвращаюсь к Pos 1");
    }

    private void tickReturning(MinecraftClient client) {
        client.options.attackKey.setPressed(false);
        if (config.pos1 == null) { stop(client); return; }
        if (near(client.player, config.pos1, 1.35)) {
            releaseMotion(client);
            if (config.loop) {
                initSweep();
                laneIndex = 0;
                configureCurrentLane();
                state = State.POSITIONING_LANE;
                lastDrillPulsePos = pos(client.player);
                stuckTicks = 0;
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

        // Try jumping first. When mining a row, also keep hitting the obstruction.
        client.options.jumpKey.setPressed(true);
        if (resume == State.MINING_LANE && stuckTicks >= 20) {
            client.options.attackKey.setPressed(true);
        }

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
            state = resumeAfterEat == State.STOPPED ? State.POSITIONING_LANE : resumeAfterEat;
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
