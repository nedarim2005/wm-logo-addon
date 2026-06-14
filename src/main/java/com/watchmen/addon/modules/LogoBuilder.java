package com.watchmen.addon.modules;

import com.watchmen.addon.WmLogoBuilder;
import com.watchmen.addon.schematic.Schematic;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ProvidedStringSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class LogoBuilder extends Module {
    private static final int ROTATION_PRIORITY = 50;
    private static final int MAX_RENDER = 4000;
    private static final int BUILD_HEIGHT = 319;
    private static final int STATE_TIMEOUT = 60;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlace = settings.createGroup("Placing");
    private final SettingGroup sgAuto = settings.createGroup("Automation");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<String> schematicFile = sgGeneral.add(new ProvidedStringSetting.Builder()
        .name("schematic")
        .description("Schematic to build, loaded from the 'schematics' folder in your game directory.")
        .defaultValue("")
        .supplier(LogoBuilder::listSchematics)
        .onChanged(v -> { if (isActive()) load(); })
        .build()
    );

    private final Setting<Integer> x = sgGeneral.add(new IntSetting.Builder()
        .name("x")
        .description("World X coordinate the logo is anchored to.")
        .defaultValue(0).noSlider().onChanged(v -> onAnchorChanged()).build());
    private final Setting<Integer> z = sgGeneral.add(new IntSetting.Builder()
        .name("z")
        .description("World Z coordinate the logo is anchored to.")
        .defaultValue(0).noSlider().onChanged(v -> onAnchorChanged()).build());

    private final Setting<Boolean> airPlace = sgPlace.add(new BoolSetting.Builder()
        .name("air-place").description("Places blocks in air")
        .defaultValue(true).build());
    private final Setting<Double> range = sgPlace.add(new DoubleSetting.Builder()
        .name("range").description("Maximum distance to a block for it to be placed.")
        .defaultValue(4.5).min(0).sliderRange(1, 6).build());
    private final Setting<Integer> blocksPerTick = sgPlace.add(new IntSetting.Builder()
        .name("blocks-per-tick").description("How many blocks to place each tick.")
        .defaultValue(1).min(1).sliderRange(1, 16).build());
    private final Setting<Integer> delay = sgPlace.add(new IntSetting.Builder()
        .name("delay").description("Ticks to wait after a placement batch before the next.")
        .defaultValue(0).min(0).sliderRange(0, 20).build());
    private final Setting<Integer> maxRetries = sgPlace.add(new IntSetting.Builder()
        .name("max-retries").description("Retries for a block whose placement didn't register before giving up.")
        .defaultValue(8).min(1).sliderRange(1, 30).build());
    private final Setting<Integer> retryDelay = sgPlace.add(new IntSetting.Builder()
        .name("retry-delay").description("Ticks to wait for a placement to take effect before retrying it.")
        .defaultValue(3).min(1).sliderRange(1, 20).build());

    private final Setting<Boolean> replenish = sgAuto.add(new BoolSetting.Builder()
        .name("auto-replenish")
        .description("Move needed blocks from your inventory to the hotbar, and refill from shulkers.")
        .defaultValue(true).build());
    private final Setting<Boolean> walk = sgAuto.add(new BoolSetting.Builder()
        .name("auto-walk")
        .description("Automatically walk the logo in a lawnmower pattern")
        .defaultValue(true)
        .build());
    private final Setting<Integer> rowSpacing = sgAuto.add(new IntSetting.Builder()
        .name("row-spacing")
        .description("Distance between lawnmower passes. Keep below twice your range.")
        .defaultValue(3).min(1).sliderRange(1, 8).visible(walk::get).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Show ghost outlines of blocks still to place.")
        .defaultValue(true).build());
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("How the ghost boxes are drawn.")
        .defaultValue(ShapeMode.Both).visible(render::get).build());
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").description("Fill colour of ghost boxes.")
        .defaultValue(new SettingColor(170, 100, 255, 30)).visible(render::get).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Outline colour of ghost boxes.")
        .defaultValue(new SettingColor(170, 100, 255, 180)).visible(render::get).build());

    private Schematic schematic;
    private int delayTimer;
    private int tickCounter;

    private final List<Item> neededItems = new ArrayList<>();
    private final Set<Item> neededSet = new HashSet<>();
    private int relMinX, relMaxX, relMinZ, relMaxZ;
    private final Map<BlockPos, Integer> attempts = new HashMap<>();
    private final Map<BlockPos, Integer> lastAttempt = new HashMap<>();
    private final Set<BlockPos> failed = new HashSet<>();

    // Lawnmower path
    private final List<BlockPos> waypoints = new ArrayList<>();
    private int waypointIndex;
    private boolean announcedComplete;
    private int stuckTicks;
    private Vec3 lastPos;

    // shulker replenish
    private enum RState { IDLE, PLACING, OPENING, EMPTYING }
    private RState rState = RState.IDLE;
    private BlockPos shulkerPos;
    private Item replenishTarget;
    private int stateEnteredTick;

    private final ResetSync resetSync = new ResetSync();
    private class ResetSync {
        @EventHandler
        private void onTick(TickEvent.Pre event) { syncResetDefaultsToPlayer(); }
    }

    public LogoBuilder() {
        super(WmLogoBuilder.CATEGORY, "logo-builder", "Automatically builds a sky logo");

        MeteorClient.EVENT_BUS.subscribe(resetSync);
    }

    @Override
    public void onActivate() {
        if (mc.player == null) { toggle(); return; }
        delayTimer = 0;
        stuckTicks = 0;
        lastPos = null;
        load();
        if (walk.get() && mc.player.getY() < BUILD_HEIGHT - 1) {
            warning("Please stand on the logo layer (Y~%d) before enabling module", BUILD_HEIGHT + 1);
        }
    }

    @Override
    public void onDeactivate() {
        schematic = null;
        rState = RState.IDLE;
        releaseMoveKeys();
    }

    private void load() {
        schematic = null;
        clearProgress();
        String name = schematicFile.get();
        if (name == null || name.isEmpty()) {
            warning("No schematic selected");
            return;
        }
        try {
            Schematic loaded = Schematic.load(schematicsDir().resolve(name));

            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            relMinX = relMinZ = Integer.MAX_VALUE;
            relMaxX = relMaxZ = Integer.MIN_VALUE;
            neededItems.clear();
            neededSet.clear();
            Set<Item> distinct = new LinkedHashSet<>();

            for (Schematic.Entry e : loaded.entries) {
                BlockPos p = e.pos();
                minY = Math.min(minY, p.getY());
                maxY = Math.max(maxY, p.getY());
                relMinX = Math.min(relMinX, p.getX());
                relMaxX = Math.max(relMaxX, p.getX());
                relMinZ = Math.min(relMinZ, p.getZ());
                relMaxZ = Math.max(relMaxZ, p.getZ());
                Item item = e.state().getBlock().asItem();
                if (item != Items.AIR) distinct.add(item);
            }

            if (!loaded.entries.isEmpty() && maxY != minY) {
                error("'%s' is %d layers tall. Only horizontal logos are supported",
                    name, maxY - minY + 1);
                return;
            }

            neededItems.addAll(distinct);
            neededSet.addAll(distinct);
            schematic = loaded;
            rebuildWaypoints();
            announcedComplete = false;
            info("Loaded %s (%d blocks) building at Y=%d.", name, schematic.entries.size(), BUILD_HEIGHT);
        } catch (Exception e) {
            error("Failed to load %s: %s", name, e.getMessage());
        }
    }

    private void onAnchorChanged() {
        clearProgress();
        if (isActive()) rebuildWaypoints();
    }

    // Tick
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (schematic == null || mc.player == null || mc.level == null) return;
        tickCounter++;

        if (rState != RState.IDLE) {
            releaseMoveKeys();
            stuckTicks = 0;
            if (replenish.get()) driveShulker();
            else abortShulker("auto-replenish disabled");
            return;
        }

        if (replenish.get()) {
            replenishHotbar();
            Item missing = firstMissingItemInShulker();
            if (missing != null && !storageFull()) { startShulker(missing); return; }
        }

        if (walk.get()) driveWalk();

        if (delayTimer > 0) { delayTimer--; return; }
        placeTick();
    }

    private void placeTick() {
        int placed = 0;
        Vec3 eye = mc.player.getEyePosition();

        for (Schematic.Entry entry : schematic.entries) {
            if (placed >= blocksPerTick.get()) break;

            BlockPos pos = worldPos(entry.pos());
            BlockState desired = entry.state();
            BlockState current = mc.level.getBlockState(pos);

            if (current.equals(desired)) { clearTracking(pos); continue; }
            if (failed.contains(pos)) continue;
            if (!current.isAir() && (!current.canBeReplaced())) continue;
            if (eye.distanceTo(Vec3.atCenterOf(pos)) > range.get()) continue;

            Integer last = lastAttempt.get(pos);
            if (last != null && tickCounter - last < retryDelay.get()) continue;

            int tries = attempts.getOrDefault(pos, 0);
            if (tries >= maxRetries.get()) {
                failed.add(pos);
                attempts.remove(pos);
                lastAttempt.remove(pos);
                warning("Gave up on block at %d, %d, %d after %d attempts.",
                    pos.getX(), pos.getY(), pos.getZ(), tries);
                continue;
            }

            Item item = desired.getBlock().asItem();
            if (item == Items.AIR) continue;

            FindItemResult found = InvUtils.findInHotbar(item);
            if (!found.found()) continue;

            boolean attempted = airPlace.get()
                ? airPlace(pos, found)
                : BlockUtils.place(pos, found, false, ROTATION_PRIORITY);

            if (attempted) {
                attempts.put(pos, tries + 1);
                lastAttempt.put(pos, tickCounter);
                placed++;
            }
        }

        if (placed > 0) delayTimer = delay.get();
    }

    private void replenishHotbar() {
        for (Item item : neededItems) {
            if (InvUtils.findInHotbar(item).found()) continue;
            FindItemResult inInv = InvUtils.find(item);
            if (!inInv.found() || !inInv.isMain()) continue;
            int target = freeHotbarSlot();
            if (target < 0) return;
            InvUtils.move().from(inInv.slot()).toHotbar(target);
        }
    }

    private int freeHotbarSlot() {
        for (int i = 0; i <= 8; i++) if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        for (int i = 0; i <= 8; i++) {
            if (!neededSet.contains(mc.player.getInventory().getItem(i).getItem())) return i;
        }
        return -1;
    }

    private boolean storageFull() {
        for (int i = 0; i < 36; i++) if (mc.player.getInventory().getItem(i).isEmpty()) return false;
        return true;
    }

    private Item firstMissingItemInShulker() {
        for (Item item : neededItems) {
            if (InvUtils.find(item).found()) continue;
            if (findShulkerWith(item).found()) return item;
        }
        return null;
    }

    private void startShulker(Item item) {
        replenishTarget = item;
        shulkerPos = new BlockPos(mc.player.getBlockX(), BUILD_HEIGHT - 1, mc.player.getBlockZ());
        enterState(RState.PLACING);
        info("Out of %s placing a shulker below to refill", item.getDefaultInstance().getHoverName().getString());
    }

    private void driveShulker() {
        if (tickCounter - stateEnteredTick > STATE_TIMEOUT) { abortShulker("timed out"); return; }

        switch (rState) {
            case PLACING -> {
                if (mc.level.getBlockState(shulkerPos).getBlock() instanceof ShulkerBoxBlock) {
                    enterState(RState.OPENING);
                    return;
                }
                FindItemResult shulker = InvUtils.findInHotbar(this::isTargetShulker);
                if (!shulker.found()) {
                    FindItemResult inInv = InvUtils.find(this::isTargetShulker);
                    if (inInv.found() && inInv.isMain()) {
                        int t = freeHotbarSlot();
                        if (t >= 0) InvUtils.move().from(inInv.slot()).toHotbar(t);
                    } else {
                        abortShulker("no shulker with that item");
                    }
                    return;
                }
                airPlace(shulkerPos, shulker, Direction.DOWN);
            }
            case OPENING -> {
                if (mc.player.containerMenu instanceof ShulkerBoxMenu) { enterState(RState.EMPTYING); return; }
                int empty = freeHotbarSlot();
                if (empty >= 0) InvUtils.swap(empty, false);
                interactAt(shulkerPos, Direction.DOWN);
            }
            case EMPTYING -> {
                if (!(mc.player.containerMenu instanceof ShulkerBoxMenu menu)) { rState = RState.IDLE; return; }
                if (storageFull()) {
                    mc.player.closeContainer();
                    rState = RState.IDLE;
                    info("Inventory full, finished replenishing");
                    return;
                }
                if (!quickMoveOneStack(menu)) {
                    mc.player.closeContainer();
                    rState = RState.IDLE;
                }
            }
            default -> rState = RState.IDLE;
        }
    }

    private boolean quickMoveOneStack(ShulkerBoxMenu menu) {
        int shulkerSlots = Math.min(27, menu.slots.size());
        for (int i = 0; i < shulkerSlots; i++) {
            if (!menu.getSlot(i).getItem().isEmpty()) {
                mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
                return true;
            }
        }
        return false;
    }

    private void interactAt(BlockPos pos, Direction face) {
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        BlockUtils.interact(new BlockHitResult(hit, face, pos, false), InteractionHand.MAIN_HAND, true);
    }

    private boolean isTargetShulker(ItemStack stack) {
        return isShulkerBox(stack) && shulkerContains(stack, replenishTarget);
    }

    private FindItemResult findShulkerWith(Item item) {
        return InvUtils.find(stack -> isShulkerBox(stack) && shulkerContains(stack, item));
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    private static boolean shulkerContains(ItemStack stack, Item item) {
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        return contents != null && contents.allItemsCopyStream().anyMatch(s -> s.getItem() == item);
    }

    private void enterState(RState state) {
        rState = state;
        stateEnteredTick = tickCounter;
    }

    private void abortShulker(String why) {
        warning("Shulker refill aborted (%s).", why);
        if (mc.player.containerMenu instanceof ShulkerBoxMenu) mc.player.closeContainer();
        rState = RState.IDLE;
    }

    // lawnmower pattern walking
    private void rebuildWaypoints() {
        waypoints.clear();
        waypointIndex = 0;
        if (schematic == null || schematic.entries.isEmpty() || mc.player == null) return;

        int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
        int worldMinX = x.get() + relMinX, worldMaxX = x.get() + relMaxX;
        int worldMinZ = z.get() + relMinZ, worldMaxZ = z.get() + relMaxZ;

        // begin at the corner closest to the player
        boolean nearMinZ = Math.abs(worldMinZ - pz) <= Math.abs(worldMaxZ - pz);
        boolean startLeft = Math.abs(worldMinX - px) <= Math.abs(worldMaxX - px);

        int step = Math.max(1, rowSpacing.get());
        List<Integer> rows = new ArrayList<>();
        for (int rz = relMinZ; rz <= relMaxZ; rz += step) rows.add(rz);
        if (rows.isEmpty() || rows.get(rows.size() - 1) != relMaxZ) rows.add(relMaxZ);
        if (!nearMinZ) Collections.reverse(rows);

        boolean leftToRight = startLeft;
        for (int rz : rows) {
            int wz = z.get() + rz;
            int a = x.get() + (leftToRight ? relMinX : relMaxX);
            int b = x.get() + (leftToRight ? relMaxX : relMinX);
            waypoints.add(new BlockPos(a, BUILD_HEIGHT, wz));
            waypoints.add(new BlockPos(b, BUILD_HEIGHT, wz));
            leftToRight = !leftToRight;
        }
    }

    private void driveWalk() {
        if (remaining() == 0) {
            if (!announcedComplete) { info("Logo complete."); announcedComplete = true; }
            releaseMoveKeys();
            return;
        }
        if (waypoints.isEmpty()) { releaseMoveKeys(); return; }
        if (waypointIndex >= waypoints.size()) { rebuildWaypoints(); if (waypoints.isEmpty()) { releaseMoveKeys(); return; } } // re-sweep

        BlockPos wp = waypoints.get(waypointIndex);
        double dx = (wp.getX() + 0.5) - mc.player.getX();
        double dz = (wp.getZ() + 0.5) - mc.player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        Vec3 here = mc.player.position();
        if (lastPos == null || here.distanceToSqr(lastPos) > 0.0025) { lastPos = here; stuckTicks = 0; }
        else stuckTicks++;

        if (horizontal <= 1.0 || stuckTicks > 60) {
            waypointIndex++;
            stuckTicks = 0;
            releaseMoveKeys();
            return;
        }

        mc.player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));

        int nx = (int) Math.floor(mc.player.getX() + dx / horizontal);
        int nz = (int) Math.floor(mc.player.getZ() + dz / horizontal);
        if (isSolidLayer(nx, nz)) {
            mc.options.keyUp.setDown(true);
            mc.options.keyJump.setDown(mc.player.horizontalCollision);
        } else {
            releaseMoveKeys();
        }
    }

    private boolean isSolidLayer(int x, int z) {
        BlockState s = mc.level.getBlockState(new BlockPos(x, BUILD_HEIGHT, z));
        return !s.isAir() && !s.canBeReplaced();
    }

    private void releaseMoveKeys() {
        if (mc.options == null) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    private int remaining() {
        int r = 0;
        for (Schematic.Entry e : schematic.entries) {
            BlockPos p = worldPos(e.pos());
            if (failed.contains(p)) continue;
            if (!mc.level.getBlockState(p).equals(e.state())) r++;
        }
        return r;
    }

    private boolean airPlace(BlockPos pos, FindItemResult item) {
        return airPlace(pos, item, Direction.UP);
    }

    private boolean airPlace(BlockPos pos, FindItemResult item, Direction face) {
        InteractionHand hand;
        boolean swapped = false;
        if (item.isOffhand()) {
            hand = InteractionHand.OFF_HAND;
        } else if (item.isHotbar()) {
            InvUtils.swap(item.slot(), true);
            swapped = true;
            hand = InteractionHand.MAIN_HAND;
        } else {
            return false;
        }

        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        BlockUtils.interact(new BlockHitResult(hit, face, pos, false), hand, true);
        if (swapped) InvUtils.swapBack();
        return true;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || schematic == null || mc.level == null) return;
        int shown = 0;
        for (Schematic.Entry entry : schematic.entries) {
            if (shown >= MAX_RENDER) break;
            BlockPos pos = worldPos(entry.pos());
            if (mc.level.getBlockState(pos).equals(entry.state())) continue;
            event.renderer.box(new AABB(pos), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            shown++;
        }
    }

    private BlockPos worldPos(BlockPos relative) {
        return new BlockPos(x.get() + relative.getX(), BUILD_HEIGHT, z.get() + relative.getZ());
    }

    private void clearProgress() {
        attempts.clear();
        lastAttempt.clear();
        failed.clear();
        tickCounter = 0;
    }

    private void clearTracking(BlockPos pos) {
        attempts.remove(pos);
        lastAttempt.remove(pos);
        failed.remove(pos);
    }

    private Vec3 eyeTo(Vec3 target) {
        return target.subtract(mc.player.getEyePosition());
    }

    private static float[] yawPitch(Vec3 delta) {
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
        return new float[]{yaw, pitch};
    }

    private void syncResetDefaultsToPlayer() {
        if (mc.player == null) return;
        Field field = defaultValueField();
        if (field == null) return;
        try {
            field.set(x, mc.player.getBlockX());
            field.set(z, mc.player.getBlockZ());
        } catch (IllegalAccessException ignored) {}
    }

    private static Field DEFAULT_VALUE_FIELD;
    private static Field defaultValueField() {
        if (DEFAULT_VALUE_FIELD == null) {
            try {
                Field f = Setting.class.getDeclaredField("defaultValue");
                f.setAccessible(true);
                DEFAULT_VALUE_FIELD = f;
            } catch (NoSuchFieldException ignored) {}
        }
        return DEFAULT_VALUE_FIELD;
    }

    private static Path schematicsDir() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("schematics");
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        return dir;
    }

    private static String[] listSchematics() {
        Path dir = schematicsDir();
        try (Stream<Path> files = Files.list(dir)) {
            return files
                .filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(n -> {
                    String l = n.toLowerCase();
                    return l.endsWith(".litematic") || l.endsWith(".schem") || l.endsWith(".schematic");
                })
                .sorted()
                .toArray(String[]::new);
        } catch (IOException e) {
            return new String[0];
        }
    }
}