package dev.rstminecraft;

//提示：本代码完全由RSTminecraft 编写，部分内容可能不符合编程规范，有意愿者请修改。
//关于有人质疑后门的事，请自行阅读代码，你要是能找出后门，我把电脑吃了。
//本模组永不收费，永远开源，许可证相关事项正在考虑。

//文件解释：本文件为模组主文件。


import baritone.api.utils.Helper;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import baritone.api.BaritoneAPI;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.fabricmc.loader.api.FabricLoader;
import com.google.gson.*;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.mojang.text2speech.Narrator.LOGGER;


public class RSTTestMod implements ClientModInitializer {
    
//    模组配置相关函数，模组配置采用.json储存
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configFile;
    private static JsonObject config;


    static private void loadConfig() {
        try {
            if (Files.exists(configFile)) {
                // 文件存在，读取它
                String content = Files.readString(configFile);
                config = GSON.fromJson(content, JsonObject.class);
            } else {
                // 文件不存在，创建默认配置
                config = new JsonObject();
                config.addProperty("FirstUse", true);
                config.addProperty("isAutoLog", true);
                config.addProperty("isAutoLogOnSeg1", false);
                config.addProperty("SegLength", DEFAULT_SEGMENT_LENGTH);
                saveConfig(); // 保存默认配置
            }
        } catch (IOException e) {
            throw new RuntimeException("无法加载配置: " + configFile, e);
        }
    }

    static private void saveConfig() {
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, GSON.toJson(config));
        } catch (IOException e) {
            throw new RuntimeException("无法保存配置: " + configFile, e);
        }
    }


//        模组配置读取与修改的函数实现
    // 提供获取配置值的方法
    static boolean getBoolean(String key, boolean defaultValue) {
        return config.has(key) ? config.get(key).getAsBoolean() : defaultValue;
    }

    static String getString(String key, String defaultValue) {
        return config.has(key) ? config.get(key).getAsString() : defaultValue;
    }

    static int getInt(String key, int defaultValue) {
        return config.has(key) ? config.get(key).getAsInt() : defaultValue;
    }

    // 提供设置配置值的方法（可选，如果你需要在运行时修改并保存）
    static void setBoolean(String key, boolean value) {
        config.addProperty(key, value);
        saveConfig();
    }

    static void setInt(String key, int value) {
        config.addProperty(key, value);
        saveConfig();
    }

    static void setString(String key, String value) {
        config.addProperty(key, value);
        saveConfig();
    }

//    简易队列机制的实现，用于延后或多次执行代码
    private static final PriorityQueue<ScheduledTask> tasks = new PriorityQueue<>(Comparator.<ScheduledTask>comparingInt(t -> t.nextRunTick).thenComparingInt(t -> -t.priority) // 同 tick 内按优先级降序
    );

    private static int currentTick = 0;


    private static void tick() {
        while (!tasks.isEmpty() && tasks.peek().nextRunTick <= currentTick) {
            ScheduledTask task = tasks.poll();
            // 执行
            task.action.accept(task, task.args);

            // 如果还要继续执行，重新入队
            if (task.repeatTimes > 0 || task.repeatTimes == -1) {
                if (task.repeatTimes > 0) {
                    task.repeatTimes--;
                }
                task.nextRunTick = currentTick + task.period;
                tasks.add(task);

            }
        }
    }

    private interface RSTConsumer {
        void accept(ScheduledTask self, Object[] args);
    }

    private interface SearchingConsumer {
        void accept(MinecraftClient client, int result, Object... args);
    }

    /**
     * 注册任务
     *
     * @param action      要执行的函数 (RSTConsumer)
     * @param period      周期 (tick)
     * @param repeatTimes 执行次数，-1 表示无限
     * @param delay       首次延迟 (tick)
     * @param priority    优先级，数值越大越优先
     * @param args        可变参数，传给 action
     */

    private static void scheduleTask(RSTConsumer action, int period, int repeatTimes, int delay, int priority, Object... args) {
        ScheduledTask task = new ScheduledTask(action, period, repeatTimes, delay, priority, args);
        task.nextRunTick = currentTick + delay;
        tasks.add(task);
    }

    private static class ScheduledTask {
        private final RSTConsumer action;
        private final int period;
        private int repeatTimes; // -1 = 无限
        private final int priority;
        private final Object[] args;

        private int nextRunTick;

        ScheduledTask(RSTConsumer action, int period, int repeatTimes, int delay, int priority, Object[] args) {
            this.action = action;
            this.period = Math.max(1, period);
            this.repeatTimes = repeatTimes;
            this.priority = priority;
            this.args = args;
            this.nextRunTick = delay;
        }
    }


//    储存当前模组状态

    enum ModStatuses {
        idle, success, failed, running, flying, canceled
    }

    static ModStatuses ModStatus = ModStatuses.idle;
    private static boolean protecting = false;
    private static int inFireTick = 0;


//        用于寻找可放置末影箱的位置
    private static BlockPos findPlaceTarget(ClientPlayerEntity player) {
        BlockPos origin = player.getBlockPos();
        World world = player.getWorld();

        // 搜索范围：以玩家为中心的 3×3×3 区域
        int radius = 1;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // 不能与玩家重合
                    if (0 == dx && 0 == dz) continue;
                    BlockPos target = origin.add(dx, dy, dz);

                    // 目标必须是空气或可替换方块（如草）
                    if (!world.getBlockState(target).isAir() && !world.getBlockState(target).isReplaceable()) continue;

                    // 下方必须是实心方块
                    BlockPos below = target.down();
                    if (!world.getBlockState(below).isSolidBlock(world, below)) continue;
                    // 上方必须是空气
                    BlockPos up = target.up();
                    if (!world.getBlockState(up).isAir()) continue;

                    // 构造命中信息：点击下面方块的顶面
                    return target;
                }
            }
        }
        return null;
    }

//    在物品栏中寻找物品
    private static int countItemInInventory(ClientPlayerEntity player, Item SearchingItem) {
        int count = 0;
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.main.size(); i++) {
            ItemStack stack = inventory.main.get(i);
            if (stack.getItem() == SearchingItem.asItem()) {
                count += stack.getCount();
            }
        }
        return count;
    }

//    让玩家看向某个坐标
    private static void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eyes = player.getEyePos();
        Vec3d dir = target.subtract(eyes);

        double distXZ = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dir.y, distXZ)));

        player.setYaw(yaw);
        player.setPitch(pitch);
    }

//    用于传输屏幕信息
    private static class RSTScreen {
        RSTScreen(boolean result, HandledScreen<?> handled, ScreenHandler handler) {
            this.result = result;
            this.handled = handled;
            this.handler = handler;

        }

        public boolean result;
        public HandledScreen<?> handled;
        public ScreenHandler handler;
    }

//    检查当前屏幕是不是容器屏幕
    private static RSTScreen ContainerScreenChecker(MinecraftClient client, String ContainerName, boolean checkNoEmpty) {
        Screen screen = client.currentScreen;
        if (!(screen instanceof HandledScreen<?> handled)) {
            // 当前不是带处理器的 GUI（不是容器界面） -> 重置等待状态
            return new RSTScreen(false, null, null);
        }

        boolean isObjectContainer = false;
        String titleStr = handled.getTitle().getString();
        if (ContainerName.equalsIgnoreCase(titleStr)) isObjectContainer = true;
        if (!isObjectContainer) {
            // 不是目标容器
            return new RSTScreen(false, null, null);
        }
        ScreenHandler handler = handled.getScreenHandler();
        if (checkNoEmpty) {
            int totalSlots = handler.slots.size();
            int containerSlots = totalSlots - 36; // 通常容器槽 = 总槽 - 玩家背包（36）
            if (containerSlots <= 0) containerSlots = 27; // 兜底为 27
            boolean anyNonEmpty = false;
            for (int i = 0; i < containerSlots; i++) {
                Slot s = handler.getSlot(i);
                if (s != null) {
                    ItemStack st = s.getStack();
                    if (st != null && !st.isEmpty()) {
                        anyNonEmpty = true;
                        break;
                    }
                }
            }
            if (!anyNonEmpty) {
                return new RSTScreen(false, null, null);
            }
        }
        return new RSTScreen(true, handled, handler);

    }

