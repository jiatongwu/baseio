/*
 * Copyright 2015 The Baseio Project
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.firenio.baseio.buffer;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import com.firenio.baseio.Options;
import com.firenio.baseio.Releasable;
import com.firenio.baseio.common.Unsafe;

public abstract class ByteBuf implements Releasable {

    static final boolean                            AUTO_EXPANSION;
    static final ByteBuf                            EMPTY;
    static final AtomicIntegerFieldUpdater<ByteBuf> refCntUpdater;

    static {
        EMPTY = new EmptyByteBuf();
        AUTO_EXPANSION = Options.isBufAutoExpansion();
        refCntUpdater = AtomicIntegerFieldUpdater.newUpdater(ByteBuf.class, "referenceCount");
    }

    protected volatile int referenceCount = 0;

    public abstract long address();

    public abstract byte absByte(int pos);

    public abstract int absLimit();

    public abstract ByteBuf absLimit(int limit);

    public abstract int absPos();

    public abstract ByteBuf absPos(int absPos);

    protected void addReferenceCount() {
        int referenceCount = this.referenceCount;
        if (refCntUpdater.compareAndSet(this, referenceCount, referenceCount + 1)) {
            return;
        }
        for (;;) {
            referenceCount = this.referenceCount;
            if (refCntUpdater.compareAndSet(this, referenceCount, referenceCount + 1)) {
                break;
            }
        }
    }

    public abstract boolean isPooled();

    public abstract byte[] array();

    public abstract int capacity();

    protected void capacity(int cap) {}

    public abstract ByteBuf clear();

    public abstract ByteBuf duplicate();

    final void ensureWritable(int len) {
        if (AUTO_EXPANSION && len > remaining()) {
            int cap = capacity();
            int wantCap = capacity() + len;
            int newCap = cap + (cap >> 1);
            for (; newCap < wantCap;) {
                newCap = newCap + (newCap >> 1);
            }
            expansion(newCap);
        }
    }

    public abstract void expansion(int cap);

    public abstract ByteBuf flip();

    public void get(byte[] dst) {
        get(dst, 0, dst.length);
    }

    public abstract void get(byte[] dst, int offset, int length);

    public int get(ByteBuf dst) {
        return dst.put(this, dst.remaining());
    }

    public int get(ByteBuf dst, int length) {
        return dst.put(this, length);
    }

    public int get(ByteBuffer dst) {
        int len = Math.min(remaining(), dst.remaining());
        if (len == 0) {
            return 0;
        }
        return get0(dst, len);
    }

    public int get(ByteBuffer dst, int length) {
        int len = Math.min(remaining(), dst.remaining());
        len = Math.min(len, length);
        if (len == 0) {
            return 0;
        }
        return get0(dst, len);
    }

    protected abstract int get0(ByteBuffer dst, int len);

    public abstract byte getByte();

    public abstract byte getByte(int index);

    public byte[] getBytes() {
        return getBytes(remaining());
    }

    public byte[] getBytes(int length) {
        byte[] bytes = new byte[length];
        get(bytes);
        return bytes;
    }

    public abstract int getInt();

    public abstract int getInt(int index);

    public abstract int getIntLE();

    public abstract int getIntLE(int index);

    public abstract long getLong();

    public abstract long getLong(int index);

    public abstract long getLongLE();

    public abstract long getLongLE(int index);

    public abstract ByteBuffer getNioBuffer();

    public abstract short getShort();

    public abstract short getShort(int index);

    public abstract short getShortLE();

    public abstract short getShortLE(int index);

    public abstract short getUnsignedByte();

    public abstract short getUnsignedByte(int index);

    public abstract long getUnsignedInt();

    public abstract long getUnsignedInt(int index);

    public abstract long getUnsignedIntLE();

    public abstract long getUnsignedIntLE(int index);

    public abstract int getUnsignedShort();

    public abstract int getUnsignedShort(int index);

    public abstract int getUnsignedShortLE();

    public abstract int getUnsignedShortLE(int index);

    public abstract boolean hasArray();

    public abstract boolean hasRemaining();

    public int indexOf(byte b) {
        return indexOf(b, absPos());
    }

    public int indexOf(byte b, int absPos) {
        return indexOf(b, absPos, remaining());
    }

    public abstract int indexOf(byte b, int absPos, int size);

    @Override
    public boolean isReleased() {
        return referenceCount < 1;
    }

    protected int ix(int index) {
        return offset() + index;
    }

    public int lastIndexOf(byte b) {
        return lastIndexOf(b, absLimit());
    }

    public int lastIndexOf(byte b, int absPos) {
        return lastIndexOf(b, absPos, remaining());
    }

    public abstract int lastIndexOf(byte b, int absPos, int size);

    public abstract int limit();

    public abstract ByteBuf limit(int limit);

    public abstract ByteBuf markL();

    public abstract ByteBuf markP();

    public abstract ByteBuffer nioBuffer();

    protected int offset() {
        return 0;
    }

    protected void offset(int offset) {}

    public abstract int position();

    public abstract ByteBuf position(int position);

    protected ByteBuf produce(int unitOffset, int unitEnd) {
        return this;
    }

    public void put(byte[] src) {
        put(src, 0, src.length);
    }

    public void put(byte[] src, int offset, int length) {
        ensureWritable(length);
        put0(src, offset, length);
    }

    public int put(ByteBuf src) {
        int len = src.remaining();
        if (len == 0) {
            return 0;
        }
        return put0(src, len);
    }

    public int put(ByteBuf src, int length) {
        int len = Math.min(length, src.remaining());
        if (len == 0) {
            return 0;
        }
        return put0(src, len);
    }

    public int put(ByteBuffer src) {
        int len = src.remaining();
        if (len == 0) {
            return 0;
        }
        return put0(src, len);
    }

    public int put(ByteBuffer src, int length) {
        int len = Math.min(length, src.remaining());
        if (len == 0) {
            return 0;
        }
        return put0(src, len);
    }

    protected abstract void put0(byte[] src, int offset, int length);

    protected int put0(ByteBuf src, int len) {
        if (AUTO_EXPANSION) {
            ensureWritable(len);
            return put00(src, len);
        } else {
            if (!hasRemaining()) {
                return 0;
            }
            return put00(src, Math.min(remaining(), len));
        }
    }

    protected int put0(ByteBuffer src, int len) {
        if (AUTO_EXPANSION) {
            ensureWritable(len);
            return put00(src, len);
        } else {
            if (!hasRemaining()) {
                return 0;
            }
            return put00(src, Math.min(remaining(), len));
        }
    }

    protected abstract int put00(ByteBuf src, int len);

    protected abstract int put00(ByteBuffer src, int len);

    public void putByte(byte b) {
        ensureWritable(1);
        putByte0(b);
    }

    public abstract void putByte(int index, byte b);

    protected abstract void putByte0(byte b);

    public void putInt(int value) {
        ensureWritable(4);
        putInt0(value);
    }

    public abstract void putInt(int index, int value);

    protected abstract void putInt0(int value);

    public void putIntLE(int value) {
        ensureWritable(4);
        putIntLE0(value);
    }

    public abstract void putIntLE(int index, int value);

    protected abstract void putIntLE0(int value);

    public abstract void putLong(int index, long value);

    public void putLong(long value) {
        ensureWritable(8);
        putLong0(value);
    }

    protected abstract void putLong0(long value);

    public abstract void putLongLE(int index, long value);

    public void putLongLE(long value) {
        ensureWritable(8);
        putLongLE0(value);
    }

    protected abstract void putLongLE0(long value);

    public abstract void putShort(int index, short value);

    public void putShort(short value) {
        ensureWritable(2);
        putShort0(value);
    }

    protected abstract void putShort0(short value);

    public abstract void putShortLE(int index, short value);

    public void putShortLE(short value) {
        ensureWritable(2);
        putShortLE0(value);
    }

    protected abstract void putShortLE0(short value);

    public abstract void putUnsignedInt(int index, long value);

    public void putUnsignedInt(long value) {
        ensureWritable(4);
        putUnsignedInt0(value);
    }

    protected abstract void putUnsignedInt0(long value);

    public abstract void putUnsignedIntLE(int index, long value);

    public void putUnsignedIntLE(long value) {
        ensureWritable(4);
        putUnsignedIntLE0(value);
    }

    protected abstract void putUnsignedIntLE0(long value);

    public void putUnsignedShort(int value) {
        ensureWritable(2);
        putUnsignedShort0(value);
    }

    public abstract void putUnsignedShort(int index, int value);

    protected abstract void putUnsignedShort0(int value);

    public void putUnsignedShortLE(int value) {
        ensureWritable(2);
        putUnsignedShortLE0(value);
    }

    public abstract void putUnsignedShortLE(int index, int value);

    protected abstract void putUnsignedShortLE0(int value);

    @Override
    public final void release() {
        int referenceCount = this.referenceCount;
        if (referenceCount < 1) {
            return;
        }
        if (refCntUpdater.compareAndSet(this, referenceCount, referenceCount - 1)) {
            if (referenceCount == 1) {
                release0();
            }
            return;
        }
        for (;;) {
            referenceCount = this.referenceCount;
            if (referenceCount < 1) {
                return;
            }
            if (refCntUpdater.compareAndSet(this, referenceCount, referenceCount - 1)) {
                if (referenceCount == 1) {
                    release0();
                }
                return;
            }
        }
    }

    protected abstract void release0();

    public abstract int remaining();

    public abstract ByteBuf resetL();

    public abstract ByteBuf resetP();

    public abstract ByteBuf reverse();

    public abstract ByteBuf skip(int length);

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        b.append(getClass().getSimpleName());
        b.append("[pos=");
        b.append(position());
        b.append(",lim=");
        b.append(limit());
        b.append(",cap=");
        b.append(capacity());
        b.append(",remaining=");
        b.append(remaining());
        b.append(",offset=");
        b.append(offset());
        b.append("]");
        return b.toString();
    }

    protected int unitOffset() {
        return -1;

    }

    protected void unitOffset(int unitOffset) {}

    static final class EmptyByteBuf extends UnpooledHeapByteBuf {

        EmptyByteBuf() {
            super(new byte[] {}, 0, 0);
        }

        @Override
        public ByteBuf duplicate() {
            return this;
        }

        @Override
        public boolean isReleased() {
            return true;
        }

    }

    static void copy(byte[] src, int srcPos, byte[] dst, int dstPos, int len) {
        System.arraycopy(src, srcPos, dst, dstPos, len);
    }

    static void copy(byte[] src, int srcPos, long dst, int len) {
        Unsafe.copyFromArray(src, srcPos, dst, len);
    }

    static void copy(long src, byte[] dst, int dstPos, int len) {
        Unsafe.copyToArray(src, dst, dstPos, len);
    }

    static void copy(long src, long dst, int len) {
        Unsafe.copyMemory(src, dst, len);
    }

    public static ByteBuf direct(int cap) {
        return wrap(ByteBuffer.allocateDirect(cap));
    }

    public static final ByteBuf empty() {
        return EMPTY;
    }

    public static ByteBuf heap(int cap) {
        return wrap(new byte[cap]);
    }

    public static ByteBuf wrap(byte[] data) {
        return wrap(data, 0, data.length);
    }

    public static ByteBuf wrap(byte[] data, int offset, int length) {
        return new UnpooledHeapByteBuf(data, offset, length);
    }

    public static ByteBuf wrap(ByteBuffer buffer) {
        if (buffer.isDirect()) {
            return new UnpooledDirectByteBuf(buffer);
        }else{
            return new UnpooledHeapByteBuf(buffer);
        }
    }

}
