package Download.Protocol;

import java.io.IOException;
import java.io.InputStream;

public interface Protocols {

    int HTTP = 0;
    int FTP = 1;
    int BITTORRENT = 2;
    int MAGNET = 3;

    /**
     * 连接下载地址
     * 确认是否可以进行下载
     * @return
     */
    boolean connect() throws IOException;

    /**
     * 获取要下载的文件长度
     * @return 返回要下载的文件长度
     */
    NetFile getContentInfo() throws IOException;

    /**
     * 检查目标服务器是否支持多线程
     * @return 支持情况
     */
    boolean checkMutiThread();

    /**
     * 从服务器申请对应快的内容
     * @return 返回装有对应区块内容的输入流
     */
    InputStream getDownloadBlock(long start, long end) throws IOException;

    /**
     * 从服务器申请整个文件的内容
     * @return 返回整个文件的输入流
     */
    InputStream getAll() throws IOException;

    /**
     * 返回协议的类型
     * @return 返回以int区分的协议标识
     */
    int getType();

    /**
     * 获取下载的来源，如网址或者文件地址
     * @return 下载地址或者文件地址
     */
    String getSource();

}