//    在潜影盒里寻找物品（补给箱）
//    多次循环以避免网络延迟造成影响
    private static void SearchShulkerInContainer(String ContainerName, MinecraftClient client, SearchingConsumer recall, Object... recallArgs) {
        scheduleTask((self, args) -> {
            if (ModStatus == ModStatuses.canceled) {
                ModStatus = ModStatuses.idle;
                self.repeatTimes = 0;
                return;
            }
            int result = SupplyFinder(client, ContainerName);
            if (result != -1 && result != -2) {
                self.repeatTimes = 0;
                recall.accept(client, result, recallArgs);
            } else if (result == -2) {
                self.repeatTimes = 0;
                if (client.player != null) {
                    client.player.sendMessage(Text.literal(ContainerName + ("中没有符合条件的补给。")), false);
                }
                recall.accept(client, -1, recallArgs);
            } else if (self.repeatTimes == 0) {
                if (client.player != null) {
                    client.player.sendMessage(Text.literal(ContainerName + ("中没有物品,或容器没有打开。")), false);
                }
                recall.accept(client, -1, recallArgs);
            }


        }, 1, 20, 1, 100, 0);
    }

//    检查潜影盒是否符合烟花条件
    private static boolean isValidContainerWithFirework(DefaultedList<ItemStack> inner) {
        int num = 0;

        // 遍历内存储的每个物品堆栈
        for (ItemStack stack : inner) {
            if (stack.isEmpty()) {
                continue;  // 跳过空的物品堆栈
            }

            // 判断烟花
            if (stack.getItem() == Items.FIREWORK_ROCKET) {
                num += stack.getCount();  // 累加烟花数量
            }
        }

        // 判断是否符合条件：超过20组烟花
        return num >= 21 * 64;
    }

//    检查潜影盒是否符合鞘翅条件
    private static boolean isValidContainerWithElytra(DefaultedList<ItemStack> inner) {
        int num = 0;
        // 遍历内存储的每个物品堆栈
        for (ItemStack stack : inner) {
            if (stack.isEmpty()) {
                continue;  // 跳过空的物品堆栈
            }
            // 判断烟花
            if (stack.getItem() == Items.ELYTRA) {
                if (stack.getDamage() > 15)
                    continue;
                var enchantments = stack.get(DataComponentTypes.ENCHANTMENTS);
                if (enchantments != null) {
                    var enc = enchantments.getEnchantments();
                    for (RegistryEntry<Enchantment> entry : enc) {
                        if (entry.getKey().isPresent() && entry.getKey().get() == Enchantments.UNBREAKING && EnchantmentHelper.getLevel(entry, stack) == 3) {
                            num += stack.getCount();  // 累加烟花数量
                        }

                    }
                }
            }
        }

        // 判断是否符合条件: 超过5个鞘翅
        return num >= 5;
    }

//    用于在末影箱中寻找符合条件的补给箱
    private static int SupplyFinder(MinecraftClient client, String searchingName) {
        if (client.player == null) {
            // 玩家不存在时重置状态
            return -1;
        }

        RSTScreen screen = ContainerScreenChecker(client, searchingName, true);
        if (!screen.result) {
            return -1;
        }
        // 到这里，准备读取目标容器的容器槽并打印所有目标物品内部物品
        StringBuilder sb = new StringBuilder();
        int totalSlots = screen.handler.slots.size();
        int containerSlots = totalSlots - 36;
        if (containerSlots <= 0) containerSlots = 27;
        int Slot = -2;
        for (int i = 0; i < containerSlots; i++) {
            Slot s = screen.handler.getSlot(i);
            if (s == null) continue;
            ItemStack stack = s.getStack();
            if (stack == null || stack.isEmpty()) continue;
            // 判断是否为目标物品（BlockItem）
            if (stack.getItem() instanceof BlockItem bi) {
                if (bi.getBlock() instanceof ShulkerBoxBlock) {
                    ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
                    sb.append(bi.getName().getString());
                    sb.append("\n");
                    if (container != null) {
                        DefaultedList<ItemStack> inner = DefaultedList.ofSize(27, ItemStack.EMPTY);
                        container.copyTo(inner); // 把 component 内容拷贝到列表（多余格子会被填空）
                        if (inner.isEmpty()) {
                            sb.append(("  (shulker is empty))")).append("\n");
                        } else {
                            boolean isEmpty = true;
                            for (ItemStack innerStack : inner) {
                                if (!innerStack.isEmpty()) {
                                    isEmpty = false;
                                    break;
                                }
                            }

//
                            if (isEmpty) sb.append(("  (shulker is empty)")).append("\n");
                            else {
                                boolean isValid = isValidContainerWithFirework(inner) && isValidContainerWithElytra(inner);
                                if (isValid) {
                                    sb.append(("  (slot ")).append(i).append((") - valid shulker")).append("\n");
                                    sb.append(("  Find shulker on slot")).append(i).append("\n");

                                    Slot = i;
                                    break;
//
                                } else
                                    sb.append(("  (slot ")).append(i).append((") - invalid shulker")).append("\n");


                            }
                        }
                    } else {
                        sb.append(("  (shulker is null...warning...)")).append("\n");
                    }
                }
            }
        }

        // 没找到任何目标物品
        if (sb.isEmpty()) {
            client.player.sendMessage(Text.literal(searchingName + ("中没有目标物品。")), false);
        } else {
            // 分行发送每一行，避免一次消息超长
            String[] lines = sb.toString().split("\n");
            for (String line : lines) {
                if (line == null || line.isEmpty()) continue;
                client.player.sendMessage(Text.literal(line), false);
            }
        }

        return Slot;
    }

//    将废弃的补给箱放回末影箱
    private static boolean putBackShulker(MinecraftClient client, String enderChestName, int shulkerSlotID, int EnderChestShulkerSlot) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }

        RSTScreen screen = ContainerScreenChecker(client, enderChestName, true);
        if (!screen.result) {
            return false;
        }
        client.interactionManager.clickSlot(screen.handler.syncId, shulkerSlotID, 0, SlotActionType.PICKUP, client.player);
        client.interactionManager.clickSlot(screen.handler.syncId, EnderChestShulkerSlot, 0, SlotActionType.PICKUP, client.player);
        client.interactionManager.clickSlot(screen.handler.syncId, shulkerSlotID, 0, SlotActionType.PICKUP, client.player);
        screen.handled.close();
        return true;
    }

//    用于从补给箱中拿出补给
    private static boolean putOutSupplyMain(MinecraftClient client, String searchingName) {
        if (client.player == null) {
            // 玩家不存在时重置状态
            return false;
        }

        RSTScreen screen = ContainerScreenChecker(client, searchingName, true);
        if (!screen.result) {
            return false;
        }
        for (int i = 0; i < 27; i++) {
            if (client.interactionManager != null) {
                if (screen.handler.getSlot(i).getStack().getItem() == Items.GOLDEN_CARROT) {
                    for (int j = 0; j < 9; j++) {
                        ItemStack s = client.player.getInventory().getStack(j);
                        if (s.getItem() == Items.GOLDEN_CARROT) {
                            client.player.sendMessage(Text.literal("z找到！"), false);
                            client.interactionManager.clickSlot(screen.handler.syncId, i, 0, SlotActionType.PICKUP, client.player);
                            client.interactionManager.clickSlot(screen.handler.syncId, 54 + j, 0, SlotActionType.PICKUP, client.player);
                            client.interactionManager.clickSlot(screen.handler.syncId, i, 0, SlotActionType.PICKUP, client.player);
                            break;
                        }
                    }
                    continue;
                }
                client.interactionManager.clickSlot(screen.handler.syncId, i, 0, SlotActionType.PICKUP, client.player);
                client.interactionManager.clickSlot(screen.handler.syncId, 27 + i, 0, SlotActionType.PICKUP, client.player);
                client.interactionManager.clickSlot(screen.handler.syncId, i, 0, SlotActionType.PICKUP, client.player);
            }
        }
        return true;
    }

    private static int putTryTimes = 0;

