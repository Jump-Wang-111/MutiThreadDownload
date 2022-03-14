package Download.Protocol.Torrent;

import Download.Protocol.Torrent.Listener.ConnectionListener;
import Download.Protocol.Torrent.Peer.HandShake;
import Download.Protocol.Torrent.Peer.SharingPeer;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class Connection implements Runnable{

    Logger logger = Logger.getGlobal();

    public static final int PORT_START = 49152;
    public static final int PORT_END = 65534;

    public static final int CONNECTIONS_POOL_SIZE = 20;
    public static final int CONNECTION_THREAD_KEEP_ALIVE_SECONDS = 10;

    private static final int CLIENT_KEEP_ALIVE_MINUTES = 3;

    private final String myPeerId;
    private InetSocketAddress address;
    private final Torrentfile torrent;
    private ServerSocketChannel channel;

    private final Set<ConnectionListener> listeners;
    private ExecutorService executor;
    private Thread thread;
    private boolean stop;

    public Connection(InetAddress localAddress, String id, Torrentfile torrent) throws IOException {
        this.myPeerId = id;
        this.torrent = torrent;
        address = null;

        // 查找合适的端口开启监听
        for(int port = Connection.PORT_START; port <= Connection.PORT_END; port++) {
            InetSocketAddress test = new InetSocketAddress(localAddress, port);

            try{
                this.channel = ServerSocketChannel.open();
                this.channel.socket().bind(test);
                this.channel.configureBlocking(false);
                this.address = test;
                break;
            } catch (IOException e) {
                logger.info("Port" + port + "is used, try next one");
            }
        }

        if(address == null) {
            throw new IOException("No available port to bind!");
        }

        logger.info("listening to port:" + address.getPort());

        listeners = new HashSet<>();
        executor = null;
        thread = null;
    }

    /**
     * 注册一个新的连接监听
     * 处理连接后相关的反应、触发相关动作
     * @param listener 监听者
     */
    public void register(ConnectionListener listener) {
        listeners.add(listener);
    }

    public String getAddress() {
        return address.getAddress().getHostAddress();
    }

    public int getPort() {
        return address.getPort();
    }

    /**
     * 初始化线程池并开始当前线程
     */
    public void start() {
        if (this.channel == null) {
            throw new IllegalStateException("Connection handler cannot be recycled!");
        }

        this.stop = false;

        if (this.executor == null || this.executor.isShutdown()) {
            this.executor = new ThreadPoolExecutor(CONNECTIONS_POOL_SIZE, CONNECTIONS_POOL_SIZE,
                    CONNECTION_THREAD_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
                    new ConnectorThreadFactory());
        }

        if (this.thread == null || !this.thread.isAlive()) {
            this.thread = new Thread(this);
            this.thread.setName("bt-serve");
            this.thread.start();
        }
    }

    /**
     * 为每个连接线程命名
     */
    private static class ConnectorThreadFactory implements ThreadFactory {

        private int number = 0;

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setName("bt-connect-" + ++this.number);
            return t;
        }
    }

    /**
     * 停止Connection线程
     */
    public void stop() {
        this.stop = true;

        if (this.thread != null && this.thread.isAlive()) {
            try {
                this.thread.join();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        if (this.executor != null && !this.executor.isShutdown()) {
            this.executor.shutdownNow();
        }

        this.executor = null;
        this.thread = null;
    }

    /**
     * 关闭ServerSocketChannel，释放绑定的端口
     * @throws IOException ServerSocketChannel
     */
    public void close() throws IOException {
        if (this.channel != null) {
            this.channel.close();
            this.channel = null;
        }
    }

    /**
     * 该对象的主要工作
     * 在收到停止信号之前
     * 无限循环等待其他的peer连接我们
     */
    @Override
    public void run() {
        while (!this.stop) {
            try {
                // 获取连接我们的其他客户端
                SocketChannel client = this.channel.accept();
                // 握手
                if (client != null) {
                    this.handshake(client);
                }
            } catch (SocketTimeoutException ste) {
                // Ignore and go back to sleep
            } catch (IOException e) {
                logger.severe("Unrecoverable error in connection handler: " + e.getMessage());
                this.stop();
            } catch (Exception e) {
                logger.severe(e.getMessage());
                this.stop();
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 完成与其他客户端的握手
     * @param client 与其他客户端的socket channel
     */
    private void handshake(SocketChannel client) {
        try {
            logger.info("New connection client, wait for handshake...");
            // 获取并检查对方的握手信息
            HandShake hs = getHandshake(client, null);
            // 发送我们的握手信息
            int sentBytes = this.sendHandshake(client);
            logger.info("reply with handshake " + sentBytes + " Bytes");

            // socket通道设为不阻塞
            client.configureBlocking(false);
            client.socket().setSoTimeout(CLIENT_KEEP_ALIVE_MINUTES * 60 * 1000);
            fireEvent_NewPeerConnection(client, hs.getPeerId());
        } catch (ParseException e) {
            logger.severe(e.getMessage());
            IOUtils.closeQuietly(client);
        } catch (IOException e) {
            logger.severe("read handshake error: " + e.getMessage());
            if (client.isConnected()) {
                IOUtils.closeQuietly(client);
            }
        }
    }

    /**
     * 获取并检查对面发来的握手消息
     * @param channel SocketChannel
     * @param peerId 对方的的peerId
     * @return 经过检查合格的握手消息
     * @throws IOException IO错误
     * @throws ParseException 解析握手消息过程中出现错误
     */
    private HandShake getHandshake(SocketChannel channel, byte[] peerId) throws IOException, ParseException {
        ByteBuffer len = ByteBuffer.allocate(1);
        ByteBuffer data;

        // 获取握手信息长度
        if (channel.read(len) < len.capacity()) {
            throw new IOException("Handshake size read underrrun");
        }

        len.rewind();
        int pstrlen = len.get();

        // 获取握手信息内容
        data = ByteBuffer.allocate(HandShake.BASE_HANDSHAKE_LENGTH + pstrlen);
        data.put((byte) pstrlen);
        int expected = data.remaining();
        int read = channel.read(data);
        if (read < expected) {
            throw new IOException("Handshake data read underrun (" + read + " < " + expected + " bytes)");
        }

        // 解析并检查握手信息
        data.rewind();
        HandShake hs = HandShake.parse(data);
        if (!Arrays.equals(hs.getInfoHash(),
                this.torrent.getInfoHash().getBytes(StandardCharsets.ISO_8859_1))) {
            throw new ParseException("torrent cannot match in handshake", pstrlen + 9);
        }

        if (peerId != null && !Arrays.equals(hs.getPeerId(), peerId)) {
            throw new ParseException("peerId cannot match in handshake", pstrlen + 29);
        }

        return hs;
    }

    /**
     * 发送我们的握手消息
     * @param channel SocketChannel
     * @return The number of bytes sent, possibly zero
     * @throws IOException IO
     */
    private int sendHandshake(SocketChannel channel) throws IOException {
        return channel
                .write(HandShake.craft(
                        this.torrent.getInfoHash().getBytes(StandardCharsets.ISO_8859_1),
                        this.myPeerId.getBytes(StandardCharsets.ISO_8859_1)).getData());
    }

    /**
     * connection是否正在运行
     * @return 运行状态
     */
    public boolean isAlive() {
        return this.executor != null && !this.executor.isShutdown() && !this.executor.isTerminated();
    }

    /**
     * 当检测到有peer未主动连接我们时
     * 我们去主动连接他
     * @param peer 未连接我们的peer
     */
    public void connect(SharingPeer peer) {
        if (!this.isAlive()) {
            throw new IllegalStateException("Connection handler is not accepting new peers at this time!");
        }

        this.executor.submit(new ConnectTask(this, peer));
    }

    /**
     * 触发新peer连接的监听
     * @param channel SocketChannel
     * @param peerId 连接的peer id
     */
    private void fireEvent_NewPeerConnection(SocketChannel channel, byte[] peerId) {
        for (ConnectionListener listener : this.listeners) {
            listener.handleNewPeerConnection(channel, peerId);
        }
    }

    /**
     * 处理失败的主动连接
     * @param peer 对方peer
     * @param cause 报错信息
     */
    private void fireEvent_FailedPeerConnection(SharingPeer peer, Throwable cause) {
        for (ConnectionListener listener : this.listeners) {
            listener.handleFailedConnection(peer, cause);
        }
    }

    /**
     * 主动连接任务
     */
    private class ConnectTask implements Runnable{

        private final Connection connection;
        private final SharingPeer peer;

        public ConnectTask(Connection connection, SharingPeer peer) {
            this.connection = connection;
            this.peer = peer;
        }

        @Override
        public void run() {

            InetSocketAddress peerAddr = new InetSocketAddress(peer.getAddress(), peer.getPort());
            SocketChannel channel = null;

            try{
                logger.info("try connect to peer: " + peerAddr);
                channel = SocketChannel.open(peerAddr);
                while (!channel.isConnected()) {
                    Thread.sleep(10);
                }

                logger.info("connected! try sewd handshake to peer: " + peerAddr);

                // 设置阻塞
                channel.configureBlocking(true);
                int sentBytes = connection.sendHandshake(channel);
                logger.info("sent handshake " + sentBytes + " bytes...");

                // 获取返回的握手消息
                HandShake hs  = connection.getHandshake(channel,
                        (peer.hasPeerId() ?
                                peer.getPeer_id().getBytes(StandardCharsets.ISO_8859_1)
                                : null));
                logger.info("handshake succeed with peer: " + peerAddr);

                // 转为非阻塞模式进行之后的交互
                channel.configureBlocking(false);
                connection.fireEvent_NewPeerConnection(channel, hs.getPeerId());

            } catch (Exception e) {

                logger.info("connect fail to peer: " + peerAddr);

                if (channel != null && channel.isConnected()) {
                    IOUtils.closeQuietly(channel);
                }
                fireEvent_FailedPeerConnection(peer, e);
            }
        }
    }

}
