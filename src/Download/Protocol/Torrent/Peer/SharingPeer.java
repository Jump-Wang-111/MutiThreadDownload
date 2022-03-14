package Download.Protocol.Torrent.Peer;

import Download.Protocol.Torrent.Listener.MessageListener;
import Download.Protocol.Torrent.Listener.PeerListener;
import Download.Protocol.Torrent.Torrentfile;

import java.io.IOException;
import java.io.Serializable;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

public class SharingPeer extends Peer implements MessageListener {

    Logger logger = Logger.getGlobal();

    private static final int MAX_PIPELINED_REQUESTS = 5;

    // 我们对peer 阻塞/感兴趣
    private boolean choking;
    private boolean interesting;

    // 我们被peer 阻塞/感兴趣
    private boolean choked;
    private boolean interested;

    private final BitSet availablePieces;
    private final Torrentfile torrent;
    private Piece piece;
    private int lastRequestedOffset;

    private volatile boolean downloading;

    private BlockingQueue<PeerMessage.RequestMessage> requests;
    private PeerExchange exchange;
    private Rate download;
    private Rate upload;

    private final Set<PeerListener> listeners;

    private final Object requestsLock;
    private final Object exchangeLock;

    public SharingPeer(String ip, int port, String peer_id, Torrentfile torrent) {
        super(ip, port, peer_id);

        this.torrent = torrent;
        this.listeners = new HashSet<>();
        this.availablePieces = new BitSet();

        this.requestsLock = new Object();
        this.exchangeLock = new Object();

        this.reset();
        this.piece = null;
    }

    /**
     * 注册事件监听，以触发其他对象的动作
     * @param listener PeerListener
     */
    public void register(PeerListener listener) {
        listeners.add(listener);
    }

    /**
     * 在peer交换中，当前peer是否活跃
     */
    public boolean isConnected() {
        synchronized (this.exchangeLock) {
            return this.exchange != null && this.exchange.isConnected();
        }
    }

    public Rate getDownloadRate() {
        return this.download;
    }

    public Rate getUploadRate() {
        return this.upload;
    }

    /**
     * 重置peer的状态
     * 所有peer在初始时默认双向阻塞且双向不感兴趣
     */
    public synchronized void reset() {
        this.choking = true;
        this.interesting = false;
        this.choked = true;
        this.interested = false;

        this.exchange = null;

        this.requests = null;
        this.lastRequestedOffset = 0;
        this.downloading = false;
    }

    /**
     * chock这个peer
     * 不给他上传
     */
    public void choke() {
        if (!this.choking) {
            logger.info("Choking: " + this.getAddress());
            this.send(PeerMessage.ChokeMessage.craft());
            this.choking = true;
        }
    }

    /**
     * unchock这个peer
     * 给他上传
     */
    public void unchoke() {
        if (this.choking) {
            logger.info("Unchoking: " + this.getAddress());
            this.send(PeerMessage.UnchokeMessage.craft());
            this.choking = false;
        }
    }

    /**
     * 对这个peer感兴趣
     * 想从他那下载
     */
    public void interesting() {
        if (!this.interesting) {
            logger.info("we're interested in peer: " + this.getAddress());
            this.send(PeerMessage.InterestedMessage.craft());
            this.interesting = true;
        }
    }

    /**
     * 对这个peer不感兴趣
     * 不想从他那下载
     */
    public void notInteresting() {
        if (this.interesting) {
            logger.info("we're no longer interested in peer: " + this.getAddress());
            this.send(PeerMessage.NotInterestedMessage.craft());
            this.interesting = false;
        }
    }

    public boolean isChoking() {
        return this.choking;
    }

    public boolean isInteresting() {
        return this.interesting;
    }


    public boolean isChoked() {
        return this.choked;
    }

    public boolean isInterested() {
        return this.interested;
    }

    /**
     * 获取该peer可用的piece位图
     * @return BitSet availablePieces
     */
    public BitSet getAvailablePieces() {
        synchronized (this.availablePieces) {
            return (BitSet)this.availablePieces.clone();
        }
    }

