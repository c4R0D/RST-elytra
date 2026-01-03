package dev.rstminecraft;

//文件解释：本文件为模组GUI实现。

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3i;

import java.util.List;
import java.util.Objects;

import static dev.rstminecraft.RSTTestMod.*;


public class RSTScr extends Screen {
    private final Screen parent;
    private boolean firstUse;
    private static final int MainButtonsRow = 3;
    private static final int MainButtonsCol = 1;
    private final int buttonWidth;

    private interface SrcButtonEntryOnClick {
        void onClick();
    }

    private interface SrcInputEntryOnTick {
        void onTick(String text);
    }

    private static class SrcEntry {
    }

    private static class SrcButtonEntry extends SrcEntry {
        public SrcButtonEntry(String text, String tooltip, SrcButtonEntryOnClick onClick) {
            this.text = text;
            this.tooltip = tooltip;
            this.onClick = onClick;
        }

        public String text;
        public String tooltip;
        public SrcButtonEntryOnClick onClick;
    }

    private static class SrcInputEntry extends SrcEntry {
        public SrcInputEntry(String title, String defaultText, SrcInputEntryOnTick onTick) {
            this.title = title;
            this.defaultText = defaultText;
            this.onTick = onTick;
        }

        public String title;
        public String defaultText;
        public SrcInputEntryOnTick onTick;
    }

    private static class ciSrc extends Screen {
        public ciSrc(Screen parent) {
            super(Text.literal(("RST Auto Elytra Mod Menu")));
            this.parent = parent;
            this.buttonWidth = Math.max(100, Math.min(300, (int) (this.width * 0.3)));
        }

        private String x = null;
        private String z = null;
        private static final int ciButtonsRow = 3;
        private static final int ciButtonsCol = 1;

        private final SrcEntry[] ciEntry = {new SrcInputEntry(("目的地X坐标"), ("目的地X坐标"), (str) -> this.x = str), new SrcInputEntry(("目的地Z坐标"), ("目的地Z坐标"), (str) -> this.z = str), new SrcButtonEntry(("开始飞行"), ("开始前往上方输入的坐标"), () -> {
            int x1, z1;
            if (x == null || z == null) return;
            try {
                x1 = Integer.parseInt(x);  // 尝试将输入转换为整数
            } catch (NumberFormatException e) {
                return;
            }
            try {
                z1 = Integer.parseInt(z);  // 尝试将输入转换为整数
            } catch (NumberFormatException e) {
                return;
            }
            if (client == null)
                return;
            int sl = getInt("SegLength", DEFAULT_SEGMENT_LENGTH);
            List<Vec3i> segments = calculatePathSegments(client, x1, z1, sl, false);
            Objects.requireNonNull(client.player).sendMessage(Text.literal(("任务开始！补给距离：") + sl), false);
            if (segments.isEmpty()) {
                client.player.sendMessage(Text.literal(("分段失败！")), false);
                return;
            }
            segmentsMainSupply(segments, 0, client, getBoolean(("isAutoLog"), true), getBoolean(("isAutoLogOnSeg1"), false));
            client.setScreen(null);
        })};

        private final Screen parent;
        private final int buttonWidth;

        @Override
        protected void init() {
            realInit();
        }

        private void realInit() {
            ClickableWidget[] ciWidget = EntryToWidget(ciEntry, ciButtonsRow, ciButtonsCol, buttonWidth, width, height, textRenderer);
            for (ClickableWidget i : ciWidget) {
                addDrawableChild(i);
            }
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public void close() {
            if (client != null) {
                client.setScreen(parent);
            }
        }

    }

    private static class HelperSrc extends Screen {
        private static final String MOD_ID = ("rst-testmod");
        private static final Identifier shulkerExample = Identifier.of(MOD_ID, ("shulker_example.png"));
        private static final Identifier page0 = Identifier.of(MOD_ID, ("page0.png"));
        private static final Identifier page1 = Identifier.of(MOD_ID, ("page1.png"));
        private static final Identifier page2 = Identifier.of(MOD_ID, ("page2.png"));
        private static final Identifier page3 = Identifier.of(MOD_ID, ("page3.png"));

        public HelperSrc(Screen parent) {
            super(Text.literal(("RST Auto Elytra Mod Helper Menu")));
            this.parent = parent;
            this.buttonWidth = Math.max(100, Math.min(300, (int) (this.width * 0.3)));
        }

