package Download.Protocol.Torrent.Storage;

import Download.Protocol.Torrent.Storage.Storage;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.logging.Logger;

public class FileStorage implements Storage {

    Logger logger = Logger.getGlobal();

    private final File target;
    private final File partial;
    private final long offset;
    private final long size;

    private RandomAccessFile raf;
    private FileChannel channel;
    private File current;

    public FileStorage(File file, long offset, long size)
            throws IOException {
        this.target = file;
        this.offset = offset;
        this.size = size;

        this.partial = new File(this.target.getAbsolutePath() +
                Storage.PARTIAL_FILE_NAME_SUFFIX);

        // 续传策略
        if (this.partial.exists()) {
            logger.info("Partial download found at " + this.partial.getAbsolutePath());
            this.current = this.partial;
        } else if (!this.target.exists()) {
            logger.info("Downloading new file to " + this.partial.getAbsolutePath());
            this.current = this.partial;
        } else {
            logger.info("Using existing file: " + this.target.getAbsolutePath());
            this.current = this.target;
        }

        this.raf = new RandomAccessFile(this.current, "rw");

        if (file.length() != this.size) {
            // Set the file length to the appropriate size, eventually truncating
            // or extending the file if it already exists with a different size.
            this.raf.setLength(this.size);
        }

        this.channel = raf.getChannel();
        logger.info("init storage file at " + current.getAbsolutePath() +
                "\n" + "offset: " + offset +
                "\n" + "size:" + size);
    }

    public long offset() {
        return this.offset;
    }

    @Override
    public long size() {
        return this.size;
    }

    @Override
    public int read(ByteBuffer buffer, long offset) throws IOException {
        int requested = buffer.remaining();

        if (offset + requested > this.size) {
            throw new IllegalArgumentException("Invalid storage read request!");
        }

        int bytes = this.channel.read(buffer, offset);
        if (bytes < requested) {
            throw new IOException("Storage underrun!");
        }

        return bytes;
    }

    @Override
    public int write(ByteBuffer buffer, long offset) throws IOException {
        int requested = buffer.remaining();

        if (offset + requested > this.size) {
            throw new IllegalArgumentException("Invalid storage write request!");
        }

        return this.channel.write(buffer, offset);
    }

    @Override
    public synchronized void close() throws IOException {
        logger.info("Closing file channel to " + this.current.getName() + "...");
        if (this.channel.isOpen()) {
            this.channel.force(true);
        }
        this.raf.close();
    }

    /**
     * 将.part文件合并到最终文件
     */
    @Override
    public synchronized void finish() throws IOException {
        logger.info("Closing file channel to " + this.current.getName() +
                " (download complete).");
        if (this.channel.isOpen()) {
            this.channel.force(true);
        }

        // 如果已经在目标文件则跳过该步骤
        if (this.isFinished()) {
            return;
        }

        this.raf.close();
        FileUtils.deleteQuietly(this.target);
        FileUtils.moveFile(this.current, this.target);

        logger.info("Re-opening torrent byte storage at " +
                this.target.getAbsolutePath());

        this.raf = new RandomAccessFile(this.target, "rw");
        this.raf.setLength(this.size);
        this.channel = this.raf.getChannel();
        this.current = this.target;

        FileUtils.deleteQuietly(this.partial);
        logger.info("Moved torrent data from " +
                this.partial.getName() +
                "to " + this.target.getName());
    }

    @Override
    public boolean isFinished() {
        return this.current.equals(this.target);
    }
}