//    多次尝试拿出补给，避免网络延迟造成影响，并调用BaritoneAPI挖掘潜影盒和末影箱
    private static void putOutSupply(MinecraftClient client, BlockPos targetPos, String searchingName, BlockPos enderChestPos, String enderChestName, int ShulkerSlot, int EnderChestShulkerSlot) {
        scheduleTask((self, args) -> {
            if (ModStatus == ModStatuses.canceled) {
                ModStatus = ModStatuses.idle;
                return;
            }
            if (client.player == null || client.interactionManager == null) {
                client.inGameHud.getChatHud().addMessage(Text.literal(("client.player或client.interactionManager为null!")));
                ModStatus = ModStatuses.failed;
                return;
            }
            client.inGameHud.getChatHud().addMessage(Text.literal(("尝试放置补给箱成功，现在打开补给箱")));
            BlockHitResult chestHit = new BlockHitResult(Vec3d.ofCenter(targetPos), Direction.UP, targetPos, false);
            ActionResult result1 = client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, chestHit);
            client.player.swingHand(Hand.MAIN_HAND);
            if (result1.isAccepted()) {
                client.inGameHud.getChatHud().addMessage(Text.literal(("打开补给箱成功")));
                scheduleTask((self1, args1) -> {
                    if (ModStatus == ModStatuses.canceled) {
                        ModStatus = ModStatuses.idle;
                        self1.repeatTimes = 0;
                        return;
                    }
                    boolean result = putOutSupplyMain(client, searchingName);
                    if (result) {
                        self1.repeatTimes = 0;

                        client.inGameHud.getChatHud().addMessage(Text.literal(("取出补给物品成功")));
                        int count = 0;
                        PlayerInventory inventory = client.player.getInventory();
                        for (int i = 0; i < inventory.main.size(); i++) {
                            ItemStack stack = inventory.main.get(i);
                            Item item = stack.getItem();
                            if (item instanceof BlockItem && ((BlockItem) item).getBlock() instanceof ShulkerBoxBlock) {
                                count += stack.getCount();
                            }
                        }
                        if (client.world == null) {
                            client.player.sendMessage(Text.literal(("世界异常")));
                            ModStatus = ModStatuses.failed;
                            return;
                        }
                        Block block = client.world.getBlockState(targetPos).getBlock();
                        BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().mine(count + 1, block);
                        int finalCount = count;
                        putTryTimes = -1;
                        scheduleTask((s, a) -> {
                            if (ModStatus == ModStatuses.canceled) {
                                BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().cancel();
                                ModStatus = ModStatuses.idle;
                                s.repeatTimes = 0;
                                return;
                            }
                            if (!BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().isActive()) {
                                int newCount = 0;
                                for (int i = 0; i < inventory.main.size(); i++) {
                                    ItemStack stack = inventory.main.get(i);
                                    Item item = stack.getItem();
                                    if (item instanceof BlockItem && ((BlockItem) item).getBlock() instanceof ShulkerBoxBlock) {
                                        newCount += stack.getCount();
                                    }
                                }
                                if (newCount < finalCount + 1) {
                                    putTryTimes++;
                                    if (putTryTimes > 8) {
                                        client.player.sendMessage(Text.literal(("挖掘补给箱失败！")), false);
                                        s.repeatTimes = 0;
                                        ModStatus = ModStatuses.failed;
                                    }
                                    return;
                                }

                                client.player.sendMessage(Text.literal(("挖掘完毕，放回末影箱")), false);

                                lookAt(client.player, Vec3d.ofCenter(enderChestPos));
                                scheduleTask((ss, aa) -> {
                                    // 构造点击数据：点击下面方块的顶面
                                    if (ModStatus == ModStatuses.canceled) {
                                        ModStatus = ModStatuses.idle;
                                        self1.repeatTimes = 0;
                                        return;
                                    }
                                    if (client.player == null) {
                                        ModStatus = ModStatuses.failed;
                                        s.repeatTimes = 0;
                                        return;
                                    }
                                    BlockHitResult hitResult = new BlockHitResult(Vec3d.ofCenter(enderChestPos), Direction.UP, enderChestPos, false);
                                    if (client.interactionManager == null) {
                                        client.player.sendMessage(Text.literal(("client.interactionManager为null")), false);
                                        ModStatus = ModStatuses.failed;
                                        s.repeatTimes = 0;
                                        return;
                                    }
                                    // 交互放置
                                    ActionResult result2 = client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
                                    client.player.swingHand(Hand.MAIN_HAND);

                                    if (result2.isAccepted()) {
                                        client.player.sendMessage(Text.literal(("打开末影箱完毕")), false);
                                        scheduleTask((s3, a3) -> {
                                            if (ModStatus == ModStatuses.canceled) {
                                                s3.repeatTimes = 0;
                                                ModStatus = ModStatuses.idle;
                                                return;
                                            }
                                            if (putBackShulker(client, enderChestName, ShulkerSlot + 54, EnderChestShulkerSlot)) {
                                                client.player.sendMessage(Text.literal(("放回完毕")), false);
                                                s3.repeatTimes = 0;
                                                int enderCount = countItemInInventory(client.player, Items.ENDER_CHEST);
                                                int obsidianCount = countItemInInventory(client.player, Items.OBSIDIAN);

                                                BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().mine(obsidianCount + 1, client.world.getBlockState(enderChestPos).getBlock());
                                                scheduleTask((s2, a2) -> {
                                                    if (ModStatus == ModStatuses.canceled) {
                                                        ModStatus = ModStatuses.idle;
                                                        s2.repeatTimes = 0;
                                                        BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().cancel();
                                                        return;
                                                    }
                                                    int enderCount2 = countItemInInventory(client.player, Items.ENDER_CHEST);
                                                    if ((!BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().isActive()) || enderCount2 > enderCount) {
                                                        client.player.sendMessage(Text.literal(("补给任务圆满完成！")), false);
                                                        BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().cancel();
                                                        s2.repeatTimes = 0;
                                                        ModStatus = ModStatuses.success;

                                                    } else if (s2.repeatTimes == 0 || (!client.player.getBlockPos().isWithinDistance(enderChestPos, 5))) {
                                                        client.player.sendMessage(Text.literal(("挖取末影箱失败！危险！")), false);
                                                        BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().cancel();
                                                        s2.repeatTimes = 0;
                                                        ModStatus = ModStatuses.failed;
                                                    }

                                                }, 1, 100, 0, 100);

                                            } else if (s3.repeatTimes == 0) {
                                                client.player.sendMessage(Text.literal(("放回异常")), false);
                                                ModStatus = ModStatuses.failed;
                                            }


                                        }, 1, 20, 0, 80);
                                    } else {
                                        client.player.sendMessage(Text.literal(("打开失败")), false);
                                        ModStatus = ModStatuses.failed;
                                    }
                                }, 1, 0, 3, 10000000);
                                s.repeatTimes = 0;
                            } else if (s.repeatTimes == 0) {
                                client.player.sendMessage(Text.literal(("挖掘异常？取消挖掘")));
                                BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().cancel();
                                ModStatus = ModStatuses.failed;
                            }
                        }, 1, 40, 1, 20);

                    } else if (self1.repeatTimes == 0) {
                        client.player.sendMessage(Text.literal(("补给物品取出失败！")));
                        ModStatus = ModStatuses.failed;
                    }

                }, 1, 20, 1, 100, 0);

            } else {
                client.inGameHud.getChatHud().addMessage(Text.literal(("打开补给箱失败")));
                ModStatus = ModStatuses.failed;
            }
        }, 9, 0, 5, 100);
    }