    /**
     * 获取正在请求的piece
     * @return Piece
     */
    public Piece getRequestedPiece() {
        return this.piece;
    }

    /**
     * 判断peer是否是一个种子
     * 即它是否拥有全部的piece，拥有完整的文件
     * @return boolean
     */
    public synchronized boolean isSeed() {
        return this.torrent.getPieceCount() > 0 &&
                this.getAvailablePieces().cardinality() ==
                        this.torrent.getPieceCount();
    }

    /**
     * 将一个连接的socket绑定到这个peer
     * 新建peer exchange与当前peer关联
     * 并注册当前peer成为监听者
     * @param channel SocketChannel
     * @throws SocketException
     */
    public synchronized void bind(SocketChannel channel) throws SocketException {
        // 有可能这个peer之前有绑定过，要先解绑，从列表移除
        this.unbind(true);

        this.exchange = new PeerExchange(this, this.torrent, channel);
        this.exchange.register(this);
        this.exchange.start();

        this.download = new Rate();
        this.download.reset();

        this.upload = new Rate();
        this.upload.reset();
    }

    public void unbind(boolean force) {
        if (!force) {
            this.cancelPendingRequests();
            this.send(PeerMessage.NotInterestedMessage.craft());
        }

        synchronized (this.exchangeLock) {
            if (this.exchange != null) {
                this.exchange.stop();
                this.exchange = null;
            }
        }

        this.fireEvent_PeerDisconnected();
        this.piece = null;
    }

    /**
     * 给这个peer发送message
     * @param message PeerMessage
     */
    public void send(PeerMessage message) {
        if (this.isConnected()) {
            this.exchange.send(message);
        } else {
            logger.severe("Attempting to send a message to non-connected peer: " + getAddress());
        }
    }

    /**
     * 取消所有在等待的piece请求
     * @return 取消这些请求而发送的message集合
     */
    public Set<PeerMessage.RequestMessage> cancelPendingRequests() {
        synchronized (this.requestsLock) {
            Set<PeerMessage.RequestMessage> requests =
                    new HashSet<PeerMessage.RequestMessage>();

            if (this.requests != null) {
                for (PeerMessage.RequestMessage request : this.requests) {
                    this.send(PeerMessage.CancelMessage.craft(request.getPiece(),
                            request.getOffset(), request.getLength()));
                    requests.add(request);
                }
            }

            this.requests = null;
            this.downloading = false;
            return requests;
        }
    }

    /**
     * 下载piece
     * 实际是对每个piece分成n个block进行请求
     * 这里只记录了piece并初始化了请求序列
     * 之后在 requestNextBlocks 中
     * 会往待下载队列中一个接一个的add block
     * @param piece 要下载的piece
     * @throws IllegalStateException
     */
    public synchronized void downloadPiece(Piece piece)
            throws IllegalStateException {
        if (this.isDownloading()) {
            IllegalStateException up = new IllegalStateException(
                    "Trying to download a piece while previous " +
                            "download not completed!");
            logger.severe("What's going on?\n" + up.getMessage());
            // 阿巴阿巴
            throw up; // ah ah.
        }

        this.requests = new LinkedBlockingQueue<PeerMessage.RequestMessage>(
                SharingPeer.MAX_PIPELINED_REQUESTS);
        this.piece = piece;
        this.lastRequestedOffset = 0;
        this.requestNextBlocks();
    }

    /**
     * 当前peer是否正在下载
     * @return boolean
     */
    public boolean isDownloading() {
        return this.downloading;
    }

    /**
     * 对piece分成的block分次发送请求
     * 并将请求添加到待下载队列中
     */
    private void requestNextBlocks() {
        synchronized (this.requestsLock) {
            if (this.requests == null || this.piece == null) {
                return;
            }

            while (this.requests.remainingCapacity() > 0 &&
                    this.lastRequestedOffset < this.piece.size()) {
                PeerMessage.RequestMessage request = PeerMessage.RequestMessage
                        .craft(
                                this.piece.getIndex(),
                                this.lastRequestedOffset,
                                Math.min(
                                        (int)(this.piece.size() -
                                                this.lastRequestedOffset),
                                        PeerMessage.RequestMessage.DEFAULT_REQUEST_SIZE));
                this.requests.add(request);
                this.send(request);
                this.lastRequestedOffset += request.getLength();
            }

            this.downloading = this.requests.size() > 0;
        }
    }

