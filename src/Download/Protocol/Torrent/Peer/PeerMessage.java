package Download.Protocol.Torrent.Peer;

import Download.Protocol.Torrent.Torrentfile;

import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.BitSet;

public abstract class PeerMessage {
    // 消息最大长度，不超过int的范围
    public static final int MESSAGE_LENGTH_FIELD_SIZE = 4;

    // 消息的类型
    public enum Type {
        KEEP_ALIVE(-1),
        CHOKE(0),
        UNCHOKE(1),
        INTERESTED(2),
        NOT_INTERESTED(3),
        HAVE(4),
        BITFIELD(5),
        REQUEST(6),
        PIECE(7),
        CANCEL(8);

        private byte id;
        Type(int id) {
            this.id = (byte)id;
        }

        public boolean equals(byte c) {
            return this.id == c;
        }

        public byte getTypeByte() {
            return this.id;
        }

        public static Type get(byte c) {
            for (Type t : Type.values()) {
                if (t.equals(c)) {
                    return t;
                }
            }
            return null;
        }
    };

    private final Type type;
    private final ByteBuffer data;

    private PeerMessage(Type type, ByteBuffer data) {
        this.type = type;
        this.data = data;
        this.data.rewind();
    }

    /**
     * 验证message和torrent的对应关系
     * 用来被overload，默认为true
     */
    public PeerMessage validate(Torrentfile torrent)
            throws MessageValidationException {
        return this;
    }

    public Type getType() {
        return this.type;
    }

    public ByteBuffer getData() {
        return this.data.duplicate();
    }

    public String toString() {
        return this.getType().name();
    }

    /**
     * 解析Message
     */
    public static PeerMessage parse(ByteBuffer buffer, Torrentfile torrent)
            throws ParseException {
        int length = buffer.getInt();
        if (length == 0) {
            return KeepAliveMessage.parse(buffer, torrent);
        } else if (length != buffer.remaining()) {
            throw new ParseException("Message size did not match announced " +
                    "size!", 0);
        }

        Type type = Type.get(buffer.get());
        if (type == null) {
            throw new ParseException("Unknown message ID!",
                    buffer.position()-1);
        }

        switch (type) {
            case CHOKE:
                return ChokeMessage.parse(buffer.slice(), torrent);
            case UNCHOKE:
                return UnchokeMessage.parse(buffer.slice(), torrent);
            case INTERESTED:
                return InterestedMessage.parse(buffer.slice(), torrent);
            case NOT_INTERESTED:
                return NotInterestedMessage.parse(buffer.slice(), torrent);
            case HAVE:
                return HaveMessage.parse(buffer.slice(), torrent);
            case BITFIELD:
                return BitfieldMessage.parse(buffer.slice(), torrent);
            case REQUEST:
                return RequestMessage.parse(buffer.slice(), torrent);
            case PIECE:
                return PieceMessage.parse(buffer.slice(), torrent);
            case CANCEL:
                return CancelMessage.parse(buffer.slice(), torrent);
            default:
                throw new IllegalStateException("Message type should have " +
                        "been properly defined by now.");
        }
    }

    public static class RequestMessage extends PeerMessage {

        private static final int BASE_SIZE = 13;

        /** Default block size is 2^14 bytes, or 16kB. */
        public static final int DEFAULT_REQUEST_SIZE = 16384;

        /** Max block request size is 2^17 bytes, or 131kB. */
        public static final int MAX_REQUEST_SIZE = 131072;

        private int piece;
        private int offset;
        private int length;

        private RequestMessage(ByteBuffer buffer, int piece,
                               int offset, int length) {
            super(Type.REQUEST, buffer);
            this.piece = piece;
            this.offset = offset;
            this.length = length;
        }

        public int getPiece() {
            return this.piece;
        }

        public int getOffset() {
            return this.offset;
        }

        public int getLength() {
            return this.length;
        }

        public RequestMessage validate(Torrentfile torrent)
                throws MessageValidationException {
            if (this.piece >= 0 && this.piece < torrent.getPieceCount() &&
                    this.offset + this.length <=
                            torrent.getPiece(this.piece).size()) {
                return this;
            }

            throw new MessageValidationException(this);
        }