        private Screen parent;

        private static final int HelperButtonsRow = 1;
        private static final int HelperButtonsCol = 2;
        private int pages = 0;
        private static final int maxPage = 3;
        private ClickableWidget[] HelperWidget = new ClickableWidget[HelperButtonsCol * HelperButtonsRow];

        private void change() {
            for (ClickableWidget i : HelperWidget) {
                remove(i);
            }
            ((SrcButtonEntry) HelperEntry[1]).text = ("阅读完成");
            HelperWidget = EntryToWidget(HelperEntry, HelperButtonsRow, HelperButtonsCol, buttonWidth, width, height, textRenderer);
            for (ClickableWidget i : HelperWidget) {
                addDrawableChild(i);
            }

        }

        private void changeBack() {
            for (ClickableWidget i : HelperWidget) {
                remove(i);
            }
            ((SrcButtonEntry) HelperEntry[1]).text = ("下一页");
            HelperWidget = EntryToWidget(HelperEntry, HelperButtonsRow, HelperButtonsCol, buttonWidth, width, height, textRenderer);
            for (ClickableWidget i : HelperWidget) {
                addDrawableChild(i);
            }

        }

        private final SrcEntry[] HelperEntry = {new SrcButtonEntry(("上一页"), ("前往上一页指南"), () -> {
            if (pages == maxPage) {
                changeBack();
            }
            pages -= (pages == 0) ? 0 : 1;
        }), new SrcButtonEntry(("下一页"), ("前往上一页指南或结束"), () -> {
            if (pages == maxPage - 1) {
                change();
            }
            if (pages == maxPage) {
                if (client != null) {
                    client.setScreen(parent);
                }
            } else {
                pages++;
            }

        })};

        private final int buttonWidth;

        @Override
        protected void init() {
            realInit();
        }

        private void realInit() {
            HelperWidget = EntryToWidget(HelperEntry, HelperButtonsRow, HelperButtonsCol, buttonWidth, width, height, textRenderer);
            for (ClickableWidget i : HelperWidget) {
                addDrawableChild(i);
            }
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
            realRender(context);
        }

        private void realRender(DrawContext context) {
            switch (pages) {
                case 0 ->
                        context.drawTexture(page0, (int) (width * 0.125), 20, (int) (width * 0.75), (int) (width * 0.157), 0, 0, 634, 132, 634, 132);
                case 1 ->
                        context.drawTexture(page1, (int) (width * 0.125), 20, (int) (width * 0.75), (int) (width * 0.205), 0, 0, 992, 272, 992, 272);
                case 2 -> {
                    context.drawTexture(page2, (int) (width * 0.125), 20, (int) (width * 0.75), (int) (width * 0.205), 0, 0, 1054, 212, 1054, 212);
                    context.drawTexture(shulkerExample, (int) (width * 0.125), height / 2 + 30, (int) (width * 0.75), (int) (width * 0.285), 0, 0, 638, 247, 638, 247);
                }
                case 3 ->
                        context.drawTexture(page3, (int) (width * 0.125), 20, (int) (width * 0.75), (int) (width * 0.136), 0, 0, 1008, 184, 1008, 184);
                default ->
                        context.drawTextWithShadow(textRenderer, Text.literal(("此页没有内容")), width / 2 - 40, height / 2 + 30, 0xFFFFFF);
            }
        }

        @Override
        public void close() {
            if (client != null) {
                client.setScreen(parent);
            }
        }

    }

    private static class SettingsSrc extends Screen {

        private final Screen parent;
        private final int buttonWidth;
        private String SegLen = String.valueOf(getInt("SegLength", DEFAULT_SEGMENT_LENGTH));

        public SettingsSrc(Screen parent) {
            super(Text.literal(("RST Auto Elytra Mod Settings Menu")));
            this.parent = parent;
            this.buttonWidth = Math.max(100, Math.min(300, (int) (this.width * 0.3)));
        }


        private static final int SettingsButtonsRow = 4;
        private static final int SettingsButtonsCol = 1;
        private ClickableWidget[] SettingsWidget = new ClickableWidget[SettingsButtonsCol * SettingsButtonsRow];

        private SrcEntry[] SettingsEntry;

