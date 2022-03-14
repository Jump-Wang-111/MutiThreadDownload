package Download;

import Download.Protocol.Ftp;
import Download.Protocol.NetFile;
import Download.Protocol.Protocols;
import Download.Protocol.Torrent.Torrent;
import Download.Protocol.Torrent.Torrentfile;

import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.logging.*;
import java.util.concurrent.*;

public class Download {

    // 输入位置
    private final String inputFile;
    // 输出地址
    private String filename;
    // 线程数
    private final int threadNum;
    // 网络协议
    private Protocols protocols = null;
    // 文件总长度
    private long fileLength;
    // 是否覆盖同名文件
    private final boolean ifOverride;
    // 线程池
    private final ThreadPoolExecutor executor;
    // 信号量
    private final Semaphore semaphore;
    //计数器
    private CountDownLatch countDownLatch;
    // 开始时间
    private volatile long startTime;
    // 存放全部文件块
    private BlockList blockList;
    // 每次请求的block大小
    private long blocksize;
    // 存储多线程下的message
    private final LinkedBlockingQueue<String> mesQueue;
    // 日志
    private final Logger logger;

    /**
     * 初始化必要信息
     * @param protocols 封装了下载链接的协议
     * @param t 线程数
     * @param output 下载文件的输出地址
     */
    public Download(Protocols protocols, int t, String output, String inputFile) {
        this.inputFile = inputFile;
        this.protocols = protocols;
        filename = output;
        threadNum = t;
        executor = new ThreadPoolExecutor(t, t, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        semaphore = new Semaphore(t);
        countDownLatch = new CountDownLatch(t);
        fileLength = 0;
        startTime = 0;
        ifOverride = false;
        blocksize = 1024 * 1024;
        mesQueue = new LinkedBlockingQueue<>();
        logger = Logger.getGlobal();
    }

    public Download(Protocols protocols, int t, String output) {
        this(protocols, t, output, null);
    }

    /**
     * Download的主体逻辑部分
     */
    public boolean start() {

        if(protocols.getType() == Protocols.BITTORRENT) {
            try {
                ((Torrent) protocols).download(-1);

            } catch (Exception e) {
                e.printStackTrace();
            }
            return true;
        }

        if(protocols.getType() == Protocols.MAGNET) {
            logger.info("please change into torrent.");
            return true;
        }

        // 获取文件信息
        try {
            if(!protocols.connect()) {
                logger.info("protocol cannot connect");
                return false;
            }
            getContentInfo();
        } catch (IOException e) {
            logger.severe(e.getMessage());
        }

        logger.info("FileLength: " + fileLength + "B");

        if(fileLength <= 0) {
            logger.info("cannot get file length");
            return false;
        }

        // 启动监视器
        monitor();

        // 初始化文件
        try {
            if(!initFile()) {
                return false;
            }
        } catch (Exception e) {
            putQueue(e.getMessage());
        }

        startTime = System.currentTimeMillis();
        logger.info("Start download.");

        // 支持多线程下载
        if(checkRange()) {
            // 初始化分段下载block
            initBlock();
            // 调用线程控制模块
            threadControl();
        }
        // 不支持多线程下载
        else {
            putQueue("This address doesn't support muti-thread download");
            putQueue("Going to use single thread");
            singleDownload();
        }

        reportDownload();
        return true;
    }

    /**
     * 在下载完成后输出本次下载的相关统计
     * 包括下载总量、用时、平均速度
     * 以及关闭线程池
     */
    private void reportDownload() {

        // 处理文件大小
        double fsize = (long)((double)fileLength / 1024 / 1024 * 100) / 100.0;

        // 获取用时
        long useTime = System.currentTimeMillis() - startTime;
        long minutes = useTime / 1000 / 60;
        long hours = minutes / 60;
        long seconds = ((useTime - minutes * 60 * 1000)) / 1000;
        String time = String.format("%s h %s m %s s", hours, minutes, seconds);

        // 获取平均速度
        double speed = (double)fileLength / 1024.0 / useTime;
        speed = (long)(speed * 100) / 100.0;

        putQueue(String.format("Download: %s MB, useTime: %s, averageSpeed: %s MB/s",
                fsize, time, speed));

        executor.shutdown();
    }

    /**
     * 创建文件
     */
    private void createFile() throws IOException{
        // 使用 try-with-resource 实现 auto close
        try(RandomAccessFile rfile = new RandomAccessFile(filename, "rwd")) {

            rfile.setLength(fileLength);

        } catch (FileNotFoundException e) {
            putQueue(e.getMessage());
        }
    }

    /**
     * 获取文件长度
     */
    private void getContentInfo() throws IOException{

        logger.info("Try to get Content-Length...");

        NetFile f = protocols.getContentInfo();

        fileLength = f.getLength();
        filename = filename + File.separator + f.getName();

    }

    /**
     * 初始化本地文件
     * @throws IOException 检查文件存在、删除文件
     */
    private boolean initFile() throws IOException {

        logger.info("Init local file...");

        File target = new File(filename);
        // 存在同名文件，考虑是否覆盖
        if(target.exists()) {
            if(ifOverride) {
                Files.deleteIfExists(target.toPath());
                createFile();
            }
            else {
                logger.info("File has already existed!");
                return false;
            }
        }
        // 创建文件
        else {
            Files.createDirectory(target.getParentFile().toPath());
            createFile();
        }
        return true;
    }

    /**
     * 检查是否支持多线程下载
     * @return 支持返回true
     */
    private boolean checkRange() {
        return protocols.checkMutiThread();
    }

    /**
     * 根据文件程度更新块大小
     * 将文件切割成块放入list以便分配给各线程
     */
    private void initBlock() {
        // 更新块大小
        blocksize = fileLength / (threadNum * 3L);
//        blocksize = Math.min(blocksize, fileLength);
        blockList = BlockList.getInstance();
        long now = fileLength;
        long blockstart = 0, blockend;
        // 切割文件成块，放入list
        while(now > 0) {
            long len = Math.min(blocksize - 1, now);
            blockend = blockstart + len;
            blockend = Math.min(fileLength, blockend);
            blockList.getList().add(new Block(blockstart, blockend));

            blockstart = blockend + 1;
            now -= blocksize;
        }
    }

    /**
     * 定义守护线程用来监视
     * 不断从公有的queue中取出消息打印
     */
    private void monitor() {
        // 用来在多线程中按顺序输出信息
        Thread monitorThread = new Thread(() -> {
            while (true) {
                try {
                    String mes = mesQueue.take();
                    logger.info(mes);
                } catch (Exception e) {
                    logger.severe(e.getMessage());
                }
            }
        });
        monitorThread.setName("monitorThread");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    /**
     * 单线程下载
     */
    private void singleDownload() {
        SingleDownload sd = new SingleDownload(protocols, filename);
        sd.start();
    }

    /**
     * put mes to queue
     */
    private void putQueue(String mes) {
        try {
            mesQueue.put(mes);
        } catch (InterruptedException e) {
            logger.info(e.getMessage());
        }
    }

    /**
     * 线程控制
     * 通过将不同的block分配给不同的task，实现线程的任务分配
     */
    private void threadControl() {

        // 倒计时计数器记下当前任务数
        countDownLatch = new CountDownLatch(blockList.getList().size());

        for(Block b : blockList.getList()) {
            executor.submit(new DownloadTask(b));
        }

        // 主线程等待待线程同步
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            putQueue(e.getMessage());
        }

        // block没有完全remove说明有的block下载失败了，那就再来一遍
        if(!blockList.getList().isEmpty()) {
            threadControl();
        }
    }

    /**
     * 设置内部类实现Runnable完成线程任务
     */
    private final class DownloadTask implements Runnable{

        private final Block block;

        private DownloadTask(Block b) {
            block = b;
        }
        @Override
        public void run() {

            try{
                semaphore.acquire();
            } catch (InterruptedException e) {
                putQueue(e.getMessage());
            }

            SingleDownload sd = null;
            if(protocols.getType() == Protocols.FTP) {
                sd = new SingleDownload(new Ftp(protocols.getSource()), filename, block);
            }
            else {
                sd = new SingleDownload(protocols, filename, block);
            }

            sd.start();

            countDownLatch.countDown();
            semaphore.release();

        }
    }

    public static void main(String[] args) {
        // genshen
        var url1 = "https://ys-api.mihoyo.com/event/download_porter/link/ys_cn/official/pc_default";
        // 王者荣耀1.8G
        var url2 = "https://count.iuuu9.com/d.php?id=501616&urlos=android";
        // 非下载网址
        var url3 = "https://www.coder.work/article/4681889";
        // ftp的测试地址
        var ftp_url = "ftp://test1:test1@192.168.1.105:21/game/download.exe";
        try {
            Torrentfile torrent = new Torrentfile(
                    new File(".\\【豌豆字幕组&amp;风之圣殿字幕组】★04月新番[鬼灭之刃 Kimetsu_no_Yaiba][01-26][合集][简体][1080P][MP4].torrent"),
                    new File("E:\\output"),
                    8);
            Download d = new Download(new Torrent(torrent), 8, null, null);
            d.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
