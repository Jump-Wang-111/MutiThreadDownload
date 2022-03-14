package Download;

import Download.Protocol.Protocols;

import java.io.*;
import java.nio.file.Files;
import java.util.logging.Logger;

/**
 * 单线程的下载
 * 按两种方式构造有不同作用
 * 只用协议构造对应下载整个链接中的文件
 * 用链接和块构造对应对某一块进行下载
 */
public class SingleDownload {

    private Protocols protocols;

    private String filename;

    private Block block = null;

    private final Logger logger = Logger.getGlobal();

    SingleDownload(Protocols protocols, String filename) {
        this.protocols = protocols;
        this.filename = filename;
    }

    SingleDownload(Protocols protocols, String filename, Block block) {
        this.protocols = protocols;
        this.filename = filename;
        this.block = block;
    }

    public void start() {
        try {
            protocols.connect();
        } catch (IOException e) {
            logger.severe("connect false");
        }

        if(block == null) {
            downloadAll();
        }
        else {
            downloadBlock();
        }
    }

    private void downloadBlock() {

        InputStream in = null;

        try(RandomAccessFile rfile = new RandomAccessFile(filename, "rwd")) {

            rfile.seek(block.getBlockstart());

            logger.info("block " + block.getBlockstart() + "-" + block.getBlockend() + " start");

            in = protocols.getDownloadBlock(block.getBlockstart(), block.getBlockend());

            byte[] buffer = new byte[1024];
            int readLength;
            while((readLength = in.read(buffer)) > 0) {
                rfile.write(buffer, 0, readLength);
            }

            in.close();

            File tmp = new File(".\\" + protocols.hashCode());
            if(tmp.exists()) {
                Files.deleteIfExists(tmp.toPath());
            }

            BlockList.getInstance().getList().remove(block);

        } catch (IOException e) {
            logger.info(String.format("%s-%s failed, has been added into the download list again.",
                    block.getBlockstart(), block.getBlockend()));
            logger.info(e.getMessage());
        }

    }

    private void downloadAll() {
        InputStream in = null;
        OutputStream out = null;
        try {

            in = protocols.getAll();

            File file = new File(filename);
            out = new FileOutputStream(file);

            byte[] buffer = new byte[1024];
            int readLength;
            while((readLength=in.read(buffer)) > 0) {
                out.write(buffer, 0, readLength);
            }

            out.flush();

        } catch (Exception e) {
            logger.severe(e.getMessage());
        } finally {
            try {
                if(in != null) {
                    in.close();
                }
                if(out != null) {
                    out.close();
                }
            } catch (IOException e) {
                logger.severe(e.getMessage());
            }
        }
    }

}