    /**
     * 当我们收到piece时
     * 移除待下载队列中的对应request
     * @param message
     */
    private void removeBlockRequest(PeerMessage.PieceMessage message) {
        synchronized (this.requestsLock) {
            if (this.requests == null) {
                return;
            }

            for (PeerMessage.RequestMessage request : this.requests) {
                if (request.getPiece() == message.getPiece() &&
                        request.getOffset() == message.getOffset()) {
                    this.requests.remove(request);
                    break;
                }
            }

            this.downloading = this.requests.size() > 0;
        }
    }

    /**
     * 处理新收到的message
     * @param msg PeerMessage
     */
    @Override
    public void handleMessage(PeerMessage msg) {
        switch (msg.getType()) {
            case KEEP_ALIVE:
                // 什么都不做，维持连接的方法在其他地方
                break;
            case CHOKE:
                this.choked = true;
                this.fireEvent_PeerChoked();
                this.cancelPendingRequests();
                break;
            case UNCHOKE:
                this.choked = false;
                logger.info("Peer: " + this.getAddress() + " is now accepting requests.");
                this.fireEvent_PeerReady();
                break;
            case INTERESTED:
                this.interested = true;
                break;
            case NOT_INTERESTED:
                this.interested = false;
                break;
            case HAVE:
                // 标记当前peer具有piece
                PeerMessage.HaveMessage have = (PeerMessage.HaveMessage)msg;
                Piece havePiece = this.torrent.getPiece(have.getPieceIndex());

                synchronized (this.availablePieces) {
                    this.availablePieces.set(havePiece.getIndex());
                    logger.info("Peer: " + this.getAddress() +
                            "now has" + havePiece + "[" +
                            this.availablePieces.cardinality() + "/" +
                            this.torrent.getPieceCount() + "]");
                }

                this.fireEvent_PieceAvailabity(havePiece);
                break;
            case BITFIELD:
                // Augment the hasPiece bit field from this BITFIELD message
                PeerMessage.BitfieldMessage bitfield =
                        (PeerMessage.BitfieldMessage)msg;

                synchronized (this.availablePieces) {
                    this.availablePieces.or(bitfield.getBitfield());
                }

                this.fireEvent_BitfieldAvailabity();
                break;
            case REQUEST:
                PeerMessage.RequestMessage request =
                        (PeerMessage.RequestMessage)msg;
                Piece rp = this.torrent.getPiece(request.getPiece());

                if (this.isChoking() || !rp.isValid()) {
                    logger.severe("Peer: " + this.getAddress()
                            + " violated protocol, " +
                            "terminating exchange.");
                    this.unbind(true);
                    break;
                }

                if (request.getLength() >
                        PeerMessage.RequestMessage.MAX_REQUEST_SIZE) {
                    logger.severe("Peer:" + this.getAddress() +
                            "requested a block too big, " +
                            "terminating exchange.");
                    this.unbind(true);
                    break;
                }

                // At this point we agree to send the requested piece block to
                // the remote peer, so let's queue a message with that block
                try {
                    ByteBuffer block = rp.read(request.getOffset(),
                            request.getLength());
                    this.send(PeerMessage.PieceMessage.craft(request.getPiece(),
                            request.getOffset(), block));
                    this.upload.add(block.capacity());

                    if (request.getOffset() + request.getLength() == rp.size()) {
                        this.fireEvent_PieceSent(rp);
                    }
                } catch (IOException ioe) {
                    this.fireEvent_IOException(new IOException(
                            "Error while sending piece block request!", ioe));
                }

                break;
            case PIECE:
                // Record the incoming piece block.

                // Should we keep track of the requested pieces and act when we
                // get a piece we didn't ask for, or should we just stay
                // greedy?
                PeerMessage.PieceMessage piece = (PeerMessage.PieceMessage)msg;
                Piece p = this.torrent.getPiece(piece.getPiece());

                // Remove the corresponding request from the request queue to
                // make room for next block requests.
                this.removeBlockRequest(piece);
                this.download.add(piece.getBlock().capacity());

                try {
                    synchronized (p) {
                        if (p.isValid()) {
                            this.piece = null;
                            this.cancelPendingRequests();
                            this.fireEvent_PeerReady();
                            logger.info("Discarding block for already completed " + p);
                            break;
                        }

                        p.record(piece.getBlock(), piece.getOffset());

                        // 已经完全下载
                        if (piece.getOffset() + piece.getBlock().capacity()
                                == p.size()) {
                            p.validate();
                            this.fireEvent_PieceCompleted(p);
                            this.piece = null;
                            this.fireEvent_PeerReady();
                        } else {
                            this.requestNextBlocks();
                        }
                    }
                } catch (IOException ioe) {
                    this.fireEvent_IOException(new IOException(
                            "Error while storing received piece block!", ioe));
                    break;
                }
                break;
            case CANCEL:
                // nothing
                break;
        }
    }

