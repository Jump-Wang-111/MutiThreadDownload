package Download.Protocol.Torrent.Listener;

import Download.Protocol.Torrent.Peer.PeerMessage;

import java.util.EventListener;

/**
 * 对于想收到peer消息的对象，使用该监听
 */
public interface MessageListener extends EventListener {
    void handleMessage(PeerMessage msg);
}