//    检查装备是否符合要求，放下末影箱
    private static void autoPlace(MinecraftClient client, ScheduledTask task) {
        if (client.player != null) {
            BlockPos footBlock = client.player.getBlockPos();
            Vec3d CenterPos = new Vec3d(footBlock.getX() + 0.5, client.player.getY(), footBlock.getZ() + 0.5);
            Vec3d current = client.player.getPos();
            Vec3d delta = CenterPos.subtract(current);
            if (ModStatus == ModStatuses.canceled) {
                client.options.forwardKey.setPressed(false);
                ModStatus = ModStatuses.idle;
                task.repeatTimes = 0;
                return;
            }

            // 到达目标则停止
            if (Math.abs(delta.x) < 0.2 && Math.abs(delta.z) < 0.2) {
                task.repeatTimes = 0;
                client.options.forwardKey.setPressed(false);
                client.inGameHud.getChatHud().addMessage(Text.literal(("行走完成")));
                scheduleTask((ss, aa) -> {
                    ClientPlayerEntity player = client.player;
                    if (player == null || client.interactionManager == null) {
                        ModStatus = ModStatuses.failed;
                        return;
                    }

                    client.setScreen(new InventoryScreen(client.player));
                    Screen screen2 = client.currentScreen;
                    if (!(screen2 instanceof HandledScreen<?> handled2)) {
                        // 当前不是带处理器的 GUI（不是容器界面） -> 重置等待状态
                        client.player.sendMessage(Text.literal(("窗口异常！")), false);
                        ModStatus = ModStatuses.failed;
                        return;
                    }


                    ScreenHandler handler2 = handled2.getScreenHandler();
                    for (int i = 9; i < 36; i++) {
                        Slot s = handler2.getSlot(i);
                        if (s == null) continue;
                        ItemStack stack = s.getStack();
                        if (stack == null || stack.isEmpty()) continue;
                        Item item = stack.getItem();
                        while (!(item != Items.NETHERITE_PICKAXE && item != Items.DIAMOND_PICKAXE && item != Items.NETHERITE_SWORD && item != Items.DIAMOND_SWORD && item != Items.ENDER_CHEST && item != Items.GOLDEN_CARROT && item != Items.TOTEM_OF_UNDYING)) {
                            if (item == Items.NETHERITE_PICKAXE || item == Items.DIAMOND_PICKAXE) {
                                if (player.getInventory().getStack(0).getItem() == Items.DIAMOND_PICKAXE || player.getInventory().getStack(0).getItem() == Items.NETHERITE_PICKAXE) {
                                    break;
                                }
                                client.interactionManager.clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, player);
                                client.interactionManager.clickSlot(handler2.syncId, 36, 0, SlotActionType.PICKUP, player);
                                client.interactionManager.clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, player);

                            } else if (item == Items.NETHERITE_SWORD || item == Items.DIAMOND_SWORD) {
                                if (player.getInventory().getStack(1).getItem() == Items.DIAMOND_SWORD || player.getInventory().getStack(1).getItem() == Items.NETHERITE_SWORD) {
                                    break;
                                }
                                client.interactionManager.clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, player);
                                client.interactionManager.clickSlot(handler2.syncId, 37, 0, SlotActionType.PICKUP, player);
                                client.interactionManager.clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, player);
                            } else if (item == Items.ENDER_CHEST) {
                                client.interactionManager.clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, player);
                                client.interactionManager.clickSlot(handler2.syncId, 38, 0, SlotActionType.PICKUP, player);
                                client.interactionManager.clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, player);
                                if (stack.getItem() == Items.ENDER_CHEST) break;
                            } else if (item == Items.TOTEM_OF_UNDYING) {
                                if (player.getInventory().getStack(3).getItem() == Items.TOTEM_OF_UNDYING) {
                                    if (player.getInventory().getStack(4).getItem() == Items.TOTEM_OF_UNDYING) break;
                                    client.interactionManager.clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, player);
                                    client.interactionManager.clickSlot(handler2.syncId, 40, 0, SlotActionType.PICKUP, player);
                                    client.interactionManager.clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, player);
                                } else {
                                    client.interactionManager.clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, player);
                                    client.interactionManager.clickSlot(handler2.syncId, 39, 0, SlotActionType.PICKUP, player);
                                    client.interactionManager.clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, player);
                                }
                            } else {
                                client.interactionManager.clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, player);
                                client.interactionManager.clickSlot(handler2.syncId, 41, 0, SlotActionType.PICKUP, player);
                                client.interactionManager.clickSlot(handler2.syncId, i, 0, SlotActionType.PICKUP, player);
                                if (stack.getItem() == Items.GOLDEN_CARROT) break;

                            }
                            item = stack.getItem();
                        }
                    }

                    handled2.close();

                    int enderChestCount = 0;
                    boolean pickaxe = false;
                    boolean sword = false;
                    int goldenCarrotCount = 0;
                    for (int i = 0; i < 9; i++) {
                        ItemStack s = client.player.getInventory().getStack(i);
                        if (s.getItem() == Items.NETHERITE_PICKAXE || s.getItem() == Items.DIAMOND_PICKAXE)
                            pickaxe = true;
                        else if (s.getItem() == Items.NETHERITE_SWORD || s.getItem() == Items.DIAMOND_SWORD)
                            sword = true;
                        else if (s.getItem() == Items.ENDER_CHEST) enderChestCount += s.getCount();
                        else if (s.getItem() == Items.GOLDEN_CARROT) goldenCarrotCount += s.getCount();
                    }
                    if (!(pickaxe && sword && (enderChestCount > 1) && (goldenCarrotCount > 15))) {
                        client.inGameHud.getChatHud().addMessage(Text.literal(("快捷栏没有足够物资")));
                        ModStatus = ModStatuses.failed;
                        return;
                    }

                    // 1. 找末影箱槽位
                    String ContainerName = "";
                    int slot = -1;
                    for (int i = 0; i < 9; i++) {
                        ItemStack stack = player.getInventory().getStack(i);
                        if (!stack.isEmpty() && stack.getItem() == Items.ENDER_CHEST) {
                            slot = i;
                            ContainerName = stack.getItem().getName().getString();
                            break;
                        }
                    }
                    if (slot == -1) {
                        client.inGameHud.getChatHud().addMessage(Text.literal(("快捷栏没有末影箱")));
                        ModStatus = ModStatuses.failed;
                        return;
                    }


                    BlockPos targetPos = findPlaceTarget(player);
                    if (targetPos == null) {
                        client.inGameHud.getChatHud().addMessage(Text.literal(("附近没有合适的位置放置末影箱")));
                        ModStatus = ModStatuses.failed;
                        return;
                    }
                    if (client.getNetworkHandler() == null) {
                        client.inGameHud.getChatHud().addMessage(Text.literal(("client.getNetworkHandler()为null")));
                        ModStatus = ModStatuses.failed;
                        return;
                    }


                    player.getInventory().selectedSlot = slot;
                    client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));

                    // 让玩家看向目标方块中心
                    lookAt(player, Vec3d.ofCenter(targetPos));
                    String finalContainerName1 = ContainerName;
                    scheduleTask((ss2, aa2) -> {
                        // 构造点击数据：点击下面方块的顶面
                        BlockPos support = targetPos.down();
                        Vec3d hitPos = Vec3d.ofCenter(support).add(0, 0.5, 0);
                        BlockHitResult hitResult = new BlockHitResult(hitPos, Direction.UP, support, false);

                        // 交互放置
                        ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
                        player.swingHand(Hand.MAIN_HAND);

                        if (result.isAccepted()) {
                            client.inGameHud.getChatHud().addMessage(Text.literal(("放置成功")));
                            scheduleTask((self, args) -> {
                                if (ModStatus == ModStatuses.canceled) {
                                    ModStatus = ModStatuses.idle;
                                    return;
                                }
                                if (client.interactionManager == null) {
                                    ModStatus = ModStatuses.failed;
                                    return;
                                }
                                player.swingHand(Hand.MAIN_HAND);
                                client.inGameHud.getChatHud().addMessage(Text.literal(("尝试放置末影箱成功，现在打开末影箱")));
                                BlockHitResult chestHit = new BlockHitResult(Vec3d.ofCenter(targetPos), Direction.UP, targetPos, false);
                                ActionResult result1 = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, chestHit);
                                player.swingHand(Hand.MAIN_HAND);
                                if (result1.isAccepted()) {
                                    client.inGameHud.getChatHud().addMessage(Text.literal(("打开容器成功:") + args[0]));
                                    SearchShulkerInContainer((String) args[0], client, (cli, res, a1) -> {
                                        if (ModStatus == ModStatuses.canceled) {
                                            ModStatus = ModStatuses.idle;
                                            return;
                                        }
                                        if (res == -1) {
                                            client.inGameHud.getChatHud().addMessage(Text.literal(("寻找补给失败！")));
                                            ModStatus = ModStatuses.failed;
                                        } else {
                                            if (client.getNetworkHandler() == null || client.interactionManager == null) {
                                                client.inGameHud.getChatHud().addMessage(Text.literal(("client.getNetworkHandler()为null")));
                                                ModStatus = ModStatuses.failed;
                                                return;
                                            }
                                            client.inGameHud.getChatHud().addMessage(Text.literal(("寻找补给成功！")));
                                            //                                    到这里，准备取出潜影盒
                                            RSTScreen screen = ContainerScreenChecker(client, (String) args[0], false);
                                            if (!screen.result) {
                                                // 不是目标容器
                                                client.inGameHud.getChatHud().addMessage(Text.literal(("界面异常！")));
                                                ModStatus = ModStatuses.failed;
                                                return;
                                            }

                                            int slot2 = -1;

                                            for (int j = 0; j < 9; j++) {
                                                ItemStack stack2 = client.player.getInventory().getStack(j);
                                                if (stack2.isEmpty() || (stack2.getItem() != Items.ENDER_CHEST && stack2.getItem() != Items.DIAMOND_PICKAXE && stack2.getItem() != Items.NETHERITE_PICKAXE && stack2.getItem() != Items.DIAMOND_SWORD && stack2.getItem() != Items.NETHERITE_SWORD && stack2.getItem() != Items.GOLDEN_CARROT && stack2.getItem() != Items.TOTEM_OF_UNDYING)) {
                                                    slot2 = j;
                                                    break;
                                                }
                                            }

                                            if (client.interactionManager != null && slot2 != -1) {
                                                client.interactionManager.clickSlot(screen.handler.syncId, res, 0, SlotActionType.PICKUP, client.player);
                                                client.interactionManager.clickSlot(screen.handler.syncId, 54 + slot2, 0, SlotActionType.PICKUP, client.player);
                                                client.interactionManager.clickSlot(screen.handler.syncId, res, 0, SlotActionType.PICKUP, client.player);
                                                client.inGameHud.getChatHud().addMessage(Text.literal(("取出成功！")));
                                                screen.handled.close();
                                                int finalSlot = slot2;
                                                scheduleTask((self2, args2) -> {
                                                    if (ModStatus == ModStatuses.canceled) {
                                                        ModStatus = ModStatuses.idle;
                                                        return;
                                                    }
                                                    BlockPos targetPosShulker = findPlaceTarget(player);
                                                    if (targetPosShulker == null) {
                                                        client.inGameHud.getChatHud().addMessage(Text.literal(("附近没有合适的位置放置补给！")));
                                                        ModStatus = ModStatuses.failed;
                                                        return;
                                                    }
                                                    player.getInventory().selectedSlot = (int) (args2[0]);
                                                    client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket((int) (args2[0])));
                                                    ItemStack s = client.player.getInventory().getStack((int) (args2[0]));
                                                    String SearchingNameShulker;
                                                    if (s.getComponents().contains(DataComponentTypes.CUSTOM_NAME)) {

                                                        SearchingNameShulker = Objects.requireNonNull(s.get(DataComponentTypes.CUSTOM_NAME)).getString();
                                                    } else {
                                                        SearchingNameShulker = Items.SHULKER_BOX.getName().getString();
                                                    }
                                                    lookAt(player, Vec3d.ofCenter(targetPosShulker));

                                                    // 构造点击数据：点击下面方块的顶面
                                                    BlockPos supportShulker = targetPosShulker.down();
                                                    Vec3d hitPosShulker = Vec3d.ofCenter(supportShulker).add(0, 0.5, 0);
                                                    BlockHitResult hitResultShulker = new BlockHitResult(hitPosShulker, Direction.UP, supportShulker, false);
                                                    if (client.interactionManager == null) {
                                                        client.inGameHud.getChatHud().addMessage(Text.literal(("interactionManager为null")));
                                                        ModStatus = ModStatuses.failed;
                                                    }
                                                    // 交互放置
                                                    ActionResult resultShulker = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResultShulker);
                                                    player.swingHand(Hand.MAIN_HAND);

                                                    if (resultShulker.isAccepted()) {
                                                        client.inGameHud.getChatHud().addMessage(Text.literal(("放置补给成功")));
                                                        putOutSupply(client, targetPosShulker, SearchingNameShulker, targetPos, finalContainerName1, finalSlot, res);
                                                    } else {
                                                        client.inGameHud.getChatHud().addMessage(Text.literal(("放置补给失败")));
                                                        ModStatus = ModStatuses.failed;
                                                    }
                                                }, 10, 0, 5, 50, slot2);
                                            } else {
                                                client.inGameHud.getChatHud().addMessage(Text.literal(("取出失败！可能由于界面异常或快捷栏没有合适位置放置补给！")));
                                                ModStatus = ModStatuses.failed;
                                            }
                                        }


                                    });
                                } else {
                                    client.inGameHud.getChatHud().addMessage(Text.literal(("打开失败")));
                                    ModStatus = ModStatuses.failed;
                                }
                            }, 9, 0, 5, 100, finalContainerName1);

                        } else {
                            client.inGameHud.getChatHud().addMessage(Text.literal(("放置失败")));
                            ModStatus = ModStatuses.failed;
                        }
                    }, 1, 0, 3, 1000000);

                }, 1, 0, 2, 10000000);
                return;
            }

            // 调整朝向
            double yaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
            client.player.setYaw((float) yaw);

            // 模拟按下 W
            client.options.forwardKey.setPressed(true);
        }
    }

    static final int DEFAULT_SEGMENT_LENGTH = 140000; // 每段路径长度