    /**
     * 触发Peer listener的方法
     * 处理peer断开连接事件
     */
    private void fireEvent_PeerDisconnected() {
        for (PeerListener listener : this.listeners) {
            listener.handlePeerDisconnected(this);
        }
    }

    /**
     * 触发Peer listener的方法
     * 处理piece下载完成事件
     */
    private void fireEvent_PieceCompleted(Piece piece) throws IOException {
        for (PeerListener listener : this.listeners) {
            listener.handlePieceCompleted(this, piece);
        }
    }

    /**
     * 触发Peer listener的方法
     * 处理piece上传事件
     */
    private void fireEvent_PieceSent(Piece piece) {
        for (PeerListener listener : this.listeners) {
            listener.handlePieceSent(this, piece);
        }
    }

    /**
     * 触发Peer listener的方法
     * 处理piece上传事件
     */
    private void fireEvent_PeerChoked() {
        for (PeerListener listener : this.listeners) {
            listener.handlePeerChoked(this);
        }
    }

    /**
     * 触发Peer listener的方法
     * 处理peer准备就绪事件
     */
    private void fireEvent_PeerReady() {
        for (PeerListener listener : this.listeners) {
            listener.handlePeerReady(this);
        }
    }

    /**
     * 触发Peer listener的方法
     * 处理piece可获取事件
     */
    private void fireEvent_PieceAvailabity(Piece piece) {
        for (PeerListener listener : this.listeners) {
            listener.handlePieceAvailability(this, piece);
        }
    }

    /**
     * 触发Peer listener的方法
     * 处理bit field可获取事件
     */
    private void fireEvent_BitfieldAvailabity() {
        for (PeerListener listener : this.listeners) {
            listener.handleBitfieldAvailability(this,
                    this.getAvailablePieces());
        }
    }

    private void fireEvent_IOException(IOException e) {
        for (PeerListener listener : this.listeners) {
            listener.handleIOException(this, e);
        }
    }

    /**
     * 上传速率的比较器
     */
    public static class ULRateComparator
            implements Comparator<SharingPeer>, Serializable {

        private static final long serialVersionUID = 38794949747717L;

        @Override
        public int compare(SharingPeer a, SharingPeer b) {
            return Rate.RATE_COMPARATOR.compare(a.getDownloadRate(), b.getDownloadRate());
        }
    }

    /**
     * 下载速率的比较器
     */
    public static class DLRateComparator
            implements Comparator<SharingPeer>, Serializable {

        private static final long serialVersionUID = 96307229964730L;

        @Override
        public int compare(SharingPeer a, SharingPeer b) {
            return Rate.RATE_COMPARATOR.compare(a.getDownloadRate(), b.getDownloadRate());
        }
    }
}
