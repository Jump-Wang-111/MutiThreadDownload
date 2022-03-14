package Download.Protocol.Torrent.Listener;

import Download.Protocol.Torrent.Peer.Peer;

import java.util.EventListener;
import java.util.List;

public interface TrackerListener extends EventListener {

    /**
     * 对tracker返回的响应的处理
     */
    void handleTrackerResponse(int interval);

    /**
     * 对新发现的peers的处理
     */
    void handleDiscoveredPeers(List<Peer> peers);
}