//    任务失败处理函数
    private static void taskFailed(MinecraftClient client, boolean isAutoLog, String str, boolean isAutoLogOnSeg1, int seg) {
        if ((seg == -1 && isAutoLogOnSeg1) || (seg != -1 && isAutoLog)) {
            MutableText text = Text.literal(("[RSTAutoLog] "));
            text.append(Text.literal(str));
            if (client.player != null) {
                client.player.networkHandler.onDisconnect(new DisconnectS2CPacket(text));
            }
        } else if (client.player != null) {
            client.player.sendMessage(Text.literal("§4任务结束。" + str + "§r"), false);
        }
        ModStatus = ModStatuses.idle;

    }

//    计算分段
    static List<Vec3i> calculatePathSegments(MinecraftClient client, double targetX, double targetZ, double segLen, boolean hasFirst) {
        List<Vec3i> segmentEndpoints = new ArrayList<>();
        if (client.player == null) {
            return new ArrayList<>();
        }
        // 获取玩家当前位置（只使用X和Z）
        Vec3d playerPos = client.player.getPos();
        if (hasFirst) segmentEndpoints.add(client.player.getBlockPos());
        double startX = playerPos.x;
        double startZ = playerPos.z;

        // 计算到目标点的方向向量（只考虑XZ平面）
        double dx = targetX - startX;
        double dz = targetZ - startZ;

        // 计算总距离（在XZ平面上）
        double totalDistance = Math.sqrt(dx * dx + dz * dz);

        // 如果总距离为0，直接返回空列表
        if (totalDistance == 0) return segmentEndpoints;

        // 计算方向向量的单位向量
        double unitX = dx / totalDistance;
        double unitZ = dz / totalDistance;

        // 计算分段数量
        int segments = (int) Math.ceil(totalDistance / segLen);

        // 生成每个分段的终点坐标（Y坐标设为0）
        for (int i = 1; i <= segments; i++) {
            double currentSegmentLength = Math.min(i * segLen, totalDistance);

            double endX = startX + unitX * currentSegmentLength;
            double endZ = startZ + unitZ * currentSegmentLength;

            segmentEndpoints.add(new Vec3i((int) endX, 0, (int) endZ));
        }

        return segmentEndpoints;
    }

//    有关鞘翅飞行的各种状态
    private static boolean isEating = false;
    private static boolean arrived = false;
    private static boolean isJumping = false;
    private static int jumpingTimes = 0;
    private static int LastJumpingTick = 0;
    private static int SegFailed = 0;
    private static int LastSegFailedTick = 0;
    private static BlockPos LastPos;
    private static boolean noFirework = false;
    private static boolean isJumpBlockedByBlock = false;
    private static BlockPos oldPos = null;
    private static int spinTimes = 0;
    private static boolean waitReset = false;
    private static int elytraCount;
    private static int FireworkCount;
    private static BlockPos LastDebugPos = null;

//    用于检查玩家头顶有无方块阻挡玩家起跳
    public static List<BlockPos> getPotentialJumpBlockingBlocks(int checkY) {
        List<BlockPos> nonAirBlocks = new ArrayList<>();

        // 获取客户端和玩家实例
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            return nonAirBlocks;
        }

        World world = client.world;
        ClientPlayerEntity player = client.player;

        // 获取玩家头部位置（眼睛位置）
        Vec3d playerPos = player.getPos();
        double headX = playerPos.x;
        double headZ = playerPos.z;
        double headY = playerPos.y + player.getStandingEyeHeight();

        // 计算头顶上方一个方块层的Y坐标
        int aboveY = (int) Math.floor(headY) + 1;

        // 获取头部所在方块的整数坐标（向下取整）
        int baseX = (int) Math.floor(headX);
        int baseZ = (int) Math.floor(headZ);

        // 计算头部在方块内的相对偏移量（0-1之间）
        double offsetX = headX - baseX;
        double offsetZ = headZ - baseZ;

        // 创建要检查的方块位置集合（使用Set避免重复）
        Set<BlockPos> blocksToCheck = new HashSet<>();

        // 总是检查头部正上方的方块
        blocksToCheck.add(new BlockPos(baseX, aboveY, baseZ));

        // 根据X方向偏移决定是否检查相邻方块
        if (offsetX > 0.7) {  // 靠近东侧边缘
            blocksToCheck.add(new BlockPos(baseX + 1, aboveY, baseZ));
        } else if (offsetX < 0.3) {  // 靠近西侧边缘
            blocksToCheck.add(new BlockPos(baseX - 1, aboveY, baseZ));
        }

        // 根据Z方向偏移决定是否检查相邻方块
        if (offsetZ > 0.7) {  // 靠近南侧边缘
            blocksToCheck.add(new BlockPos(baseX, aboveY, baseZ + 1));
        } else if (offsetZ < 0.3) {  // 靠近北侧边缘
            blocksToCheck.add(new BlockPos(baseX, aboveY, baseZ - 1));
        }

        // 检查对角线方向（当同时靠近两个方向的边缘时）
        if (offsetX > 0.7 && offsetZ > 0.7) {  // 东南角
            blocksToCheck.add(new BlockPos(baseX + 1, aboveY, baseZ + 1));
        } else if (offsetX > 0.7 && offsetZ < 0.3) {  // 东北角
            blocksToCheck.add(new BlockPos(baseX + 1, aboveY, baseZ - 1));
        } else if (offsetX < 0.3 && offsetZ > 0.7) {  // 西南角
            blocksToCheck.add(new BlockPos(baseX - 1, aboveY, baseZ + 1));
        } else if (offsetX < 0.3 && offsetZ < 0.3) {  // 西北角
            blocksToCheck.add(new BlockPos(baseX - 1, aboveY, baseZ - 1));
        }

        // 检查每个方块，只返回不是空气的方块
        for (BlockPos pos : blocksToCheck) {
            for (int i = 0; i < checkY; i++) {
                if (world.isInBuildLimit(pos.add(0, i, 0)) && !world.isAir(pos.add(0, i, 0))) {
                    nonAirBlocks.add(pos);
                }
            }
        }

        return nonAirBlocks;
    }

