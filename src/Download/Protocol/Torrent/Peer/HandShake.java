package Download.Protocol.Torrent.Peer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;

public class HandShake {

    public static final String BITTORRENT_PROTOCOL_IDENTIFIER = "BitTorrent protocol";
    public static final int BASE_HANDSHAKE_LENGTH = 49;

    private final ByteBuffer data;
    private final ByteBuffer infoHash;
    private final ByteBuffer peerId;

    private HandShake(ByteBuffer data, ByteBuffer infoHash,
                      ByteBuffer peerId) {
        this.data = data;
        this.data.rewind();

        this.infoHash = infoHash;
        this.peerId = peerId;
    }

    public ByteBuffer getData() {
        return this.data;
    }

    public byte[] getInfoHash() {
        return this.infoHash.array();
    }

    public byte[] getPeerId() {
        return this.peerId.array();
    }

    public static HandShake parse(ByteBuffer buffer)
            throws ParseException {
        int pstrlen = Byte.valueOf(buffer.get()).intValue();
        if (pstrlen < 0 ||
                buffer.remaining() != BASE_HANDSHAKE_LENGTH + pstrlen - 1) {
            throw new ParseException("Incorrect handshake message length " +
                    "(pstrlen=" + pstrlen + ") !", 0);
        }

        // 检查 "BitTorrent protocol"
        byte[] pstr = new byte[pstrlen];
        buffer.get(pstr);

        if (!HandShake.BITTORRENT_PROTOCOL_IDENTIFIER.equals(
                new String(pstr, StandardCharsets.ISO_8859_1))) {
            throw new ParseException("Invalid protocol identifier!", 1);
        }

        // Ignore reserved bytes
        byte[] reserved = new byte[8];
        buffer.get(reserved);

        byte[] infoHash = new byte[20];
        buffer.get(infoHash);
        byte[] peerId = new byte[20];
        buffer.get(peerId);
        return new HandShake(buffer, ByteBuffer.wrap(infoHash),
                ByteBuffer.wrap(peerId));
    }

    public static HandShake craft(byte[] torrentInfoHash,
                                  byte[] clientPeerId) {

        ByteBuffer buffer = ByteBuffer.allocate(
                HandShake.BASE_HANDSHAKE_LENGTH +
                        HandShake.BITTORRENT_PROTOCOL_IDENTIFIER.length());

        byte[] reserved = new byte[8];
        ByteBuffer infoHash = ByteBuffer.wrap(torrentInfoHash);
        ByteBuffer peerId = ByteBuffer.wrap(clientPeerId);

        buffer.put((byte) HandShake
                .BITTORRENT_PROTOCOL_IDENTIFIER.length());
        buffer.put(HandShake
                .BITTORRENT_PROTOCOL_IDENTIFIER.getBytes(StandardCharsets.ISO_8859_1));
        buffer.put(reserved);
        buffer.put(infoHash);
        buffer.put(peerId);

        return new HandShake(buffer, infoHash, peerId);
    }

}
