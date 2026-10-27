package com.example.waypoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple client-side waypoints for Minecraft 26.3 (Fabric, Mojang names).
 *
 * Labels are drawn on the HUD by projecting each waypoint's world position onto the screen,
 * so they show through walls. Waypoints are saved to config/simple-waypoints.json.
 */
public class WaypointsMod implements ClientModInitializer {
    public static final String MOD_ID = "simplewaypoints";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<Waypoint>>() {}.getType();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("simple-waypoints.json");
    private static final int[] PALETTE = {0xFF0000, 0x55FF55, 0x5599FF, 0xFFFF55, 0xFF55FF, 0x55FFFF, 0xFFAA00};

    private static final List<Waypoint> WAYPOINTS = new ArrayList<>();
    private static KeyMapping addKey;

    @Override
    public void onInitializeClient() {
        load();
        BeamRenderer.init();

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        addKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.simplewaypoints.add",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_B,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            purgeExpired();
            while (addKey.consumeClick()) {
                if (client.player == null) return;
                BlockPos p = client.player.blockPosition();
                add("Waypoint" + (WAYPOINTS.size() + 1), p.getX(), p.getY(), p.getZ());
                client.player.sendSystemMessage(Component.literal("Waypoint added."));
            }
        });

        registerCommands();

        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "waypoints"),
                WaypointsMod::renderHud);
    }

    // ---------------- commands ----------------
    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("wp")
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> {
                                            BlockPos p = ctx.getSource().getPlayer().blockPosition();
                                            add(StringArgumentType.getString(ctx, "name"), p.getX(), p.getY(), p.getZ());
                                            feedback(ctx.getSource(), "Waypoint added at your position.");
                                            return 1;
                                        })
                                        .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                                .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                                        .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                                                                .executes(ctx -> {
                                                                    add(StringArgumentType.getString(ctx, "name"),
                                                                            IntegerArgumentType.getInteger(ctx, "x"),
                                                                            IntegerArgumentType.getInteger(ctx, "y"),
                                                                            IntegerArgumentType.getInteger(ctx, "z"));
                                                                    feedback(ctx.getSource(), "Waypoint added.");
                                                                    return 1;
                                                                }))))))
                        .then(ClientCommands.literal("remove")
                                .then(ClientCommands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String n = StringArgumentType.getString(ctx, "name");
                                            boolean removed = WAYPOINTS.removeIf(w -> w.name().equalsIgnoreCase(n));
                                            save();
                                            feedback(ctx.getSource(), removed ? "Removed." : "No waypoint with that name.");
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("color")
                                .then(ClientCommands.argument("name", StringArgumentType.word())
                                        .then(ClientCommands.argument("color", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String n = StringArgumentType.getString(ctx, "name");
                                                    Integer c = parseColor(StringArgumentType.getString(ctx, "color"));
                                                    if (c == null) {
                                                        feedback(ctx.getSource(), "Unknown color. Use red, green, blue, yellow, pink, cyan, orange, white, a hex code like ff8800, or chroma.");
                                                        return 0;
                                                    }
                                                    boolean ok = update(n, w -> new Waypoint(w.name(), w.x(), w.y(), w.z(), c, w.dimension(), w.expiresAt()));
                                                    feedback(ctx.getSource(), ok ? "Color changed." : "No waypoint with that name.");
                                                    return 1;
                                                }))))
                        .then(ClientCommands.literal("expire")
                                .then(ClientCommands.argument("name", StringArgumentType.word())
                                        .then(ClientCommands.argument("seconds", IntegerArgumentType.integer(0))
                                                .executes(ctx -> {
                                                    String n = StringArgumentType.getString(ctx, "name");
                                                    int secs = IntegerArgumentType.getInteger(ctx, "seconds");
                                                    long at = secs == 0 ? 0L : System.currentTimeMillis() + secs * 1000L;
                                                    boolean ok = update(n, w -> new Waypoint(w.name(), w.x(), w.y(), w.z(), w.color(), w.dimension(), at));
                                                    feedback(ctx.getSource(), ok ? (secs == 0 ? "Waypoint is now permanent." : "Waypoint will disappear in " + secs + "s.") : "No waypoint with that name.");
                                                    return 1;
                                                }))))
                        .then(ClientCommands.literal("list")
                                .executes(ctx -> {
                                    if (WAYPOINTS.isEmpty()) feedback(ctx.getSource(), "No waypoints.");
                                    for (Waypoint w : WAYPOINTS) {
                                        String extra = w.color() == CHROMA ? " chroma" : String.format(" #%06X", w.color() & 0xFFFFFF);
                                        if (w.expiresAt() > 0) extra += " (" + Math.max(0, (w.expiresAt() - System.currentTimeMillis()) / 1000) + "s left)";
                                        feedback(ctx.getSource(), w.name() + "  " + w.x() + " " + w.y() + " " + w.z() + extra);
                                    }
                                    return 1;
                                }))
                        .then(ClientCommands.literal("clear")
                                .executes(ctx -> {
                                    WAYPOINTS.clear();
                                    save();
                                    feedback(ctx.getSource(), "All waypoints cleared.");
                                    return 1;
                                }))));
    }

    private static void feedback(FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal(message));
    }

    // ---------------- data ----------------
    static final int CHROMA = -1;

    private static boolean update(String name, java.util.function.UnaryOperator<Waypoint> fn) {
        boolean found = false;
        for (int i = 0; i < WAYPOINTS.size(); i++) {
            Waypoint w = WAYPOINTS.get(i);
            if (w.name().equalsIgnoreCase(name)) {
                WAYPOINTS.set(i, fn.apply(w));
                found = true;
            }
        }
        if (found) save();
        return found;
    }

    private static void purgeExpired() {
        long now = System.currentTimeMillis();
        if (WAYPOINTS.removeIf(w -> w.expiresAt() > 0 && now > w.expiresAt())) save();
    }

    private static Integer parseColor(String s) {
        switch (s.toLowerCase()) {
            case "red": return 0xFF0000;
            case "green": return 0x00FF00;
            case "blue": return 0x3366FF;
            case "yellow": return 0xFFFF00;
            case "pink": return 0xFF55FF;
            case "cyan": return 0x00FFFF;
            case "orange": return 0xFF9900;
            case "white": return 0xFFFFFF;
            case "chroma": case "rainbow": return CHROMA;
            default:
                String hex = s.startsWith("#") ? s.substring(1) : s;
                if (hex.length() != 6) return null;
                try {
                    return Integer.parseInt(hex, 16);
                } catch (NumberFormatException e) {
                    return null;
                }
        }
    }

    /** Actual 0xRRGGBB to draw this frame (handles the rainbow effect). */
    static int resolveColor(Waypoint w) {
        if (w.color() != CHROMA) return w.color() & 0xFFFFFF;
        float h = (System.currentTimeMillis() % 4000L) / 4000f * 6f;
        int i = (int) h;
        float f = h - i;
        float q = 1f - f;
        float r, g, b;
        switch (i % 6) {
            case 0: r = 1; g = f; b = 0; break;
            case 1: r = q; g = 1; b = 0; break;
            case 2: r = 0; g = 1; b = f; break;
            case 3: r = 0; g = q; b = 1; break;
            case 4: r = f; g = 0; b = 1; break;
            default: r = 1; g = 0; b = q; break;
        }
        return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    static List<Waypoint> waypoints() {
        return WAYPOINTS;
    }

    static String currentDimension() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null ? mc.level.dimension().toString() : "unknown";
    }

    private static void add(String name, int x, int y, int z) {
        int color = PALETTE[WAYPOINTS.size() % PALETTE.length];
        WAYPOINTS.add(new Waypoint(name, x, y, z, color, currentDimension(), 0L));
        save();
    }

    private static void load() {
        try {
            if (Files.exists(FILE)) {
                List<Waypoint> list = GSON.fromJson(Files.readString(FILE), LIST_TYPE);
                if (list != null) WAYPOINTS.addAll(list);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void save() {
        try {
            Files.writeString(FILE, GSON.toJson(WAYPOINTS, LIST_TYPE));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ---------------- HUD rendering ----------------
    private static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || WAYPOINTS.isEmpty()) return;

        float partial = deltaTracker.getGameTimeDeltaPartialTick(false);
        Vec3 eye = mc.player.getEyePosition(partial);
        double yaw = Math.toRadians(mc.player.getViewYRot(partial));
        double pitch = Math.toRadians(mc.player.getViewXRot(partial));

        // Camera basis vectors (Minecraft: yaw 0 looks toward +Z).
        double fx = -Math.sin(yaw) * Math.cos(pitch);
        double fy = -Math.sin(pitch);
        double fz = Math.cos(yaw) * Math.cos(pitch);
        double rx = -Math.cos(yaw);
        double ry = 0;
        double rz = -Math.sin(yaw);
        // up = right x forward
        double ux = ry * fz - rz * fy;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy - ry * fx;

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        double aspect = (double) sw / (double) sh;
        double tanHalfFov = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);

        Font font = mc.font;
        String dim = currentDimension();

        for (Waypoint w : WAYPOINTS) {
            if (!w.dimension().equals(dim)) continue;

            double dx = w.x() + 0.5 - eye.x;
            double dy = w.y() + 1.2 - eye.y;
            double dz = w.z() + 0.5 - eye.z;

            double depth = dx * fx + dy * fy + dz * fz;
            if (depth < 0.1) continue; // behind the camera

            double side = dx * rx + dy * ry + dz * rz;
            double up = dx * ux + dy * uy + dz * uz;

            double ndcX = side / (depth * tanHalfFov * aspect);
            double ndcY = up / (depth * tanHalfFov);

            int sx = (int) (sw / 2.0 + ndcX * sw / 2.0);
            int sy = (int) (sh / 2.0 - ndcY * sh / 2.0);
            if (sx < -60 || sx > sw + 60 || sy < -60 || sy > sh + 60) continue;

            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            // Feather-style label: "Name (19m)" in white on a dark box, at the base of the beam.
            String text = w.name() + " (" + (int) dist + "m)";
            int tw = font.width(text);
            int x0 = sx - tw / 2;
            int y0 = sy - 4;
            graphics.fill(x0 - 2, y0 - 2, x0 + tw + 2, y0 + 10, 0x90000000);
            graphics.text(font, text, x0, y0, 0xFFFFFFFF, false);
        }
    }
}
