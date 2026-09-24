package com.pglol.aotworld.anvil;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal NBT tree + big-endian writer, enough for chunks and level.dat. */
public final class Nbt {
    private Nbt() {}

    public static final byte END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6,
        BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    /** A typed value. */
    public static final class Tag {
        final byte type;
        final Object value;

        Tag(byte type, Object value) {
            this.type = type;
            this.value = value;
        }
    }

    public static final class Compound {
        final Map<String, Tag> map = new LinkedHashMap<>();

        public Compound putByte(String k, int v) { map.put(k, new Tag(BYTE, (byte) v)); return this; }
        public Compound putShort(String k, int v) { map.put(k, new Tag(SHORT, (short) v)); return this; }
        public Compound putInt(String k, int v) { map.put(k, new Tag(INT, v)); return this; }
        public Compound putLong(String k, long v) { map.put(k, new Tag(LONG, v)); return this; }
        public Compound putFloat(String k, float v) { map.put(k, new Tag(FLOAT, v)); return this; }
        public Compound putDouble(String k, double v) { map.put(k, new Tag(DOUBLE, v)); return this; }
        public Compound putString(String k, String v) { map.put(k, new Tag(STRING, v)); return this; }
        public Compound putLongArray(String k, long[] v) { map.put(k, new Tag(LONG_ARRAY, v)); return this; }
        public Compound putIntArray(String k, int[] v) { map.put(k, new Tag(INT_ARRAY, v)); return this; }
        public Compound put(String k, Compound v) { map.put(k, new Tag(COMPOUND, v)); return this; }
        public Compound put(String k, ListTag v) { map.put(k, new Tag(LIST, v)); return this; }
    }

    public static final class ListTag {
        final byte elementType;
        final List<Object> items = new ArrayList<>();

        public ListTag(byte elementType) {
            this.elementType = elementType;
        }

        public ListTag add(Object v) {
            items.add(v);
            return this;
        }

        public int size() {
            return items.size();
        }
    }

    public static void writeRoot(DataOutputStream out, Compound root) throws IOException {
        out.writeByte(COMPOUND);
        out.writeUTF("");
        writePayload(out, COMPOUND, root);
    }

    private static void writePayload(DataOutputStream out, byte type, Object v) throws IOException {
        switch (type) {
            case BYTE: out.writeByte((Byte) v); break;
            case SHORT: out.writeShort((Short) v); break;
            case INT: out.writeInt((Integer) v); break;
            case LONG: out.writeLong((Long) v); break;
            case FLOAT: out.writeFloat((Float) v); break;
            case DOUBLE: out.writeDouble((Double) v); break;
            case STRING: out.writeUTF((String) v); break;
            case BYTE_ARRAY: {
                byte[] a = (byte[]) v;
                out.writeInt(a.length);
                out.write(a);
                break;
            }
            case INT_ARRAY: {
                int[] a = (int[]) v;
                out.writeInt(a.length);
                for (int x : a) out.writeInt(x);
                break;
            }
            case LONG_ARRAY: {
                long[] a = (long[]) v;
                out.writeInt(a.length);
                for (long x : a) out.writeLong(x);
                break;
            }
            case LIST: {
                ListTag l = (ListTag) v;
                out.writeByte(l.items.isEmpty() ? END : l.elementType);
                out.writeInt(l.items.size());
                for (Object o : l.items) writePayload(out, l.elementType, o);
                break;
            }
            case COMPOUND: {
                Compound c = (Compound) v;
                for (Map.Entry<String, Tag> e : c.map.entrySet()) {
                    out.writeByte(e.getValue().type);
                    out.writeUTF(e.getKey());
                    writePayload(out, e.getValue().type, e.getValue().value);
                }
                out.writeByte(END);
                break;
            }
            default: throw new IOException("bad tag type " + type);
        }
    }
}
