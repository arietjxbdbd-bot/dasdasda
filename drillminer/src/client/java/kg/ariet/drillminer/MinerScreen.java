package kg.ariet.drillminer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

public final class MinerScreen extends Screen {
    private TextFieldWidget pos1Field;
    private TextFieldWidget pos2Field;
    private TextFieldWidget commandField;
    private TextFieldWidget foodField;
    private TextFieldWidget stepField;

    private ButtonWidget autoEatButton;
    private ButtonWidget autoBuildButton;
    private ButtonWidget loopButton;

    public MinerScreen() {
        super(Text.literal("Drill Miner"));
    }

    @Override
    protected void init() {
        MinerConfig c = DrillMinerClient.CONFIG;
        int cx = this.width / 2;
        int left = cx - 150;
        int y = 42;

        pos1Field = new TextFieldWidget(textRenderer, left, y, 190, 20, Text.literal("Pos 1"));
        pos1Field.setText(MinerConfig.formatPos(c.pos1));
        addDrawableChild(pos1Field);
        addDrawableChild(ButtonWidget.builder(Text.literal("Текущая → Pos1"), b -> setCurrentPos(true))
                .dimensions(left + 195, y, 105, 20).build());

        y += 28;
        pos2Field = new TextFieldWidget(textRenderer, left, y, 190, 20, Text.literal("Pos 2"));
        pos2Field.setText(MinerConfig.formatPos(c.pos2));
        addDrawableChild(pos2Field);
        addDrawableChild(ButtonWidget.builder(Text.literal("Текущая → Pos2"), b -> setCurrentPos(false))
                .dimensions(left + 195, y, 105, 20).build());

        y += 34;
        commandField = new TextFieldWidget(textRenderer, left, y, 300, 20, Text.literal("Команда каждые 3 минуты"));
        commandField.setMaxLength(256);
        commandField.setText(c.autoCommand == null ? "" : c.autoCommand);
        addDrawableChild(commandField);

        y += 32;
        foodField = new TextFieldWidget(textRenderer, left, y, 80, 20, Text.literal("Голод"));
        foodField.setText(Integer.toString(c.foodThreshold));
        addDrawableChild(foodField);

        stepField = new TextFieldWidget(textRenderer, left + 90, y, 80, 20, Text.literal("Шаг бура"));
        stepField.setText(String.format(java.util.Locale.ROOT, "%.2f", c.drillStepDistance));
        addDrawableChild(stepField);

        autoEatButton = addDrawableChild(ButtonWidget.builder(toggleText("Автоеда", c.autoEat), b -> {
            c.autoEat = !c.autoEat;
            autoEatButton.setMessage(toggleText("Автоеда", c.autoEat));
        }).dimensions(left + 180, y, 120, 20).build());

        y += 30;
        autoBuildButton = addDrawableChild(ButtonWidget.builder(toggleText("Автоподъём", c.autoBuild), b -> {
            c.autoBuild = !c.autoBuild;
            autoBuildButton.setMessage(toggleText("Автоподъём", c.autoBuild));
        }).dimensions(left, y, 145, 20).build());

        loopButton = addDrawableChild(ButtonWidget.builder(toggleText("Цикл", c.loop), b -> {
            c.loop = !c.loop;
            loopButton.setMessage(toggleText("Цикл", c.loop));
        }).dimensions(left + 155, y, 145, 20).build());

        y += 34;
        addDrawableChild(ButtonWidget.builder(Text.literal("СОХРАНИТЬ"), b -> saveFields())
                .dimensions(left, y, 95, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("START"), b -> {
            if (saveFields()) {
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.setScreen(null);
                DrillMinerClient.CONTROLLER.start(mc);
            }
        }).dimensions(left + 102, y, 95, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("STOP"), b -> DrillMinerClient.CONTROLLER.stop(MinecraftClient.getInstance()))
                .dimensions(left + 204, y, 96, 20).build());

        y += 30;
        addDrawableChild(ButtonWidget.builder(Text.literal("Закрыть"), b -> close())
                .dimensions(cx - 50, y, 100, 20).build());
    }

    private void setCurrentPos(boolean first) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        BlockPos p = mc.player.getBlockPos();
        if (first) pos1Field.setText(MinerConfig.formatPos(p));
        else pos2Field.setText(MinerConfig.formatPos(p));
    }

    private boolean saveFields() {
        MinerConfig c = DrillMinerClient.CONFIG;
        BlockPos p1 = MinerConfig.parsePos(pos1Field.getText());
        BlockPos p2 = MinerConfig.parsePos(pos2Field.getText());
        if (p1 == null || p2 == null) return false;

        c.pos1 = p1;
        c.pos2 = p2;
        c.autoCommand = commandField.getText().trim();
        c.commandIntervalSeconds = 180;
        try { c.foodThreshold = Math.max(1, Math.min(19, Integer.parseInt(foodField.getText().trim()))); }
        catch (Exception e) { c.foodThreshold = 14; }
        try { c.drillStepDistance = Math.max(0.5, Math.min(4.0, Double.parseDouble(stepField.getText().trim().replace(',', '.')))); }
        catch (Exception e) { c.drillStepDistance = 1.70; }
        c.save();
        return true;
    }

    private static Text toggleText(String name, boolean enabled) {
        return Text.literal(name + ": " + (enabled ? "ВКЛ" : "ВЫКЛ"));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int cx = width / 2;
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Drill Miner — Fabric 1.21.11"), cx, 14, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Статус: " + DrillMinerClient.CONTROLLER.getStatusText()), cx, 27, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, "Pos1 / Pos2: X Y Z", cx - 150, 32, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, "Команда (например /spawn) — каждые 180 сек", cx - 150, 96, 0xA0A0A0);
        context.drawTextWithShadow(textRenderer, "Голод ≤   |   Шаг бура ≈ 2 блока", cx - 150, 128, 0xA0A0A0);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
