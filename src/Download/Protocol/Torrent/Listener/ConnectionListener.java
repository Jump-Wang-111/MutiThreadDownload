package Download.Protocol.Torrent.Listener;

import Download.Protocol.Torrent.Peer.SharingPeer;

import java.nio.channels.SocketChannel;
import java.util.EventListener;

public interface ConnectionListener extends EventListener {
    /**
     * 处理新的peer连接
     */
    void handleNewPeerConnection(SocketChannel channel, byte[] peerId);

    /**
     * 处理失败的peer连接
     */
    void handleFailedConnection(SharingPeer peer, Throwable cause);

}