//    玩家飞行时运行该函数，保护玩家并在必要时获取补给
//    1.自动起跳
//    2.自动逃离岩浆
//    3.自动进食
//    4.过于危险时（情况较少）log
    private static void segmentsMainElytra(List<Vec3i> segments, int nowIndex, MinecraftClient client, boolean isAutoLog, boolean isAutoLogOnSeg1) {
        arrived = false;
        isJumping = false;
        jumpingTimes = 0;
        ModStatus = ModStatuses.flying;
        LastJumpingTick = 0;
        SegFailed = 0;
        LastSegFailedTick = -100000;
        LastPos = null;
        noFirework = false;
        isJumpBlockedByBlock = false;
        spinTimes = 0;
        waitReset = false;
        elytraCount = 0;
        FireworkCount = 0;

        BaritoneAPI.getSettings().elytraAutoJump.value = false;
        BaritoneAPI.getSettings().logger.value = ((var1x) -> {
            try {
                MessageIndicator var2 = BaritoneAPI.getSettings().useMessageTag.value ? Helper.MESSAGE_TAG : null;
                MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(var1x, null, var2);
                if (MinecraftClient.getInstance().player != null && (var1x.getString().contains("Failed to compute path to destination") || var1x.getString().contains("Failed to recompute segment") || var1x.getString().contains("Failed to compute next segment"))) {
                    if (currentTick - LastSegFailedTick < 5) SegFailed++;
                    else SegFailed = 1;
                    LastSegFailedTick = currentTick;
                    waitReset = true;
                }
            } catch (Throwable var3) {
                LOGGER.warn("Failed to log message to chat: {}", var1x.getString(), var3);
            }
        });
        if (client.player == null) {
            taskFailed(client, isAutoLog, ("飞行任务失败！player为null！"), isAutoLogOnSeg1, nowIndex);
            return;
        }

        oldPos = client.player.getBlockPos();
        BlockPos segPos = oldPos;
        client.player.setPitch(-30);
        BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().pathTo(new BlockPos(segments.get(nowIndex)));
        scheduleTask((s2, a2) -> {

            client.options.jumpKey.setPressed(true);
            scheduleTask((self2, args2) -> {

                client.options.jumpKey.setPressed(false);
                scheduleTask((s3, a3) -> client.options.jumpKey.setPressed(true), 1, 0, 1, 100000);

                scheduleTask((s, a) -> client.options.jumpKey.setPressed(false), 1, 0, 3, 25);
                if (client.player == null || client.getNetworkHandler() == null || client.interactionManager == null || client.world == null) {
                    taskFailed(client, isAutoLog, ("飞行任务失败！null异常！"), isAutoLogOnSeg1, nowIndex);
                    return;
                }
                client.player.sendMessage(Text.literal(("elytra任务创建！")), false);
                scheduleTask((self, args) -> {
                    if (client.player == null) {
                        taskFailed(client, isAutoLog, ("飞行任务失败！player为null！"), isAutoLogOnSeg1, nowIndex);
                        self.repeatTimes = 0;
                        return;
                    }
                    if (ModStatus == ModStatuses.canceled) {
                        ModStatus = ModStatuses.idle;
                        self.repeatTimes = 0;
                        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
                        client.player.sendMessage(Text.literal(("任务取消")), false);
                        return;
                    }
                    boolean result = BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().isActive();
                    if (!result && !isJumpBlockedByBlock) {
//                        此时，到达阶段目的地，准备获取补给
                        if (client.player.getBlockPos().isWithinDistance(oldPos, 100)) {
                            client.player.sendMessage(Text.literal(("距离异常！")), false);
                            taskFailed(client, isAutoLog, ("飞行任务失败！距离异常！"), isAutoLogOnSeg1, nowIndex);
                            self.repeatTimes = 0;
                            return;
                        }
                        self.repeatTimes = 0;
                        arrived = true;
                        if (nowIndex == segments.size() - 1) {
                            BaritoneAPI.getSettings().logger.value = BaritoneAPI.getSettings().logger.defaultValue;
                            client.player.sendMessage(Text.literal(("到达目的地！本段飞行距离：") + Math.sqrt(client.player.getBlockPos().getSquaredDistance(segPos))), false);
                        } else {
                            client.player.sendMessage(Text.literal(("到达阶段目的地：") + nowIndex + ("本段飞行距离:") + Math.sqrt(client.player.getBlockPos().getSquaredDistance(segPos))), false);
                            client.player.sendMessage(Text.literal(("开始下一段补给任务：") + (nowIndex + 1)), false);
                            scheduleTask((s3, a3) -> {
                                if (client.player == null || s3.repeatTimes == 0) {
                                    taskFailed(client, isAutoLog, ("开启补给任务失败！"), isAutoLogOnSeg1, nowIndex);
                                    s3.repeatTimes = 0;
                                    return;
                                }
                                if (client.player.getVelocity().getX() < 0.01 && client.player.getVelocity().getZ() < 0.01) {
                                    segmentsMainSupply(segments, nowIndex + 1, client, isAutoLog, isAutoLogOnSeg1);
                                    s3.repeatTimes = 0;
                                }
                            }, 1, 60, 5, 20000);
                        }
                    } else {
//                        自动起跳次数过多，可能遭遇意外情况，auto log
                        if (jumpingTimes > 7) {
                            client.player.sendMessage(Text.literal(("自动起跳数量过多，可能是baritone异常！")), false);
                            taskFailed(client, isAutoLog, ("飞行任务失败！自动退出！自动起跳数量过多，可能是baritone异常！"), isAutoLogOnSeg1, nowIndex);
                            self.repeatTimes = 0;
                            return;

                        }

//                        玩家掉落在地上时自动起跳，继续鞘翅飞行
                        if (!arrived && !isJumping && !isJumpBlockedByBlock && !isEating && client.player.isOnGround() && !client.player.isFallFlying() && !client.player.isInLava() && client.player.getVelocity().getX() < 0.01 && client.player.getVelocity().getZ() < 0.01) {
                            List<BlockPos> bp = getPotentialJumpBlockingBlocks(1);
                            if (!bp.isEmpty()) {
//                                玩家头顶有方块阻挡，调用baritone API清除
                                isJumpBlockedByBlock = true;
                                BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("stop");
                                BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess().clearArea(new BlockPos((int) Math.floor(client.player.getPos().getX() - 0.3), bp.getFirst().getY(), (int) Math.floor(client.player.getPos().getZ() - 0.3)), new BlockPos((int) Math.floor(client.player.getPos().getX() - 0.3) + 1, bp.getFirst().getY() + 1, (int) Math.floor(client.player.getPos().getZ() - 0.3) + 1));
                                scheduleTask((s, a) -> {
                                    if (client.player == null) {
                                        s.repeatTimes = 0;
                                        return;
                                    }
                                    if (!BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess().isActive()) {

                                        s.repeatTimes = 0;
                                        oldPos = client.player.getBlockPos();
                                        BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().pathTo(new BlockPos(segments.get(nowIndex)));
                                        scheduleTask((s3, a3) -> isJumpBlockedByBlock = false, 1, 0, 10, 100000000);
                                        jumpingTimes++;
                                    } else if (s.repeatTimes == 0) {
                                        client.player.sendMessage(Text.literal(("挖掘异常！")), false);

                                        taskFailed(client, isAutoLog, ("飞行任务失败！自动退出！挖掘异常！"), isAutoLogOnSeg1, nowIndex);
                                        self.repeatTimes = 0;

                                    }
                                }, 1, 200, 0, 1000);
                                return;

                            }
                            isJumping = true;
                            if (currentTick - LastJumpingTick < 100) {
                                jumpingTimes++;
                            } else {
                                jumpingTimes = 0;
                            }
                            LastJumpingTick = currentTick;
//                            玩家暂时无法起跳，尝试使用烟花辅助起跳
                            if (jumpingTimes > 4 && getPotentialJumpBlockingBlocks(8).isEmpty()) {
                                client.player.sendMessage(Text.literal(("自动烟花起跳！") + jumpingTimes), false);
                                client.player.setPitch(-90);
                                client.options.jumpKey.setPressed(true);
                                scheduleTask((ss, aa) -> client.options.jumpKey.setPressed(false), 1, 0, 1, 100000000);
                                double y = client.player.getPos().getY();
                                scheduleTask((s4, a4) -> {
                                    if (client.player == null) {
                                        s4.repeatTimes = 0;
                                        return;
                                    }
                                    if (client.player.getPos().getY() > y + 1 || s4.repeatTimes == 0) {
                                        client.options.jumpKey.setPressed(true);
                                        scheduleTask((s3, a3) -> client.options.jumpKey.setPressed(false), 1, 0, 1, 100000);

                                        scheduleTask((s, a) -> {
                                            if (client.player == null) return;
                                            PlayerInventory inv = client.player.getInventory();
                                            int slots = -1;
                                            for (int i = 0; i < 8; i++) {
                                                ItemStack s5 = inv.getStack(i);
                                                if (s5.isEmpty() || s5.getItem() == Items.FIREWORK_ROCKET) slots = i;
                                            }
                                            if (slots == -1) {

                                                client.player.sendMessage(Text.literal(("烟花异常！")), false);
                                                taskFailed(client, isAutoLog, ("飞行任务失败！自动退出！找不到烟花！"), isAutoLogOnSeg1, nowIndex);
                                                self.repeatTimes = 0;
                                                return;
                                            } else {
                                                client.player.getInventory().selectedSlot = slots;
                                                client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slots));
                                                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                                                client.player.sendMessage(Text.literal(("已使用烟花！")), false);
                                            }
                                            isJumping = false;
                                        }, 1, 0, 2, 25);
                                        s4.repeatTimes = 0;
                                    }
                                }, 1, 7, 1, 25);


                            } else {
                                client.player.sendMessage(Text.literal(("自动起跳！") + jumpingTimes), false);
                                client.player.setPitch(-30);
                                client.options.jumpKey.setPressed(true);
                                scheduleTask((s4, a4) -> {
                                    if (client.player == null) {
                                        s4.repeatTimes = 0;
                                        return;
                                    }
                                    if (client.player.getVelocity().getY() < -0.1 || s4.repeatTimes == 0) {

                                        client.options.jumpKey.setPressed(false);
                                        scheduleTask((s3, a3) -> client.options.jumpKey.setPressed(true), 1, 0, 1, 100000);

                                        scheduleTask((s, a) -> {
                                            client.options.jumpKey.setPressed(false);
                                            isJumping = false;
                                        }, 1, 0, 3, 25);
                                        s4.repeatTimes = 0;
                                    }
                                }, 1, 7, 0, 25);
                            }

                        }

//                        在岩浆中吗？
                        if (client.player.isInLava()) {
                            inFireTick++;
                        } else {
                            inFireTick = 0;
                        }

                        if (inFireTick > 20 || (inFireTick > 5 && !client.player.isFallFlying())) {
//                            位于岩浆中？自动逃离岩浆

                            inFireTick = -100;
                            client.options.jumpKey.setPressed(true);
                            scheduleTask((ss, aa) -> {
                                client.options.jumpKey.setPressed(false);
                                if (client.player != null && client.interactionManager != null) {
                                    client.player.sendMessage(Text.literal(("位于岩浆中，鞘翅打开")), false);
                                    client.player.setPitch(-90);
                                    PlayerInventory inv = client.player.getInventory();
                                    int slots = -1;
                                    for (int i = 0; i < 8; i++) {
                                        ItemStack s = inv.getStack(i);
                                        if (s.isEmpty() || s.getItem() == Items.FIREWORK_ROCKET) slots = i;
                                    }
                                    if (slots == -1) {

                                        client.player.sendMessage(Text.literal(("烟花异常！")), false);
                                        taskFailed(client, isAutoLog, ("飞行任务失败！自动退出！找不到烟花！"), isAutoLogOnSeg1, nowIndex);
                                        self.repeatTimes = 0;
                                    } else {
                                        client.player.getInventory().selectedSlot = slots;
                                        client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slots));
                                        client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                                        client.player.sendMessage(Text.literal(("已使用烟花！")), false);

                                        scheduleTask((s4, a4) -> {
                                            if (client.player == null) {
                                                s4.repeatTimes = 0;
                                                return;
                                            }
                                            if (client.player.isInLava()) {

                                                client.player.sendMessage(Text.literal(("无法逃离岩浆！")), false);
                                                taskFailed(client, isAutoLog, ("飞行任务失败！自动退出！逃离岩浆失败！"), isAutoLogOnSeg1, nowIndex);
                                                self.repeatTimes = 0;
                                            }


                                        }, 1, 0, 40, 10);
                                    }

                                }
                            }, 1, 0, 3, 1000000);
                        }
//                        baritone寻路失败，等待重置状态或auto log
                        if (SegFailed > 25) {
                            if (SegFailed > 30) {
                                client.player.sendMessage(Text.literal(("SegFailed！")), false);
                                taskFailed(client, isAutoLog, ("飞行任务失败！自动退出！baritone寻路异常？！"), isAutoLogOnSeg1, nowIndex);
                                self.repeatTimes = 0;
                                return;
                            } else if (waitReset) {
                                BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().resetState();
                                client.player.sendMessage(Text.literal(("SegFailed！正在重置baritone!")), false);
                                waitReset = false;
                            }
                        }
//                        玩家是不是陷入了原地绕圈？尝试重置baritone或auto log
                        if (currentTick % 1000 == 0) {
                            if (LastPos != null && client.player.getBlockPos().isWithinDistance(LastPos, 25)) {
                                client.player.sendMessage(Text.literal(("SegFailed！spin!")), false);
                                if (spinTimes > 4) {
                                    taskFailed(client, isAutoLog, ("飞行任务失败！自动退出！baritone寻路异常？！疑似原地转圈"), isAutoLogOnSeg1, nowIndex);
                                    self.repeatTimes = 0;
                                } else {
                                    spinTimes++;
                                    BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().resetState();
                                }
                                return;
                            }
                            LastPos = client.player.getBlockPos();

                        }
//                        检查玩家烟花数量
                        PlayerInventory inv = client.player.getInventory();
                        int count = 0;
                        int slots = 0;
                        for (int i = 0; i < 9; i++) {
                            ItemStack s = inv.getStack(i);
                            if (s.isEmpty() || s.getItem() == Items.FIREWORK_ROCKET) slots++;
                            if (s.getItem() == Items.FIREWORK_ROCKET) count += s.getCount();
                        }
                        if (slots == 0) {
                            client.player.sendMessage(Text.literal(("烟花异常！")), false);
                            taskFailed(client, isAutoLog, ("飞行任务失败！自动退出！任务栏没有空位放烟花了！"), isAutoLogOnSeg1, nowIndex);
                            self.repeatTimes = 0;
                            return;
                        } else if (count < 64) {
//                            烟花数量少，从背包里拿一些
                            client.setScreen(new InventoryScreen(client.player));
                            Screen screen = client.currentScreen;
                            if (!(screen instanceof HandledScreen<?> handled)) {
                                // 当前不是带处理器的 GUI（不是容器界面） -> 重置等待状态
                                client.player.sendMessage(Text.literal(("窗口异常！")), false);
                                taskFailed(client, isAutoLog, ("飞行任务失败！自动退出！窗口异常！"), isAutoLogOnSeg1, nowIndex);
                                self.repeatTimes = 0;
                                return;
                            }
                            int c = 0;
                            ScreenHandler handler = handled.getScreenHandler();
                            for (int i = 9; i < 36; i++) {
                                Slot s = handler.getSlot(i);
                                if (s == null) continue;
                                ItemStack stack = s.getStack();
                                if (stack == null || stack.isEmpty()) continue;
                                Item item = stack.getItem();

                                if (item == Items.FIREWORK_ROCKET) {
                                    c += stack.getCount();
                                    client.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                                }
                            }
                            int d = 0;
                            for (int i = 0; i < 46; i++) {
                                Slot s = handler.getSlot(i);
                                if (s == null) continue;
                                ItemStack stack = s.getStack();
                                if (stack == null || stack.isEmpty()) continue;
                                Item item = stack.getItem();
                                if (item == Items.ELYTRA) {
                                    d += stack.getMaxDamage() - stack.getDamage();
                                }
                            }

                            handled.close();

                            if (c <= 128 && !noFirework) {
                                if (client.player.getBlockPos().isWithinDistance(segments.get(nowIndex), (getInt("SegLength", DEFAULT_SEGMENT_LENGTH) - 3500) * 0.6)) {
                                    noFirework = true;
                                    client.player.sendMessage(Text.literal(("烟花不足，提前寻找位置降落！")));
                                } else {

                                    client.player.sendMessage(Text.literal(("烟花不足，以飞行路程不足总路程60%，可能是baritone设置错误？请检查！")));
                                    taskFailed(client, isAutoLog, ("飞行任务失败！自动退出！烟花不足，以飞行路程不足总路程60%，可能是baritone设置错误？请检查！"), isAutoLogOnSeg1, nowIndex);
                                    self.repeatTimes = 0;
                                    return;

                                }
                            }

//                            统计玩家烟花消耗速度，调试用
                            if (elytraCount != 0 && FireworkCount != 0 && LastDebugPos != null) {
                                client.player.sendMessage(Text.literal(("目前鞘翅剩余耐久：") + d + ("鞘翅预计可以飞行") + 2160.0 / (elytraCount - d) * Math.sqrt(client.player.getBlockPos().getSquaredDistance(LastDebugPos))));
                                client.player.sendMessage(Text.literal(("目前烟花剩余数量：") + (c + count) + ("烟花预计可以飞行") + 1344.0 / (FireworkCount - c - count) * Math.sqrt(client.player.getBlockPos().getSquaredDistance(LastDebugPos))));
                            } else {
                                elytraCount = d;
                                FireworkCount = c + count;
                                LastDebugPos = client.player.getBlockPos();
                            }
                        }
                        int slot2 = -1;

//                        自动进食，恢复血量
                        if ((!isEating) && (!client.player.isInLava()) && client.player.getVelocity().length() > 1.4 && (client.player.getHungerManager().getFoodLevel() < 16 || (client.player.getHealth() < 15 && client.player.getHungerManager().getFoodLevel() < 20))) {
                            client.player.sendMessage(Text.literal(("准备食用")), false);
                            for (int i = 0; i < 8; i++) {
                                ItemStack s = client.player.getInventory().getStack(i);
                                Item item = s.getItem();
                                if (item == Items.GOLDEN_CARROT) {
                                    slot2 = i;
                                    break;
                                }
                            }
                            if (slot2 == -1) {
                                client.player.sendMessage(Text.literal(("无食物了！！")), false);
                                taskFailed(client, isAutoLog, ("飞行任务失败！自动退出！没有足够的食物了！"), isAutoLogOnSeg1, nowIndex);
                                self.repeatTimes = 0;
                                return;
                            } else {
                                client.player.getInventory().selectedSlot = slot2;
                                client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slots));
                                client.options.useKey.setPressed(true);
                                isEating = true;
                                scheduleTask((s5, a5) -> {
                                    if (client.player == null) {
                                        client.options.useKey.setPressed(false);
                                        isEating = false;
                                        s5.repeatTimes = 0;
                                        return;
                                    }
                                    if (client.player.getVelocity().length() < 0.9) {
//                                        速度过低，放弃吃食物，防止影响baritone寻路

                                        client.player.sendMessage(Text.literal(("放弃吃食物！！！")), false);
                                        client.options.useKey.setPressed(false);
                                        s5.repeatTimes = 0;
                                        isEating = false;
                                        if (client.interactionManager != null)
                                            client.interactionManager.stopUsingItem(client.player);
                                    } else if (s5.repeatTimes == 0) {
                                        client.options.useKey.setPressed(false);
                                        isEating = false;
                                    }
                                }, 1, 40, 0, 10000);
                            }
                        }
//                        下界荒地怪物较少，提前降落领取补给
                        if (!arrived && (client.player.getBlockPos().isWithinDistance(segments.get(nowIndex), 3500) || noFirework) && Objects.equals(client.world.getBiome(client.player.getBlockPos()).getKey().map(RegistryKey::getValue).orElse(null), Identifier.of("minecraft", "nether_wastes"))) {
                            scheduleTask((s6, a6) -> {
                                if (client.player != null)
                                    BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().pathTo(client.player.getBlockPos());
                            }, 1, 0, 15, 1000);
                            client.player.sendMessage(Text.literal(("位于下界荒地，提前降落！")), false);
                            arrived = true;
                        }
                    }

                }, 1, -1, 2, 2);
            }, 1, 0, 7, 200);
        }, 15, 0, 15, 300);

    }

