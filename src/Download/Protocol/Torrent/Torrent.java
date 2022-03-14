package Download.Protocol.Torrent;

import Download.Protocol.NetFile;
import Download.Protocol.Torrent.Listener.ConnectionListener;
import Download.Protocol.Torrent.Listener.PeerListener;
import Download.Protocol.Torrent.Listener.TrackerListener;
import Download.Protocol.Protocols;
import Download.Protocol.Torrent.Peer.Peer;
import Download.Protocol.Torrent.Peer.PeerMessage;
import Download.Protocol.Torrent.Peer.Piece;
import Download.Protocol.Torrent.Peer.SharingPeer;
import Download.Protocol.Torrent.Tracker.ManageTracker;
import Download.Protocol.Torrent.Tracker.TrackerException;
import Download.Protocol.Torrent.Tracker.TrackerMessage;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

public class Torrent extends Observable
        implements Runnable, Protocols, ConnectionListener, TrackerListener, PeerListener {

    Logger logger = Logger.getGlobal();

    /**
     * 设置不阻塞的频率，单位：秒
     * 这里每10秒重新进行决策，对上传速度快的peer实行不阻塞态度
     */
    private static final int UNCHOKING_FREQUENCY = 3;

    /**
     * 用循环迭代次数定义间隔时间，
     * 在 UNCHOKING_FREQUENCY循环时，循环n次执行一次
     */
    private static final int OPTIMISTIC_UNCHOKE_ITERATIONS = 3;
    private static final int RATE_COMPUTATION_ITERATIONS = 2;
    private static final int MAX_DOWNLOADERS_UNCHOKE = 4;

    public enum ClientState {
        WAITING, VALIDATING, SHARING, SEEDING, ERROR, DONE;
    };

    private static final String BITTORRENT_ID_PREFIX = "-BT0000-";

    private ClientState state;
    private InetAddress localAddress;
    private Torrentfile torrent;
    private Peer my_peer;
    private Thread thread;
    private boolean stop;
    private long seed;
    private Random random;

    private ManageTracker manageTracker;
    private Connection connection;
    private ConcurrentMap<String, SharingPeer> peers;
    private ConcurrentMap<String, SharingPeer> connected;

    public Torrent(Torrentfile torrent) throws IOException {
        this.torrent = torrent;
        localAddress = InetAddress.getLocalHost();
        state = ClientState.WAITING;

        // 初始化bt客户端的id,即自己的peer_id
        String id = Torrent.BITTORRENT_ID_PREFIX + UUID.randomUUID().toString().split("-")[4];

        connection = new Connection(localAddress, id, torrent);
        connection.register(this);

        my_peer = new Peer(connection.getAddress(), connection.getPort(), id);

        manageTracker = new ManageTracker(torrent, my_peer);
        manageTracker.register(this);

        logger.info(torrent.getName() + " start...\n" +
                "listening at " + my_peer.getIp() + ":" + my_peer.getPort());

        peers = new ConcurrentHashMap<String, SharingPeer>();
        connected = new ConcurrentHashMap<String, SharingPeer>();
        random = new Random(System.currentTimeMillis());

    }

    public void setMaxDownloadRate(double rate) {
        this.torrent.setMaxDownloadRate(rate);
    }

    public void setMaxUploadRate(double rate) {
        this.torrent.setMaxUploadRate(rate);
    }

    public Peer getMy_peer() {
        return my_peer;
    }

    public Torrentfile getTorrent() {
        return torrent;
    }

    public Set<SharingPeer> getPeers() {
        return new HashSet<SharingPeer>(this.peers.values());
    }

    private synchronized void setState(ClientState state) {
        if (this.state != state) {
            this.setChanged();
        }
        this.state = state;
        this.notifyObservers(this.state);
    }

    public ClientState getState() {
        return this.state;
    }

    /**
     * 下载并分享当前torrent
     * @param seed
     * seed控制做种时间
     * -1表示不限时持续做种，0表示只做0s即不做种
     */
    public synchronized void download(int seed) {
        this.seed = seed;
        this.stop = false;

        if (this.thread == null || !this.thread.isAlive()) {
            this.thread = new Thread(this);
            this.thread.setName("bt-client(" + this.my_peer.getPeer_id() + ")");
            this.thread.start();
        }
    }

    /**
     * 是否在做种
     * @return boolean
     */
    private boolean isSeed() {
        return torrent.isComplete();
    }

    /**
     * 停止下载
     */
    public void stop() {
        this.stop(true);
    }

    /**
     * 停止下载
     * @param wait 是否等待线程任务完成
     */
    public void stop(boolean wait) {
        this.stop = true;

        if (this.thread != null && this.thread.isAlive()) {
            this.thread.interrupt();
            if (wait) {
                this.waitForCompletion();
            }
        }

        this.thread = null;
    }

    /**
     * 等待线程任务完成，包括下载（根据设置可能也包括做种）
     */
    public void waitForCompletion() {
        if (this.thread != null && this.thread.isAlive()) {
            try {
                this.thread.join();
            } catch (InterruptedException e) {
                logger.severe(e.getMessage());
            }
        }
    }

    /**
     * 开始做种
     */
    private synchronized void seed() {
        // Silently ignore if we're already seeding.
        if (ClientState.SEEDING.equals(this.getState())) {
            return;
        }

        logger.info("Download of " + this.torrent.getPieceCount() +" pieces completed.");

        this.setState(ClientState.SEEDING);
        if (this.seed < 0) {
            logger.info("Seeding indefinetely...");
            return;
        }

        // 做种持续seed秒，0则为不做种
        logger.info("Seeding for " + this.seed +" seconds...");
        Timer timer = new Timer();
        timer.schedule(new ClientShutdown(this, timer), this.seed * 1000);
    }

    private void finish() {
        this.torrent.close();

        // 更改状态
        if (this.torrent.isFinished()) {
            this.setState(ClientState.DONE);
        } else {
            this.setState(ClientState.ERROR);
        }

        logger.info("BitTorrent client signing off.");
    }

    public synchronized void info() {
        float dl = 0;
        float ul = 0;
        for (SharingPeer peer : this.connected.values()) {
            dl += peer.getDownloadRate().get();
            ul += peer.getUploadRate().get();
        }
        logger.info(this.getState().name() + "\n" +
                "dl: " + String.format("%.2f", this.torrent.getCompletion()) +
                "ul: " + String.format("%.2f", dl / 1024.0));
        for (SharingPeer peer : this.connected.values()) {
            Piece piece = peer.getRequestedPiece();
            String pieceString = piece != null ? "(downloading " + piece + ")" : "";
            logger.info("  | " + peer.getAddress() + " " + pieceString);
        }
    }

    private synchronized void resetPeerRates() {
        for (SharingPeer peer : this.connected.values()) {
            peer.getDownloadRate().reset();
            peer.getUploadRate().reset();
        }
    }

    /**
     * 主循环
     * 主要负责开启TrackerManger以及Connection线程
     * 这两个线程分别与tracker和peer建立联系，并持续监听
     * 直到下载完成或强制停止
     *
     * 每隔一段时间重新选择peers阻塞
     */
    @Override
    public void run() {

        // 这里主要工作就是init torrent，初始化了piece[]
        try {
            this.setState(ClientState.VALIDATING);
            this.torrent.init();
        } catch (IOException ioe) {
            logger.severe("Error while initializing torrent data: " + ioe.getMessage());
        } catch (InterruptedException ie) {
            logger.severe("Client was interrupted during initialization. " + "Aborting right away.");
        } finally {
            if (!this.torrent.isInitialized()) {
                try {
                    this.connection.close();
                } catch (IOException ioe) {
                    logger.severe("Error while releasing bound channel: " + ioe.getMessage());
                }

                this.setState(ClientState.ERROR);
                this.torrent.close();
                return;
            }
        }

        // 检测任务状态
        if (this.torrent.isComplete()) {
            this.seed();
        } else {
            this.setState(ClientState.SHARING);
        }

        // 检测提前停止
        if (this.stop) {
            logger.info("Download is complete and no seeding was requested.");
            this.finish();
            return;
        }

        this.manageTracker.start();
        this.connection.start();

        int optimisticIterations = 0;
        int rateComputationIterations = 0;

        while (!this.stop) {
            optimisticIterations = (optimisticIterations == 0 ? Torrent.OPTIMISTIC_UNCHOKE_ITERATIONS
                    : optimisticIterations - 1);

            rateComputationIterations = (rateComputationIterations == 0 ? Torrent.RATE_COMPUTATION_ITERATIONS
                    : rateComputationIterations - 1);

            try {
                this.unchokePeers(optimisticIterations == 0);
                this.info();
                if (rateComputationIterations == 0) {
                    this.resetPeerRates();
                }
            } catch (Exception e) {
                logger.severe("An exception occurred during the BitTorrent "
                        + "main loop execution:\n" + e.getMessage());
            }

            try {
                Thread.sleep(Torrent.UNCHOKING_FREQUENCY * 1000);
            } catch (InterruptedException ie) {
                logger.severe("BitTorrent main loop interrupted.");
            }
        }

        logger.info("Stopping BitTorrent client connection service " + "and announce threads...");

        this.connection.stop();
        try {
            this.connection.close();
        } catch (IOException ioe) {
            logger.severe("Error while releasing bound channel: " + ioe.getMessage());
        }

        this.manageTracker.stop();

        logger.info("Closing all remaining peer connections...");
        for (SharingPeer peer : this.connected.values()) {
            peer.unbind(true);
        }

        this.finish();

    }

    @Override
    public boolean connect() throws IOException {
        return false;
    }

    @Override
    public NetFile getContentInfo() throws IOException {
        return null;
    }

    @Override
    public boolean checkMutiThread() {
        return true;
    }

    @Override
    public InputStream getDownloadBlock(long start, long end) throws IOException {
        return null;
    }

    @Override
    public InputStream getAll() throws IOException {
        return null;
    }

    @Override
    public int getType() {
        return Protocols.BITTORRENT;
    }

    @Override
    public String getSource() {
        return null;
    }

    /**
     * 搜索某个peer是否为新发现的
     * 是已存在的则返回原来的peer
     * 是不存在的则新建peer并返回
     * @param searched 被搜索的peer
     * @return peer
     */
    private SharingPeer searchPeer(Peer searched) {
        SharingPeer peer;

        synchronized (peers) {
            logger.info("searching peer: " + searched.getAddress() + " in our list...");

            // 按peer id 检索
            if(searched.hasPeerId()) {
                peer = peers.get(searched.getPeer_id());
                if(peer != null) {
                    logger.info("find existed peer by peer id.");
                    peers.put(peer.getHost_id(), peer);
                    peers.put(searched.getHost_id(), peer);
                    return peer;
                }
            }

            // 按ip:port检索
            peer = peers.get(searched.getHost_id());
            if(peer != null) {
                logger.info("find existed peer by host id.");
                if(searched.hasPeerId()) {
                    peer.setPeer_id(searched.getPeer_id());
                    peers.put(peer.getPeer_id(), peer);
                    return peer;
                }
            }

            peer = new SharingPeer(searched.getIp(), searched.getPort(), searched.getPeer_id(), torrent);
            logger.info("Create new peer: " + peer.getAddress());

            peers.put(peer.getHost_id(), peer);
            if(peer.hasPeerId()) {
                peers.put(peer.getPeer_id(), peer);
            }
            return peer;
        }
    }

    private Comparator<SharingPeer> getPeerRateComparator() {
        if (ClientState.SHARING.equals(this.state)) {
            return new SharingPeer.DLRateComparator();
        } else if (ClientState.SEEDING.equals(this.state)) {
            return new SharingPeer.ULRateComparator();
        } else {
            throw new IllegalStateException(
                    "Client is neither sharing nor " + "seeding, we shouldn't be comparing peers at this point.");
        }
    }

    private synchronized void unchokePeers(boolean optimistic) {
        TreeSet<SharingPeer> bound = new TreeSet<SharingPeer>(this.getPeerRateComparator());
        bound.addAll(this.connected.values());

        if (bound.size() == 0) {
            logger.info("No connected peers, skipping unchoking.");
            return;
        } else {
            logger.info("Running unchokePeers() on " + bound.size() + " connected peers.");
        }

        int downloaders = 0;
        Set<SharingPeer> choked = new HashSet<SharingPeer>();

        // 降序以便找到速率大的
        for (SharingPeer peer : bound.descendingSet()) {
            if (downloaders < Torrent.MAX_DOWNLOADERS_UNCHOKE) {
                // Unchoke up to MAX_DOWNLOADERS_UNCHOKE interested peers
                if (peer.isChoking()) {
                    if (peer.isInterested()) {
                        downloaders++;
                    }

                    peer.unchoke();
                }
            } else {
                // Choke 其他所有
                choked.add(peer);
            }
        }

        // Actually choke all chosen peers (if any), except the eventual
        // optimistic unchoke.
        if (choked.size() > 0) {
            SharingPeer randomPeer = choked.toArray(new SharingPeer[0])[this.random.nextInt(choked.size())];

            for (SharingPeer peer : choked) {
                if (optimistic && peer == randomPeer) {
                    logger.info("Optimistic unchoke of " + peer.getAddress());
                    peer.unchoke();
                    continue;
                }

                peer.choke();
            }
        }
    }



    //***************************** ConnectionListener ******************************/

    /**
     * 新peer连接时触发
     * @param channel new peer SocketChannel
     * @param peerId new peer id
     */
    @Override
    public void handleNewPeerConnection(SocketChannel channel, byte[] peerId) {
        Peer peer = new Peer(channel.socket().getInetAddress().getHostAddress(),
                channel.socket().getPort(),
                peerId != null ? new String(peerId) : null);

        logger.info("handle new peer connection: " + peer.getAddress());
        SharingPeer new_peer = searchPeer(peer);

        try {
            synchronized (new_peer) {
                if(new_peer.isConnected()) {
                    logger.info("peer: " + new_peer.getAddress() + "Already connected.");
                    channel.close();
                    return;
                }
                new_peer.register(this);
                new_peer.bind(channel);
            }

            connected.put(new_peer.getPeer_id(), new_peer);
            new_peer.register(torrent);
            logger.info("new peer connection with " + new_peer.getAddress() +
                    "(" + connected.size() + "/" + peers.size() + ")");
        } catch(Exception e) {
            connected.remove(new_peer.getPeer_id());
            logger.severe("cannot deal with new connection: " +
                            new_peer.getPeer_id() + "\n" + e.getMessage());
        }

    }

    /**
     * 主动连接peer失败时触发
     * @param peer 连接失败的peer
     * @param cause 失败原因
     */
    @Override
    public void handleFailedConnection(SharingPeer peer, Throwable cause) {
        logger.severe("Could not connect to peer: " + peer.getAddress() +
                "\n" + "cause: " + cause.getMessage());
        peers.remove(peer.getHost_id());

        if (peer.hasPeerId()) {
            this.peers.remove(peer.getPeer_id());
        }
    }

    //***************************** TrackerListener ******************************/

    /**
     * tracker有响应时触发
     * 设置连接tracker的interval
     * @param interval 设置interval
     */

    @Override
    public void handleTrackerResponse(int interval) {
        this.manageTracker.setInterval(interval);
    }

    /**
     * tracker返回peer list时触发
     * 检查是否有新的peer
     * 有的话主动连接他们
     * @param peers peer list
     */
    @Override
    public void handleDiscoveredPeers(List<Peer> peers) {
        if(peers == null || peers.isEmpty()) {
            return;
        }
        logger.info(peers.size() +  " peers return by tracker");

        if(!this.connection.isAlive()) {
            logger.severe("connect is not available");
            return;
        }

        for(Peer peer : peers) {
            SharingPeer match = searchPeer(peer);
            if(this.isSeed()) {
                continue;
            }
            synchronized (match) {
                if (!match.isConnected()) {
                    connection.connect(match);
                }
            }
        }
    }

    //******************************** PeerListener ******************************/

    @Override
    public void handlePeerChoked(SharingPeer peer) {}

    @Override
    public void handlePeerReady(SharingPeer peer) {}

    @Override
    public void handlePieceAvailability(SharingPeer peer, Piece piece) {}

    @Override
    public void handleBitfieldAvailability(SharingPeer peer, BitSet availablePieces) {}

    @Override
    public void handlePieceSent(SharingPeer peer, Piece piece) {}

    /**
     * 当一个piece下载下来之后
     * 我们需要告诉所有peer我们拥有了这个piece
     *
     * 另外，如果全部下载完成，我们应该开始做种
     * @param peer
     * @param piece
     * @throws IOException
     */
    @Override
    public void handlePieceCompleted(SharingPeer peer, Piece piece) throws IOException {
        synchronized (this.torrent) {
            if (piece.isValid()) {
                // Make sure the piece is marked as completed in the torrent
                // Note: this is required because the order the
                // PeerActivityListeners are called is not defined, and we
                // might be called before the torrent's piece completion
                // handler is.
                this.torrent.markCompleted(piece);
                logger.info("Completed download of piece#" + piece.getIndex());

                // Send a HAVE message to all connected peers
                PeerMessage have = PeerMessage.HaveMessage.craft(piece.getIndex());
                for (SharingPeer remote : this.connected.values()) {
                    remote.send(have);
                }

                // Force notify after each piece is completed to propagate
                // download
                // completion information (or new seeding state)
                this.setChanged();
                this.notifyObservers(this.state);
            } else {
                logger.severe("Downloaded piece#" + piece.getIndex() + " but not valid ;-(");
            }

            if (this.torrent.isComplete()) {
                logger.info("Last piece validated and completed, finishing download...");

                // Cancel all remaining outstanding requests
                for (SharingPeer remote : this.connected.values()) {
                    if (remote.isDownloading()) {
                        int requests = remote.cancelPendingRequests().size();
                        logger.info("Cancelled remaining pending requests on :" + remote.getAddress());
                    }
                }

                this.torrent.finish();

                try {
                    this.manageTracker.getCurrentTracker()
                            .sendMessage(TrackerMessage.RequestEvent.COMPLETED, true);
                } catch (TrackerException e) {
                    logger.severe("Error announcing completion event to " + "tracker: " + e.getMessage());
                }

                logger.info("Download is complete and finalized.");
                this.seed();
            }
        }
    }

    @Override
    public void handlePeerDisconnected(SharingPeer peer) {
        if (this.connected.remove(peer.hasPeerId() ? peer.getPeer_id() : peer.getHost_id()) != null) {
            logger.info("Peer "+ peer.getAddress() +" disconnected");
        }

        peer.reset();
    }

    @Override
    public void handleIOException(SharingPeer peer, IOException ioe) {
        logger.severe("I/O error while exchanging data with " + peer.getAddress() +
                        ", closing connection with it！\n" +ioe.getMessage());
        peer.unbind(true);
    }

    /**
     * 用来定时停止做种的计时器
     */
    public static class ClientShutdown extends TimerTask {

        private final Torrent client;
        private final Timer timer;

        public ClientShutdown(Torrent client, Timer timer) {
            this.client = client;
            this.timer = timer;
        }

        @Override
        public void run() {
            this.client.stop();
            if (this.timer != null) {
                this.timer.cancel();
            }
        }
    }

}
