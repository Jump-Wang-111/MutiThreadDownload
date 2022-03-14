package Download.Protocol.Torrent.Peer;

import Download.Protocol.Torrent.Storage.Storage;
import Download.Protocol.Torrent.Torrentfile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

public class Piece implements Comparable<Piece>{

    Logger logger = Logger.getGlobal();

    private Storage storage;
    private final int index;
    private final long offset;
    long length;
    private final byte[] hash;
    private final boolean seeder;

    private volatile boolean valid;
    private int seen;

    private ByteBuffer data;

    public Piece(Storage storage, int index, long offset,
                 long length, byte[] hash, boolean seeder) {
        this.storage = storage;
        this.index = index;
        this.offset = offset;
        this.length = length;
        this.hash = hash;
        this.seeder = seeder;

        // 初始默认为不合法
        valid = false;
        // 初始认为不可见
        seen = 0;

        data = null;

    }

    public boolean isValid() {
        return this.valid;
    }

    public int getIndex() {
        return this.index;
    }

    /**
     * 判断在当前的连接中这个piece是否可用
     * @return boolean
     */
    public boolean available() {
        return this.seen > 0;
    }

    /**
     * 增加被关注标记
     * @param peer SharingPeer
     */
    public void seenAt(SharingPeer peer) {
        this.seen++;
    }

    /**
     * 减少被关注标记
     * @param peer SharingPeer
     */
    public void noLongerSeenAt(SharingPeer peer) {
        this.seen--;
    }

    /**
     * 获取该piece的字节长度
     * @return long
     */
    public long size() {
        return this.length;
    }

    /**
     * 检测存储中piece的哈希值是否正确
     * @return boolean
     * @throws IOException 读取内存时IO出错
     */
    public synchronized boolean validate() throws IOException {
        if (this.seeder) {
            logger.info("Skipping validation of piece (seeder mode).");
            this.valid = true;
            return true;
        }

        logger.info("Validating piece #" + index);
        this.valid = false;

        ByteBuffer buffer = this._read(0, this.length);
        byte[] data = new byte[(int)this.length];
        buffer.get(data);
        try {
            this.valid = Arrays.equals(Torrentfile.hash(data), this.hash);
        } catch (NoSuchAlgorithmException e) {
            logger.severe(e.getMessage());
            this.valid = false;
        }

        return this.isValid();
    }

    /**
     * 从存储中读取该piece的方法
     * 并且不检测合法性
     * @param offset piece内开始读取的偏移
     * @param length 读取长度
     * @return ByteBuffer
     * @throws IOException 读取时IO出错
     */
    private ByteBuffer _read(long offset, long length) throws IOException {
        if (offset + length > this.length) {
            throw new IllegalArgumentException("Piece#" + this.index +
                    " overrun (" + offset + " + " + length + " > " +
                    this.length + ") !");
        }

        // TODO: remove cast to int when large ByteBuffer support is implemented in Java.
        ByteBuffer buffer = ByteBuffer.allocate((int)length);
        int bytes = storage.read(buffer, this.offset + offset);
        buffer.rewind();
        buffer.limit(Math.max(bytes, 0));
        return buffer;
    }

    /**
     * public方法，读取之前检测valid
     * @param offset piece内开始读取的偏移
     * @param length 读取长度
     * @return ByteBuffer
     * @throws IllegalArgumentException piece index 越界
     * @throws IllegalStateException invalid piece
     * @throws IOException 读取io出错
     */
    public ByteBuffer read(long offset, int length)
            throws IllegalArgumentException, IllegalStateException, IOException {
        if (!this.valid) {
            throw new IllegalStateException("Attempting to read an " +
                    "known-to-be invalid piece!");
        }

        return this._read(offset, length);
    }

    /**
     * 将给定offset位置的block保存到当前piece
     * @param block ByteBuffer 含有一部分piece的内容
     * @param offset 这个block在当前piece内的偏移
     */
    public synchronized void record(ByteBuffer block, int offset)
            throws IOException {
        if (this.data == null || offset == 0) {
            // TODO: remove cast to int when large ByteBuffer support is implemented in Java.
            this.data = ByteBuffer.allocate((int)this.length);
        }

        int pos = block.position();
        this.data.position(offset);
        this.data.put(block);
        block.position(pos);

        if (block.remaining() + offset == this.length) {
            this.data.rewind();
            logger.info("recording block to piece...");
            this.storage.write(this.data, this.offset);
            this.data = null;
        }
    }

    /**
     * piece比较方法
     */
    @Override
    public int compareTo(Piece o) {
        if (this.seen != o.seen) {
            return this.seen < o.seen ? -1 : 1;
        }
        return Integer.compare(this.index, o.index);
    }

    // 暂时还没搞懂有啥用(
    public static class CallableHasher implements Callable<Piece> {

        private final Piece piece;

        public CallableHasher(Piece piece) {
            this.piece = piece;
        }

        @Override
        public Piece call() throws IOException {
            this.piece.validate();
            return this.piece;
        }
    }
}