        public static RequestMessage parse(ByteBuffer buffer,
                                           Torrentfile torrent) throws MessageValidationException {
            int piece = buffer.getInt();
            int offset = buffer.getInt();
            int length = buffer.getInt();
            return new RequestMessage(buffer, piece,
                    offset, length).validate(torrent);
        }

        public static RequestMessage craft(int piece, int offset, int length) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(
                    MESSAGE_LENGTH_FIELD_SIZE + RequestMessage.BASE_SIZE);
            buffer.putInt(RequestMessage.BASE_SIZE);
            buffer.put(PeerMessage.Type.REQUEST.getTypeByte());
            buffer.putInt(piece);
            buffer.putInt(offset);
            buffer.putInt(length);
            return new RequestMessage(buffer, piece, offset, length);
        }

        public String toString() {
            return super.toString() + " #" + this.getPiece() +
                    " (" + this.getLength() + "@" + this.getOffset() + ")";
        }
    }

    public static class ChokeMessage extends PeerMessage {

        private static final int BASE_SIZE = 1;

        private ChokeMessage(ByteBuffer buffer) {
            super(Type.CHOKE, buffer);
        }

        public static ChokeMessage parse(ByteBuffer buffer,
                                         Torrentfile torrent) throws MessageValidationException {
            return (ChokeMessage)new ChokeMessage(buffer)
                    .validate(torrent);
        }

        public static ChokeMessage craft() {
            ByteBuffer buffer = ByteBuffer.allocateDirect(
                    MESSAGE_LENGTH_FIELD_SIZE + ChokeMessage.BASE_SIZE);
            buffer.putInt(ChokeMessage.BASE_SIZE);
            buffer.put(PeerMessage.Type.CHOKE.getTypeByte());
            return new ChokeMessage(buffer);
        }
    }

    public static class UnchokeMessage extends PeerMessage {

        private static final int BASE_SIZE = 1;

        private UnchokeMessage(ByteBuffer buffer) {
            super(Type.UNCHOKE, buffer);
        }

        public static UnchokeMessage parse(ByteBuffer buffer,
                                           Torrentfile torrent) throws MessageValidationException {
            return (UnchokeMessage)new UnchokeMessage(buffer)
                    .validate(torrent);
        }

        public static UnchokeMessage craft() {
            ByteBuffer buffer = ByteBuffer.allocateDirect(
                    MESSAGE_LENGTH_FIELD_SIZE + UnchokeMessage.BASE_SIZE);
            buffer.putInt(UnchokeMessage.BASE_SIZE);
            buffer.put(PeerMessage.Type.UNCHOKE.getTypeByte());
            return new UnchokeMessage(buffer);
        }
    }

    public static class InterestedMessage extends PeerMessage {

        private static final int BASE_SIZE = 1;

        private InterestedMessage(ByteBuffer buffer) {
            super(Type.INTERESTED, buffer);
        }

        public static InterestedMessage parse(ByteBuffer buffer,
                                              Torrentfile torrent) throws MessageValidationException {
            return (InterestedMessage)new InterestedMessage(buffer)
                    .validate(torrent);
        }

        public static InterestedMessage craft() {
            ByteBuffer buffer = ByteBuffer.allocateDirect(
                    MESSAGE_LENGTH_FIELD_SIZE + InterestedMessage.BASE_SIZE);
            buffer.putInt(InterestedMessage.BASE_SIZE);
            buffer.put(PeerMessage.Type.INTERESTED.getTypeByte());
            return new InterestedMessage(buffer);
        }
    }

    public static class NotInterestedMessage extends PeerMessage {

        private static final int BASE_SIZE = 1;

        private NotInterestedMessage(ByteBuffer buffer) {
            super(Type.NOT_INTERESTED, buffer);
        }

        public static NotInterestedMessage parse(ByteBuffer buffer,
                                                 Torrentfile torrent) throws MessageValidationException {
            return (NotInterestedMessage)new NotInterestedMessage(buffer)
                    .validate(torrent);
        }

        public static NotInterestedMessage craft() {
            ByteBuffer buffer = ByteBuffer.allocateDirect(
                    MESSAGE_LENGTH_FIELD_SIZE + NotInterestedMessage.BASE_SIZE);
            buffer.putInt(NotInterestedMessage.BASE_SIZE);
            buffer.put(PeerMessage.Type.NOT_INTERESTED.getTypeByte());
            return new NotInterestedMessage(buffer);
        }
    }

    public static class CancelMessage extends PeerMessage {

        private static final int BASE_SIZE = 13;

        private int piece;
        private int offset;
        private int length;

        private CancelMessage(ByteBuffer buffer, int piece,
                              int offset, int length) {
            super(Type.CANCEL, buffer);
            this.piece = piece;
            this.offset = offset;
            this.length = length;
        }

        public int getPiece() {
            return this.piece;
        }

        public int getOffset() {
            return this.offset;
        }

        public int getLength() {
            return this.length;
        }

        @Override
        public CancelMessage validate(Torrentfile torrent)
                throws MessageValidationException {
            if (this.piece >= 0 && this.piece < torrent.getPieceCount() &&
                    this.offset + this.length <=
                            torrent.getPiece(this.piece).size()) {
                return this;
            }

            throw new MessageValidationException(this);
        }

        public static CancelMessage parse(ByteBuffer buffer,
                                          Torrentfile torrent) throws MessageValidationException {
            int piece = buffer.getInt();
            int offset = buffer.getInt();
            int length = buffer.getInt();
            return new CancelMessage(buffer, piece,
                    offset, length).validate(torrent);
        }

        public static CancelMessage craft(int piece, int offset, int length) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(
                    MESSAGE_LENGTH_FIELD_SIZE + CancelMessage.BASE_SIZE);
            buffer.putInt(CancelMessage.BASE_SIZE);
            buffer.put(PeerMessage.Type.CANCEL.getTypeByte());
            buffer.putInt(piece);
            buffer.putInt(offset);
            buffer.putInt(length);
            return new CancelMessage(buffer, piece, offset, length);
        }

        public String toString() {
            return super.toString() + " #" + this.getPiece() +
                    " (" + this.getLength() + "@" + this.getOffset() + ")";
        }
    }

    public static class PieceMessage extends PeerMessage {

        private static final int BASE_SIZE = 9;

        private int piece;
        private int offset;
        private ByteBuffer block;

        private PieceMessage(ByteBuffer buffer, int piece,
                             int offset, ByteBuffer block) {
            super(Type.PIECE, buffer);
            this.piece = piece;
            this.offset = offset;
            this.block = block;
        }

        public int getPiece() {
            return this.piece;
        }

        public int getOffset() {
            return this.offset;
        }

        public ByteBuffer getBlock() {
            return this.block;
        }

        @Override
            public PieceMessage validate(Torrentfile torrent)
                throws MessageValidationException {
            if (this.piece >= 0 && this.piece < torrent.getPieceCount() &&
                    this.offset + this.block.limit() <=
                            torrent.getPiece(this.piece).size()) {
                return this;
            }

            throw new MessageValidationException(this);
        }

        public static PieceMessage parse(ByteBuffer buffer,
                                         Torrentfile torrent) throws MessageValidationException {
            int piece = buffer.getInt();
            int offset = buffer.getInt();
            ByteBuffer block = buffer.slice();
            return new PieceMessage(buffer, piece, offset, block)
                    .validate(torrent);
        }

        public static PieceMessage craft(int piece, int offset,
                                         ByteBuffer block) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(
                    MESSAGE_LENGTH_FIELD_SIZE + PieceMessage.BASE_SIZE + block.capacity());
            buffer.putInt(PieceMessage.BASE_SIZE + block.capacity());
            buffer.put(PeerMessage.Type.PIECE.getTypeByte());
            buffer.putInt(piece);
            buffer.putInt(offset);
            buffer.put(block);
            return new PieceMessage(buffer, piece, offset, block);
        }

        public String toString() {
            return super.toString() + " #" + this.getPiece() +
                    " (" + this.getBlock().capacity() + "@" + this.getOffset() + ")";
        }
    }

    public static class HaveMessage extends PeerMessage {

        private static final int BASE_SIZE = 5;

        private int piece;

        private HaveMessage(ByteBuffer buffer, int piece) {
            super(Type.HAVE, buffer);
            this.piece = piece;
        }

        public int getPieceIndex() {
            return this.piece;
        }

        @Override
        public HaveMessage validate(Torrentfile torrent)
                throws MessageValidationException {
            if (this.piece >= 0 && this.piece < torrent.getPieceCount()) {
                return this;
            }

            throw new MessageValidationException(this);
        }

        public static HaveMessage parse(ByteBuffer buffer,
                                        Torrentfile torrent) throws MessageValidationException {
            return new HaveMessage(buffer, buffer.getInt())
                    .validate(torrent);
        }

        public static HaveMessage craft(int piece) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(
                    MESSAGE_LENGTH_FIELD_SIZE + HaveMessage.BASE_SIZE);
            buffer.putInt(HaveMessage.BASE_SIZE);
            buffer.put(PeerMessage.Type.HAVE.getTypeByte());
            buffer.putInt(piece);
            return new HaveMessage(buffer, piece);
        }

        public String toString() {
            return super.toString() + " #" + this.getPieceIndex();
        }
    }

    public static class BitfieldMessage extends PeerMessage {

        private static final int BASE_SIZE = 1;

        private BitSet bitfield;

        private BitfieldMessage(ByteBuffer buffer, BitSet bitfield) {
            super(Type.BITFIELD, buffer);
            this.bitfield = bitfield;
        }

        public BitSet getBitfield() {
            return this.bitfield;
        }

        @Override
        public BitfieldMessage validate(Torrentfile torrent)
                throws MessageValidationException {
            if (this.bitfield.length() <= torrent.getPieceCount()) {
                return this;
            }

            throw new MessageValidationException(this);
        }

        public static BitfieldMessage parse(ByteBuffer buffer,
                                            Torrentfile torrent) throws MessageValidationException {
            BitSet bitfield = new BitSet(buffer.remaining()*8);
            for (int i=0; i < buffer.remaining()*8; i++) {
                if ((buffer.get(i/8) & (1 << (7 -(i % 8)))) > 0) {
                    bitfield.set(i);
                }
            }

            return new BitfieldMessage(buffer, bitfield)
                    .validate(torrent);
        }

        public static BitfieldMessage craft(BitSet availablePieces, int pieceCount) {
            BitSet bitfield = new BitSet();
            int bitfieldBufferSize= (pieceCount + 8 - 1) / 8;
            byte[] bitfieldBuffer = new byte[bitfieldBufferSize];

            for (int i=availablePieces.nextSetBit(0);
                 0 <= i && i < pieceCount;
                 i=availablePieces.nextSetBit(i+1)) {
                bitfieldBuffer[i/8] |= 1 << (7 -(i % 8));
                bitfield.set(i);
            }

            ByteBuffer buffer = ByteBuffer.allocateDirect(
                    MESSAGE_LENGTH_FIELD_SIZE + BitfieldMessage.BASE_SIZE + bitfieldBufferSize);
            buffer.putInt(BitfieldMessage.BASE_SIZE + bitfieldBufferSize);
            buffer.put(PeerMessage.Type.BITFIELD.getTypeByte());
            buffer.put(ByteBuffer.wrap(bitfieldBuffer));

            return new BitfieldMessage(buffer, bitfield);
        }

        public String toString() {
            return super.toString() + " " + this.getBitfield().cardinality();
        }
    }

    public static class KeepAliveMessage extends PeerMessage {

        private static final int BASE_SIZE = 0;

        private KeepAliveMessage(ByteBuffer buffer) {
            super(Type.KEEP_ALIVE, buffer);
        }

        public static KeepAliveMessage parse(ByteBuffer buffer,
                                             Torrentfile torrent) throws MessageValidationException {
            return (KeepAliveMessage)new KeepAliveMessage(buffer)
                    .validate(torrent);
        }

        public static KeepAliveMessage craft() {
            ByteBuffer buffer = ByteBuffer.allocateDirect(
                    MESSAGE_LENGTH_FIELD_SIZE + KeepAliveMessage.BASE_SIZE);
            buffer.putInt(KeepAliveMessage.BASE_SIZE);
            return new KeepAliveMessage(buffer);
        }
    }

    public static class MessageValidationException extends ParseException {

        static final long serialVersionUID = -1;

        public MessageValidationException(PeerMessage m) {
            super("Message " + m + " is not valid!", 0);
        }

    }

}
