package kg.ariet.drillminer;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class MinerConfig {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("drillminer.properties");

    public BlockPos pos1 = null;
    public BlockPos pos2 = null;
    public String autoCommand = "";
    public int commandIntervalSeconds = 180;
    public int foodThreshold = 14;
    public boolean autoEat = true;
    public boolean autoBuild = true;
    public boolean loop = false;
    public double drillStepDistance = 1.70;

    public void load() {
        if (!Files.exists(FILE)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            p.load(in);
            pos1 = parsePos(p.getProperty("pos1"));
            pos2 = parsePos(p.getProperty("pos2"));
            autoCommand = p.getProperty("autoCommand", "");
            commandIntervalSeconds = parseInt(p.getProperty("commandIntervalSeconds"), 180, 10, 3600);
            foodThreshold = parseInt(p.getProperty("foodThreshold"), 14, 1, 19);
            autoEat = Boolean.parseBoolean(p.getProperty("autoEat", "true"));
            autoBuild = Boolean.parseBoolean(p.getProperty("autoBuild", "true"));
            loop = Boolean.parseBoolean(p.getProperty("loop", "false"));
            drillStepDistance = parseDouble(p.getProperty("drillStepDistance"), 1.70, 0.5, 4.0);
        } catch (IOException ignored) {
        }
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("pos1", formatPos(pos1));
        p.setProperty("pos2", formatPos(pos2));
        p.setProperty("autoCommand", autoCommand == null ? "" : autoCommand.trim());
        p.setProperty("commandIntervalSeconds", Integer.toString(commandIntervalSeconds));
        p.setProperty("foodThreshold", Integer.toString(foodThreshold));
        p.setProperty("autoEat", Boolean.toString(autoEat));
        p.setProperty("autoBuild", Boolean.toString(autoBuild));
        p.setProperty("loop", Boolean.toString(loop));
        p.setProperty("drillStepDistance", Double.toString(drillStepDistance));
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                p.store(out, "Drill Miner Fabric 1.21.11");
            }
        } catch (IOException ignored) {
        }
    }

    public static String formatPos(BlockPos pos) {
        return pos == null ? "" : pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    public static BlockPos parsePos(String text) {
        if (text == null || text.isBlank()) return null;
        String[] s = text.trim().replace(',', ' ').split("\\s+");
        if (s.length != 3) return null;
        try {
            return new BlockPos(Integer.parseInt(s[0]), Integer.parseInt(s[1]), Integer.parseInt(s[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseInt(String s, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(s))); }
        catch (Exception e) { return fallback; }
    }

    private static double parseDouble(String s, double fallback, double min, double max) {
        try { return Math.max(min, Math.min(max, Double.parseDouble(s))); }
        catch (Exception e) { return fallback; }
    }
}
