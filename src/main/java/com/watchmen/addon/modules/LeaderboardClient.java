package com.watchmen.addon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.watchmen.addon.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

public final class LeaderboardClient {
    private static final String BASE = "https://nedarym.com";
    private static final String PROTOCOL_FLAG = "wm-vb-7e3a9c";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private String sessionId;
    private String nonce;
    private long startMillis;

    public static String canonicalHash(Schematic schematic) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (Schematic.Entry e : schematic.entries) {
            minX = Math.min(minX, e.pos().getX());
            minY = Math.min(minY, e.pos().getY());
            minZ = Math.min(minZ, e.pos().getZ());
        }
        final int fMinX = minX, fMinY = minY, fMinZ = minZ;

        List<String> lines = new ArrayList<>(schematic.entries.size());
        for (Schematic.Entry e : schematic.entries) {
            int x = e.pos().getX() - fMinX;
            int y = e.pos().getY() - fMinY;
            int z = e.pos().getZ() - fMinZ;
            lines.add(x + "," + y + "," + z + "=" + canonicalState(e.state()));
        }
        lines.sort(Comparator.naturalOrder());

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static String canonicalState(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        TreeMap<String, String> props = new TreeMap<>();
        for (Property<?> p : state.getProperties()) {
            props.put(p.getName(), valueName(state, p));
        }
        if (props.isEmpty()) return id;
        StringBuilder sb = new StringBuilder(id).append('[');
        boolean first = true;
        for (var en : props.entrySet()) {
            if (!first) sb.append(',');
            sb.append(en.getKey()).append('=').append(en.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String valueName(BlockState s, Property<?> p) {
        Property<T> pp = (Property<T>) p;
        return pp.getName(s.getValue(pp));
    }

    public boolean start(String playerId, String playerName, String schematicHash) {
        JsonObject body = new JsonObject();
        body.addProperty("player_id", playerId);
        body.addProperty("player_name", playerName);
        body.addProperty("schematic_hash", schematicHash);
        String ver = WmLogoBuilder.class.getPackage().getImplementationVersion();
        body.addProperty("client_version", ver == null ? "dev" : ver);
        body.addProperty("protocol_flag", PROTOCOL_FLAG);

        JsonObject resp = post("/v1/session/start", body);
        if (resp == null || !resp.has("session_id")) return false;
        sessionId = resp.get("session_id").getAsString();
        nonce = resp.get("nonce").getAsString();
        startMillis = System.currentTimeMillis();
        return true;
    }

    public void complete(String playerId, String schematicHash, int blocksPlaced) {
        if (sessionId == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("session_id", sessionId);
        body.addProperty("nonce", nonce);
        body.addProperty("player_id", playerId);
        body.addProperty("schematic_hash", schematicHash);
        body.addProperty("build_seconds", (System.currentTimeMillis() - startMillis) / 1000.0);
        body.addProperty("blocks_placed", blocksPlaced);
        body.addProperty("protocol_flag", PROTOCOL_FLAG);
        post("/v1/session/complete", body);
        sessionId = null;
    }

    private JsonObject post(String path, JsonObject body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                WmLogoBuilder.LOG.warn("[leaderboard] POST {} -> {} {}",
                    path, resp.statusCode(), resp.body());
                return null;
            }
            return JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (Exception e) {
            WmLogoBuilder.LOG.error("[leaderboard] POST {} failed: {}", path, e.toString());
            return null;
        }
    }
}