//    自动拿补给
    static void segmentsMainSupply(List<Vec3i> segments, int nowIndex, MinecraftClient client, boolean isAutoLog, boolean isAutoLogOnSeg1) {
        ModStatus = ModStatuses.running;
        if (client.player == null) {
            taskFailed(client, isAutoLog, ("补给任务失败！client为null"), isAutoLogOnSeg1, nowIndex - 1);
            return;
        }

        float h = client.player.getHealth();
        scheduleTask((self, args) -> {
            if (ModStatus == ModStatuses.canceled) {
                self.repeatTimes = 0;
                ModStatus = ModStatuses.idle;
                return;
            }
            autoPlace(client, self);
        }, 1, -1, 0, 10);
        scheduleTask((self, args) -> {
            if (ModStatus == ModStatuses.canceled) {
                self.repeatTimes = 0;
                return;
            }

            if (client.player == null) {
                self.repeatTimes = 0;
                taskFailed(client, isAutoLog, ("补给任务失败！client为null"), isAutoLogOnSeg1, nowIndex - 1);
                ModStatus = ModStatuses.canceled;
                return;
            }
            if (client.player.getHealth() < h) {
                self.repeatTimes = 0;

                BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().cancel();
                taskFailed(client, isAutoLog, ("补给过程受伤！紧急！"), isAutoLogOnSeg1, nowIndex - 1);
                ModStatus = ModStatuses.canceled;
                return;
            }
            switch (ModStatus) {
                case failed -> {
//                    补给失败
                    client.player.sendMessage(Text.literal(("补给任务失败")), false);
                    taskFailed(client, isAutoLog, ("补给任务失败！自动退出！"), isAutoLogOnSeg1, nowIndex - 1);
                    self.repeatTimes = 0;
                }
                case success -> {
//                    补给成功，继续飞行
                    client.player.sendMessage(Text.literal(("补给任务成功:") + nowIndex), false);
                    client.player.sendMessage(Text.literal(("进行下一段飞行任务：") + nowIndex), false);
                    self.repeatTimes = 0;
                    segmentsMainElytra(segments, nowIndex, client, isAutoLog, isAutoLogOnSeg1);
                }
            }
        }, 1, -1, 1, 100);


    }

    private static KeyBinding openCustomScreenKey;

    @Override
    public void onInitializeClient() {
//        初始化客户端
        configFile = FabricLoader.getInstance().getConfigDir().resolve("RSTConfig.json");
        loadConfig();

//        GUI按键注册
        openCustomScreenKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(("RST Auto Elytra Mod主界面"), // 直接使用英文文本作为翻译键
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "RST Auto Elytra Mod" // 直接使用分类名称
        ));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            currentTick++;
            tick();
            if (openCustomScreenKey.isPressed())
                client.setScreen(new RSTScr(MinecraftClient.getInstance().currentScreen, getBoolean("FirstUse", true)));
        });

