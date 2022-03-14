package Download.Protocol;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class Ftp implements Protocols {

    FTPClient ftp = null;

    /**
     * url格式如下，构造时会进行解析
     * ftp://用户名:密码@地址:端口/远程文件存储地址
     */
    String url;
    String host = null;
    int port = 0;
    String username = null;
    String password = null;

    // 远程文件位置
    String remote;

    public Ftp(String url) {
        this.url = url;
        splitUrl();
    }

    @Override
    public boolean connect() throws IOException {
        ftp = new FTPClient();
        // 连接FPT服务器,设置IP及端口
        ftp.connect(host, port);
        // 设置用户名和密码
        ftp.login(username, password);
        // 设置连接超时时间,5000毫秒
        ftp.setConnectTimeout(5000);
        // 设置中文编码集，防止中文乱码
        ftp.setControlEncoding("UTF-8");
        // 以二进制方式传输
        ftp.setFileType(FTPClient.BINARY_FILE_TYPE);
        // 检测是否连接成功
        if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
            ftp.disconnect();
            return false;
        } else {
            return true;
        }
    }

    @Override
    public NetFile getContentInfo() throws IOException {
        FTPFile[] files = ftp.listFiles(new String(
                remote.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
        if (files.length != 1) {
            return new NetFile(0, null);
        }
        FTPFile file = files[0];
        return new NetFile(file.getSize(), file.getName());
    }

    @Override
    public boolean checkMutiThread() {
        return true;
    }

    @Override
    public InputStream getDownloadBlock(long start, long end) throws IOException {
        ftp.setRestartOffset(start);
        InputStream in = ftp.retrieveFileStream(new String(
                remote.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.ISO_8859_1));

        File file = new File(".\\" + this.hashCode());
        byte[] bytes = new byte[1024];
        OutputStream out = new FileOutputStream(file);

        byte[] buffer = new byte[1024];
        int readLength;
        int downloadSize = 0;
        while((readLength=in.read(buffer)) > 0) {
            out.write(buffer, 0, readLength);
            downloadSize += readLength;
            if(downloadSize >= end - start) {
                break;
            }
        }
        out.flush();

        in.close();
        out.close();

        ftp.completePendingCommand();

        return new FileInputStream(file);
    }

    @Override
    public InputStream getAll() throws IOException {
        return null;
    }

    @Override
    public int getType() {
        return Protocols.FTP;
    }

    @Override
    public String getSource() {
        return url;
    }

    private void splitUrl() {
        String temp = url;
        temp = temp.substring(6);

        int split = temp.indexOf(':');
        username = temp.substring(0, split);
        temp = temp.substring(split + 1);

        split = temp.indexOf('@');
        password = temp.substring(0, split);
        temp = temp.substring(split + 1);

        split = temp.indexOf(':');
        host = temp.substring(0, split);
        temp = temp.substring(split + 1);

        split = temp.indexOf('/');
        port = Integer.parseInt(temp.substring(0, split));
        temp = temp.substring(split);

        remote = '.' + temp.replace('/', '\\');
    }
}