        private void BuildButtons() {
            for (ClickableWidget i : SettingsWidget) {
                remove(i);
            }
            SettingsEntry = new SrcEntry[]{new SrcButtonEntry(("自动退出:") + (getBoolean(("isAutoLog"), true) ? ("开") : ("关")), ("在任务失败时是否自动退出服务器"), () -> {
                setBoolean(("isAutoLog"), !getBoolean(("isAutoLog"), true));
                BuildButtons();
            }), new SrcButtonEntry(("第一段自动退出:") + (getBoolean(("isAutoLogOnSeg1"), false) ? ("开") : ("关")), ("在任务刚开始时若失败是否自动退出。假如否，您可以避免在第一次补给时因“末影箱中没有补给物品”等简单原因自动退出（造成时间浪费），但请确保第一次补给成功后再离开电脑"), () -> {
                setBoolean(("isAutoLogOnSeg1"), !getBoolean(("isAutoLogOnSeg1"), false));
                BuildButtons();
            }), new SrcInputEntry(("补给分段距离"), String.valueOf(getInt("SegLength", DEFAULT_SEGMENT_LENGTH)) , (text) -> this.SegLen = text)
                    , new SrcButtonEntry(("更改补给距离"), ("点击此处将补给距离改为上方输入框中的值（>10000)"), () -> {
                int sl;
                if (SegLen == null) return;
                try {
                    sl = Integer.parseInt(SegLen);  // 尝试将输入转换为整数
                } catch (NumberFormatException e) {
                    return;
                }
                setInt("SegLength", sl);
                SegLen = String.valueOf(getInt("SegLength", DEFAULT_SEGMENT_LENGTH));
                if (client != null && client.player != null) {
                    client.player.sendMessage(Text.literal(("已将补给距离设为：") + sl), false);

                }

            })
            };

            SettingsWidget = EntryToWidget(SettingsEntry, SettingsButtonsRow, SettingsButtonsCol, buttonWidth, width, height, textRenderer);
            for (ClickableWidget i : SettingsWidget) {
                addDrawableChild(i);
            }


        }


        @Override
        protected void init() {
            realInit();
        }

        private void realInit() {
            SettingsEntry = new SrcEntry[]{new SrcButtonEntry(("自动退出:") + (getBoolean(("isAutoLog"), true) ? ("开") : ("关")), ("在任务失败时是否自动退出服务器"), () -> {
                setBoolean(("isAutoLog"), !getBoolean(("isAutoLog"), true));
                BuildButtons();
            }), new SrcButtonEntry(("第一段自动退出:") + (getBoolean(("isAutoLogOnSeg1"), false) ? ("开") : ("关")), ("在任务刚开始时若失败是否自动退出。假如否，您可以避免在第一次补给时因“末影箱中没有补给物品”等简单原因自动退出（造成时间浪费），但请确保第一次补给成功后再离开电脑"), () -> {
                setBoolean(("isAutoLogOnSeg1"), !getBoolean(("isAutoLogOnSeg1"), false));
                BuildButtons();
            }), new SrcInputEntry(("补给分段距离"), String.valueOf(getInt("SegLength", DEFAULT_SEGMENT_LENGTH)) , (text) -> this.SegLen = text)
                    , new SrcButtonEntry(("更改补给距离"), ("点击此处将补给距离改为上方输入框中的值（>10000)"), () -> {
                int sl;
                if (SegLen == null) return;
                try {
                    sl = Integer.parseInt(SegLen);  // 尝试将输入转换为整数
                } catch (NumberFormatException e) {
                    return;
                }
                setInt("SegLength", sl);
                SegLen = String.valueOf(getInt("SegLength", DEFAULT_SEGMENT_LENGTH));
                if (client != null && client.player != null) {
                    client.player.sendMessage(Text.literal(("已将补给距离设为：") + sl), false);

                }

            })
            };

            SettingsWidget = EntryToWidget(SettingsEntry, SettingsButtonsRow, SettingsButtonsCol, buttonWidth, width, height, textRenderer);
            for (ClickableWidget i : SettingsWidget) {
                addDrawableChild(i);
            }


        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public void close() {
            if (client != null) {
                client.setScreen(parent);
            }
        }

    }


    private final SrcEntry[] MainEntry = {new SrcButtonEntry(("设置"), ("调整Mod设置"), () -> {
        if (client != null) {
            client.setScreen(new SettingsSrc(client.currentScreen));
        }
    }), new SrcButtonEntry(("飞行菜单"), ("输入坐标并开始自动飞行"), () -> {
        if (client != null) {
            client.setScreen(new ciSrc(client.currentScreen));
        }
    }), new SrcButtonEntry(("使用指南"), ("请按指南要求完成必要设置"), () -> {
        if (client != null) {
            client.setScreen(new HelperSrc(client.currentScreen));
        }
    })};