//        本命令用于进入主菜单GUI(也可以通过上方按键进入)
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var dis = dispatcher.register(ClientCommandManager.literal("RSTAutoElytraMenu").executes(context -> {
                scheduleTask((s, a) -> MinecraftClient.getInstance().setScreen(new RSTScr(MinecraftClient.getInstance().currentScreen, getBoolean("FirstUse", true))), 1, 0, 2, 100000);
                return 1;
            }));
            dispatcher.register(ClientCommandManager.literal("raem").redirect(dis));
        });

//        命令开启飞行，不推荐，优先使用GUI
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(ClientCommandManager.literal("RSTAutoElytra").then(ClientCommandManager.argument("x", IntegerArgumentType.integer()).then(ClientCommandManager.argument("z", IntegerArgumentType.integer()).executes(context -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                return 0;
            }
            int targetX = IntegerArgumentType.getInteger(context, "x");
            int targetZ = IntegerArgumentType.getInteger(context, "z");
            int sl = getInt("SegLength", DEFAULT_SEGMENT_LENGTH);
            List<Vec3i> segments = calculatePathSegments(client, targetX, targetZ, sl, false);
            if (segments.isEmpty()) {
                client.player.sendMessage(Text.literal(("分段失败！")), false);
                return 0;
            }
            client.player.sendMessage(Text.literal(("任务开始！补给距离：") + sl), false);
            segmentsMainSupply(segments, 0, client, true, true);
            return 1;
        })))));


        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // 确保 client.world 为 null 时不崩溃
            if (ModStatus != ModStatuses.idle) {
                ModStatus = ModStatuses.canceled;
            }
        });
    }


}




