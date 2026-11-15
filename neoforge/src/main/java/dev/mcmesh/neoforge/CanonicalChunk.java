package dev.mcmesh.neoforge;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * A digest of one stored chunk that two peers generating it independently agree on, so a second peer can vouch for a
 * tile without sending it. It covers the terrain, the entities and the points of interest, and leaves out exactly the
 * things that were measured to differ between runs without the world differing (board/RESEARCH.md 1b to 1e): light
 * (recomputed on load), timestamps, entity UUIDs (random on creation), the order of block entities and entities (they
 * come back in a different order after a load and save), and what a spawner will spawn (a random pick that is not tied
 * to the seed). Anything else that differs between two runs is a real difference and must change the digest.
 */
final class CanonicalChunk {
    private static final Set<String> SPAWNERS = Set.of("minecraft:mob_spawner", "minecraft:trial_spawner");
    private static final Set<String> SPAWNER_KEEP = Set.of("id", "x", "y", "z", "keepPacked");
    /** Entity fields drawn from the entity's own unseeded random when it is created, so they differ on every run (a chicken's next egg). Found one at a time by comparing real worlds. */
    private static final Set<String> VOLATILE_ENTITY = Set.of("EggLayTime");

    private CanonicalChunk() { }

    /** `entities` and `poi` are null when the chunk has no such file entry. */
    static byte[] digest(CompoundTag region, CompoundTag entities, CompoundTag poi) throws IOException {
        MessageDigest md = sha256();
        ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 16);
        try (DataOutputStream out = new DataOutputStream(buf)) {
            out.writeByte('R');
            canon(region(region), out);
            out.writeByte('E');
            canon(entities(entities), out);
            out.writeByte('P');
            canon(poi == null ? new CompoundTag() : poi, out);
        }
        return md.digest(buf.toByteArray());
    }

    /** One digest over the per-chunk digests in the order given. */
    static byte[] tile(List<byte[]> perChunk) {
        MessageDigest md = sha256();
        for (byte[] d : perChunk) {
            md.update(d);
        }
        return md.digest();
    }

    static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static CompoundTag region(CompoundTag t) {
        CompoundTag c = t.copy();
        c.remove("LastUpdate");
        c.remove("InhabitedTime");
        c.remove("isLightOn");
        ListTag sections = c.getList("sections", 10);
        for (int i = 0; i < sections.size(); i++) {
            sections.getCompound(i).remove("BlockLight");
            sections.getCompound(i).remove("SkyLight");
        }
        ListTag be = c.getList("block_entities", 10);
        if (!be.isEmpty()) {
            List<CompoundTag> kept = new ArrayList<>();
            for (int i = 0; i < be.size(); i++) {
                CompoundTag e = be.getCompound(i).copy();
                if (SPAWNERS.contains(e.getString("id"))) {
                    for (String k : new ArrayList<>(e.getAllKeys())) {
                        if (!SPAWNER_KEEP.contains(k)) {
                            e.remove(k);
                        }
                    }
                }
                kept.add(e);
            }
            c.put("block_entities", sorted(kept));
        }
        return c;
    }

    static CompoundTag entities(CompoundTag t) {
        CompoundTag out = new CompoundTag();
        if (t == null) {
            return out;
        }
        List<CompoundTag> list = new ArrayList<>();
        ListTag src = t.getList("Entities", 10);
        for (int i = 0; i < src.size(); i++) {
            // which mob is where is what generation decides; its stat rolls (attributes, health, timers) come from the mob's own
            // unseeded random and differ on every run, zombies and horses and chickens alike (RESEARCH.md 1e), so they stay out
            CompoundTag e = new CompoundTag();
            CompoundTag full = src.getCompound(i);
            e.putString("id", full.getString("id"));
            if (full.contains("Pos", Tag.TAG_LIST)) {
                e.put("Pos", full.getList("Pos", Tag.TAG_DOUBLE).copy());
            }
            list.add(e);
        }
        out.put("Entities", sorted(list));
        for (String k : t.getAllKeys()) {
            if (!k.equals("Entities")) {
                out.put(k, t.get(k).copy());
            }
        }
        return out;
    }

    /**
     * Entities get a random UUID when they are created, so it says nothing about generation, and their attributes sit in a
     * hash map, so the order the list comes back in changes from one JVM run to the next (measured, RESEARCH.md 1e).
     */
    static void stripUuids(Tag t) {
        if (t instanceof CompoundTag c) {
            c.remove("UUID");
            VOLATILE_ENTITY.forEach(c::remove);
            for (String k : new ArrayList<>(c.getAllKeys())) {
                stripUuids(c.get(k));
            }
            if (c.contains("attributes", Tag.TAG_LIST)) {
                List<CompoundTag> attrs = new ArrayList<>();
                ListTag l = c.getList("attributes", Tag.TAG_COMPOUND);
                for (int i = 0; i < l.size(); i++) {
                    attrs.add(l.getCompound(i));
                }
                c.put("attributes", sorted(attrs));
            }
        } else if (t instanceof ListTag l) {
            l.forEach(CanonicalChunk::stripUuids);
        }
    }

    private static ListTag sorted(List<CompoundTag> items) {
        List<java.util.Map.Entry<String, CompoundTag>> keyed = new ArrayList<>();
        for (CompoundTag c : items) {
            keyed.add(java.util.Map.entry(key(c), c));
        }
        keyed.sort(java.util.Map.Entry.comparingByKey());
        ListTag out = new ListTag();
        for (var e : keyed) {
            out.add(e.getValue());
        }
        return out;
    }

    private static String key(Tag t) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (DataOutputStream o = new DataOutputStream(b)) {
                canon(t, o);
            }
            return hex(b.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Type-tagged, compound keys sorted: the same tag always gives the same bytes whatever order a map happened to iterate in. */
    static void canon(Tag t, DataOutputStream out) throws IOException {
        out.writeByte(t.getId());
        switch (t.getId()) {
            case Tag.TAG_BYTE -> out.writeByte(((NumericTag) t).getAsByte());
            case Tag.TAG_SHORT -> out.writeShort(((NumericTag) t).getAsShort());
            case Tag.TAG_INT -> out.writeInt(((NumericTag) t).getAsInt());
            case Tag.TAG_LONG -> out.writeLong(((NumericTag) t).getAsLong());
            case Tag.TAG_FLOAT -> out.writeInt(Float.floatToRawIntBits(((NumericTag) t).getAsFloat()));
            case Tag.TAG_DOUBLE -> out.writeLong(Double.doubleToRawLongBits(((NumericTag) t).getAsDouble()));
            case Tag.TAG_BYTE_ARRAY -> {
                byte[] a = ((ByteArrayTag) t).getAsByteArray();
                out.writeInt(a.length);
                out.write(a);
            }
            case Tag.TAG_STRING -> {
                byte[] a = ((StringTag) t).getAsString().getBytes(StandardCharsets.UTF_8);
                out.writeInt(a.length);
                out.write(a);
            }
            case Tag.TAG_LIST -> {
                ListTag l = (ListTag) t;
                out.writeByte(l.getElementType());
                out.writeInt(l.size());
                for (Tag e : l) {
                    canon(e, out);
                }
            }
            case Tag.TAG_COMPOUND -> {
                CompoundTag c = (CompoundTag) t;
                TreeSet<String> keys = new TreeSet<>(c.getAllKeys());
                out.writeInt(keys.size());
                for (String k : keys) {
                    byte[] kb = k.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(kb.length);
                    out.write(kb);
                    canon(c.get(k), out);
                }
            }
            case Tag.TAG_INT_ARRAY -> {
                int[] a = ((IntArrayTag) t).getAsIntArray();
                out.writeInt(a.length);
                for (int v : a) {
                    out.writeInt(v);
                }
            }
            case Tag.TAG_LONG_ARRAY -> {
                long[] a = ((LongArrayTag) t).getAsLongArray();
                out.writeInt(a.length);
                for (long v : a) {
                    out.writeLong(v);
                }
            }
            default -> { }
        }
    }
}
