package Download.Protocol.Torrent.Listener;

import Download.Protocol.Torrent.Peer.Piece;
import Download.Protocol.Torrent.Peer.SharingPeer;

import java.io.IOException;
import java.util.BitSet;
import java.util.EventListener;

/**
 * 当Peer产生变化或者影响时，触发对其他对象的动作
 */
public interface PeerListener extends EventListener {

    void handlePeerChoked(SharingPeer peer);

    void handlePeerReady(SharingPeer peer);

    void handlePieceAvailability(SharingPeer peer, Piece piece);

    /**当更新可用的piece时，触发此方法
     * @param peer
     * @param availablePieces
     */
    void handleBitfieldAvailability(SharingPeer peer,
                                           BitSet availablePieces);

    /** 上传成功的处理
     * @param peer
     * @param piece
     */
    void handlePieceSent(SharingPeer peer, Piece piece);

    /** 下载完成的处理
     * @param peer
     * @param piece
     * @throws IOException
     */
    void handlePieceCompleted(SharingPeer peer, Piece piece)
            throws IOException;

    void handlePeerDisconnected(SharingPeer peer);

    void handleIOException(SharingPeer peer, IOException ioe);
}
