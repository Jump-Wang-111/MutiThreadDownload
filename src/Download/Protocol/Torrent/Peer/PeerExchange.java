package Download.Protocol.Torrent.Peer;

import Download.Protocol.Torrent.Listener.MessageListener;
import Download.Protocol.Torrent.Torrentfile;
import org.apache.commons.io.IOUtils;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.text.ParseException;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class PeerExchange {
    Logger logger = Logger.getGlobal();

    private static final int KEEP_ALIVE_IDLE_MINUTES = 2;
    private static final PeerMessage STOP = PeerMessage.KeepAliveMessage.craft();

    private final SharingPeer peer;
    private final Torrentfile torrent;
    private final SocketChannel channel;

    private final Set<MessageListener> listeners;

    private final IncomingThread in;
    private final OutgoingThread out;

    private final BlockingQueue<PeerMessage> sendQueue;
    private volatile boolean stop;

    public PeerExchange(SharingPeer peer, Torrentfile torrent,
                        SocketChannel channel) {
        this.peer = peer;
        this.torrent = torrent;
        this.channel = channel;

        this.listeners = new HashSet<MessageListener>();
        this.sendQueue = new LinkedBlockingQueue<PeerMessage>();

        if (!this.peer.hasPeerId()) {
            throw new IllegalStateException("Peer does not have a " +
                    "peer ID. Was the handshake made properly?");
        }

        this.in = new IncomingThread();
        this.in.setName("bt-peer(" +
                this.peer.getPeer_id() + ")-recv");

        this.out = new OutgoingThread();
        this.out.setName("bt-peer(" +
                this.peer.getPeer_id() + ")-send");
        this.out.setDaemon(true);

        this.stop = false;

        logger.info("Started peer exchange with peer: " + this.peer.getAddress());

        // 获得当前pieces状态
        // 如果我们有位图的话，我们先将自己的位图发送给peer
        BitSet pieces = this.torrent.getCompletedPieces();
        if (pieces.cardinality() > 0) {
            this.send(PeerMessage.BitfieldMessage.craft(pieces, torrent.getPieceCount()));
        }
    }

    /**
     * 注册监听，以通知监听者
     * @param listener MessageListener
     */
    public void register(MessageListener listener) {
        this.listeners.add(listener);
    }

    /**
     * 当前通信是否活跃
     * @return boolean
     */
    public boolean isConnected() {
        return this.channel.isConnected();
    }

    public void send(PeerMessage message) {
        try {
            this.sendQueue.put(message);
        } catch (InterruptedException ie) {
            logger.info(ie.getMessage());
        }
    }

    public void start() {
        this.in.start();
        this.out.start();
    }

    /**
     * 停止与peer的交互
     */
    public void stop() {
        this.stop = true;

        try {
            // 向peer发送停止message
            this.sendQueue.put(STOP);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (this.channel.isConnected()) {
            IOUtils.closeQuietly(this.channel);
        }

        logger.info("Peer exchange closed: " + this.peer.getAddress());
    }

    /**
     * 从socket输入流中读取信息
     * 全部接收后触发peer的handleMessage()方法
     * 对收到的Message进行处理
     */
    private class IncomingThread extends RateLimitThread {

        private long read(Selector selector, ByteBuffer buffer) throws IOException {
            if (selector.select() == 0 || !buffer.hasRemaining()) {
                return 0;
            }

            long size = 0;
            Iterator it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = (SelectionKey) it.next();
                if (key.isValid() && key.isReadable()) {
                    int read = ((SocketChannel) key.channel()).read(buffer);
                    if (read < 0) {
                        throw new IOException("Unexpected end-of-stream while reading");
                    }
                    size += read;
                }
                it.remove();
            }

            return size;
        }

        private void handleIOE(IOException ioe) {
            logger.info("Could not read message from peer: " + peer.getAddress());
            peer.unbind(true);
        }

        @Override
        public void run() {
            ByteBuffer buffer = ByteBuffer.allocateDirect(1*1024*1024);
            Selector selector = null;

            try {
                selector = Selector.open();
                channel.register(selector, SelectionKey.OP_READ);

                while (!stop) {
                    buffer.rewind();
                    buffer.limit(PeerMessage.MESSAGE_LENGTH_FIELD_SIZE);

                    // Keep reading bytes until the length field has been read
                    // entirely.
                    while (!stop && buffer.hasRemaining()) {
                        this.read(selector, buffer);
                    }

                    // Reset the buffer limit to the expected message size.
                    int pstrlen = buffer.getInt(0);
                    buffer.limit(PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + pstrlen);

                    long size = 0;
                    while (!stop && buffer.hasRemaining()) {
                        size += this.read(selector, buffer);
                    }

                    buffer.rewind();

                    if (stop) {
                        // The buffer may contain the type from the last message
                        // if we were stopped before reading the payload and cause
                        // BufferUnderflowException in parsing.
                        break;
                    }

                    try {
                        PeerMessage message = PeerMessage.parse(buffer, torrent);
                        logger.info("Received message from peer:" + peer.getAddress());

                        // Wait if needed to reach configured download rate.
                        this.rateLimit(
                                PeerExchange.this.torrent.getMaxDownloadRate(),
                                size, message);

                        for (MessageListener listener : listeners)
                            listener.handleMessage(message);
                    } catch (ParseException pe) {
                        logger.severe( pe.getMessage());
                    }
                }
            } catch (IOException ioe) {
                this.handleIOE(ioe);
            } finally {
                try {
                    if (selector != null) {
                        selector.close();
                    }
                } catch (IOException ioe) {
                    this.handleIOE(ioe);
                }
            }
        }
    }

    private abstract class RateLimitThread extends Thread {

        protected final Rate rate = new Rate();
        protected long sleep = 1000;

        protected void rateLimit(double maxRate, long messageSize, PeerMessage message) {
            if (message.getType() != PeerMessage.Type.PIECE || maxRate <= 0) {
                return;
            }

            try {
                this.rate.add(messageSize);

                if (rate.get() > (maxRate * 1024)) {
                    Thread.sleep(this.sleep);
                    this.sleep += 50;
                } else {
                    this.sleep = this.sleep > 50
                            ? this.sleep - 50
                            : 0;
                }
            } catch (InterruptedException e) {
                // Not critical, eat it.
            }
        }
    }

    /**
     * 等待消息出现在发送队列
     * 并进行处理
     */
    private class OutgoingThread extends RateLimitThread {

        @Override
        public void run() {
            try {
                // Loop until told to stop. When stop was requested, loop until the queue is served.
                while (!stop || (stop && sendQueue.size() > 0)) {
                    try {
                        // Wait for two minutes for a message to send
                        PeerMessage message = sendQueue.poll(
                                PeerExchange.KEEP_ALIVE_IDLE_MINUTES,
                                TimeUnit.MINUTES);

                        if (message == STOP) {
                            return;
                        }

                        if (message == null) {
                            message = PeerMessage.KeepAliveMessage.craft();
                        }

                        logger.info("Sending message");

                        ByteBuffer data = message.getData();
                        long size = 0;
                        while (!stop && data.hasRemaining()) {
                            int written = channel.write(data);
                            size += written;
                            if (written < 0) {
                                throw new EOFException(
                                        "Reached end of stream while writing");
                            }
                        }

                        // Wait if needed to reach configured upload rate.
                        this.rateLimit(PeerExchange.this.torrent.getMaxUploadRate(),
                                size, message);
                    } catch (InterruptedException ie) {
                        // Ignore and potentially terminate
                    }
                }
            } catch (IOException ioe) {
                logger.info("Could not send message to peer:" + peer.getAddress());
                peer.unbind(true);
            }
        }
    }
}
