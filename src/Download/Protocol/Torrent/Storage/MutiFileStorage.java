package Download.Protocol.Torrent.Storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

public class MutiFileStorage implements Storage {

    Logger logger = Logger.getGlobal();

    private final List<FileStorage> files;
    private final long size;

    public MutiFileStorage(List<FileStorage> files, long size) {
        this.files = files;
        this.size = size;
        logger.info("init storage file at " + files.size() +
                "files\n" + "total size:" + size);
    }

    @Override
    public long size() {
        return this.size;
    }

    @Override
    public int read(ByteBuffer buffer, long offset) throws IOException {
        int requested = buffer.remaining();
        int bytes = 0;

        for (FileOffset fo : this.select(offset, requested)) {
            // TODO: remove cast to int when large ByteBuffer support is implemented in Java.
            buffer.limit((int)(bytes + fo.length));
            bytes += fo.file.read(buffer, fo.offset);
        }

        if (bytes < requested) {
            throw new IOException("Storage collection read underrun!");
        }

        return bytes;
    }

    @Override
    public int write(ByteBuffer buffer, long offset) throws IOException {
        int requested = buffer.remaining();

        int bytes = 0;

        for (FileOffset fo : this.select(offset, requested)) {
            buffer.limit(bytes + (int)fo.length);
            bytes += fo.file.write(buffer, fo.offset);
        }

        if (bytes < requested) {
            throw new IOException("Storage collection write underrun!");
        }

        return bytes;
    }

    @Override
    public void close() throws IOException {
        for (FileStorage file : this.files) {
            file.close();
        }
    }

    @Override
    public void finish() throws IOException {
        for (FileStorage file : this.files) {
            file.finish();
        }
    }

    @Override
    public boolean isFinished() {
        for (FileStorage file : this.files) {
            if (!file.isFinished()) {
                return false;
            }
        }

        return true;
    }

    private static class FileOffset {

        public final FileStorage file;
        public final long offset;
        public final long length;

        FileOffset(FileStorage file, long offset, long length) {
            this.file = file;
            this.offset = offset;
            this.length = length;
        }
    }

    private List<FileOffset> select(long offset, long length) {
        if (offset + length > this.size) {
            throw new IllegalArgumentException("Buffer overrun (" +
                    offset + " + " + length + " > " + this.size + ") !");
        }

        List<FileOffset> selected = new LinkedList<FileOffset>();
        long bytes = 0;

        for (FileStorage file : this.files) {
            if (file.offset() >= offset + length) {
                break;
            }

            if (file.offset() + file.size() < offset) {
                continue;
            }

            long position = offset - file.offset();
            position = position > 0 ? position : 0;
            long size = Math.min(
                    file.size() - position,
                    length - bytes);
            selected.add(new FileOffset(file, position, size));
            bytes += size;
        }

        if (selected.size() == 0 || bytes < length) {
            throw new IllegalStateException("Buffer underrun (only got " +
                    bytes + " out of " + length + " byte(s) requested)!");
        }

        return selected;
    }
}