    private static ClickableWidget[] EntryToWidget(SrcEntry[] Entry, int row, int col, int widgetWidth, int width, int height, TextRenderer textRenderer) {
        ClickableWidget[] widget = new ClickableWidget[row * col];
        for (int i = 0; i < row; i++) {
            for (int j = 0; j < col; j++) {
                int finalI = i;
                int finalJ = j;
                int widgetX = width / (col + 1) * (j + 1) - widgetWidth / 2;
                int widgetY = 40 + (height - 80) / (row + 1) * (i + 1);
                if (Entry[i * col + j] instanceof SrcButtonEntry)
                    widget[i * col + j] = ButtonWidget.builder(Text.literal(((SrcButtonEntry) Entry[i * col + j]).text), button -> ((SrcButtonEntry) Entry[finalI * col + finalJ]).onClick.onClick()).dimensions(widgetX, widgetY, widgetWidth, 20).tooltip(Tooltip.of(Text.literal(((SrcButtonEntry) Entry[i * col + j]).tooltip))).build();
                else if (Entry[i * col + j] instanceof SrcInputEntry) {
                    TextFieldWidget tmp = new TextFieldWidget(textRenderer, widgetX, widgetY, widgetWidth, 20, Text.literal(((SrcInputEntry) Entry[i * col + j]).title));
                    tmp.setText("");
                    tmp.setMaxLength(10);
                    tmp.setPlaceholder(Text.literal(((SrcInputEntry) Entry[i * col + j]).defaultText));
                    int finalJ1 = j;
                    int finalI1 = i;
                    tmp.setChangedListener((str) -> ((SrcInputEntry) Entry[finalI1 * col + finalJ1]).onTick.onTick(str));
                    widget[i * col + finalJ1] = tmp;
                }
            }

        }
        return widget;
    }

    public RSTScr(Screen parent, boolean firstUse) {

        super(Text.literal(("RST Auto Elytra Menu")));
        this.buttonWidth = Math.max(100, Math.min(300, (int) (this.width * 0.3)));
        this.firstUse = firstUse;
        this.parent = parent;

    }

    private ButtonWidget button1;


    @Override
    protected void init() {
        realInit();
    }

    private void realInit() {
        ClickableWidget[] mainWidget = EntryToWidget(MainEntry, MainButtonsRow, MainButtonsCol, buttonWidth, width, height, textRenderer);

        if (firstUse) {

            button1 = ButtonWidget.builder(Text.literal(("我知道了")), button -> {
                setBoolean(("FirstUse"), false);
                remove(button1);
                firstUse = false;
                realInit();
            }).dimensions(width / 2 - 205, 120, 200, 20).tooltip(Tooltip.of(Text.literal(("阅读完毕指南")))).build();
            addDrawableChild(button1);
        } else if (ModStatus != ModStatuses.idle) {
            button1 = ButtonWidget.builder(Text.literal(("取消飞行")), button -> {
                ModStatus = ModStatuses.canceled;
                if (client != null) {
                    client.setScreen(parent);
                }
            }).dimensions(width / 2 - buttonWidth / 2, height / 2, buttonWidth, 20).tooltip(Tooltip.of(Text.literal("关闭飞行。"))).build();
            addDrawableChild(button1);
        } else {
            for (ClickableWidget i : mainWidget) {
                addDrawableChild(i);
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        realRender(context, mouseX, mouseY, delta);
    }

    private void realRender(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawTextWithShadow(textRenderer, ("欢迎使用RSTAutoElytraMod"), width / 3 * 2, 20, 16777215);
        if (firstUse) {
            context.drawTextWithShadow(textRenderer, ("若您是第一次使用RSTAutoElytraMod，请务必仔细阅读本指南"), width / 4, height / 10, 0xFF0000);
            context.drawTextWithShadow(textRenderer, ("否则可能造成物资损失或存档损坏等严重后果！"), width / 4, height / 10 + 15, 0xFFFFFF);
        } else if (ModStatus != ModStatuses.idle) {
            context.drawTextWithShadow(textRenderer, ("正在飞行中，若要更改设置请先取消飞行。"), width / 4, height / 10, 0xFF0000);

        }
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }
}
