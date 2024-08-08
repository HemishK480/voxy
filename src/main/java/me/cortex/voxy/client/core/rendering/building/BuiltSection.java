package me.cortex.voxy.client.core.rendering.building;

import me.cortex.voxy.common.util.MemoryBuffer;

import java.util.Arrays;

//TODO: also have an AABB size stored
public final class BuiltSection {
    public static final boolean VERIFY_BUILT_SECTION_OFFSETS = System.getProperty("voxy.verifyBuiltSectionOffsets", "true").equals("true");
    public final long position;
    public final int aabb;
    public final MemoryBuffer geometryBuffer;
    public final int[] offsets;

    private BuiltSection(long position) {
        this(position, -1, null, null);
    }

    public static BuiltSection empty(long position) {
        return new BuiltSection(position);
    }

    public BuiltSection(long position, int aabb, MemoryBuffer geometryBuffer, int[] offsets) {
        this.position = position;
        this.aabb = aabb;
        this.geometryBuffer = geometryBuffer;
        this.offsets = offsets;
        if (offsets != null && VERIFY_BUILT_SECTION_OFFSETS) {
            for (int i = 0; i < offsets.length-1; i++) {
                int delta = offsets[i+1] - offsets[i];
                if (delta<0||delta>=(1<<16)) {
                    throw new IllegalArgumentException("Offsets out of range");
                }
            }
        }
    }

    public BuiltSection clone() {
        return new BuiltSection(this.position, this.aabb, this.geometryBuffer!=null?this.geometryBuffer.copy():null, this.offsets!=null?Arrays.copyOf(this.offsets, this.offsets.length):null);
    }

    public void free() {
        if (this.geometryBuffer != null) {
            this.geometryBuffer.free();
        }
    }

    public boolean isEmpty() {
        return this.geometryBuffer == null;
    }
}
