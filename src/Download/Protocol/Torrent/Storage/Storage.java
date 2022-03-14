package Download.Protocol.Torrent.Storage;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface Storage {

    static final String PARTIAL_FILE_NAME_SUFFIX = ".part";

    long size();

    /**
     * 从内存中读取数据
     * @param buffer ByteBuffer
     * @param offset 读取内容的偏移
     * @return 读取字节
     * @throws IOException 读取时IO错误
     */
    int read(ByteBuffer buffer, long offset) throws IOException;

    int write(ByteBuffer buffer, long offset) throws IOException;

    public void close() throws IOException;

    public void finish() throws IOException;

    public boolean isFinished();
}
