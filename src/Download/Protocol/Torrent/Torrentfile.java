package Download.Protocol.Torrent;

import Download.Protocol.Torrent.Listener.PeerListener;
import Download.Protocol.Torrent.Peer.Piece;
import Download.Protocol.Torrent.Peer.SharingPeer;
import Download.Protocol.Torrent.Storage.FileStorage;
import Download.Protocol.Torrent.Storage.MutiFileStorage;
import Download.Protocol.Torrent.Storage.Storage;
import org.eclipse.bittorrent.TorrentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import org.eclipse.bittorrent.internal.encode.BEncodedDictionary;
import org.eclipse.bittorrent.internal.encode.Decode;

public class Torrentfile extends TorrentFile implements PeerListener {

    Logger logger = Logger.getGlobal();

    private final int threads;

    private static final float ENG_GAME_COMPLETION_RATIO = 0.95f;
    private long upload;
    private long download;
    private long left;
    private final boolean seeder;

    private boolean stop;

    private final BEncodedDictionary dictionary;
    private final List<List<String>> announceList;

    private final Storage storage;
    private boolean initialized;
    private final long pieceLength;
    private final String[] piecesHashes;
    private Piece[] pieces;
    private final SortedSet<Piece> rarest;
    private BitSet completedPieces;
    private final BitSet requestedPieces;

    private double maxUploadRate = 0.0;
    private double maxDownloadRate = 0.0;

    private static final int RAREST_PIECE_JITTER = 42;
    private final Random random;

    public Torrentfile(File file, File dest) throws IOException {
        this(file, dest, 8, false);
    }

    public Torrentfile(File file, File dest,int threads) throws IOException {
        this(file, dest, threads, false);
    }

    public Torrentfile(File file, File dest,int threads, boolean seeder) throws IOException {
        super(file);

        if (dest == null || !dest.isDirectory()) {
            throw new IllegalArgumentException("Invalid dest directory!");
        }

        this.threads = threads;
        this.seeder = seeder;
        this.random = new Random(System.currentTimeMillis());

        this.dictionary = Decode.bDecode(new FileInputStream(file));
        announceList = new ArrayList<>();
        initAnnounceList();
        String[] filenames = this.getFilenames();
        long[] lengths = this.getLengths();

        String parentPath = dest.getCanonicalPath();

        try {
            this.pieceLength = this.getPieceLength();
            this.piecesHashes = this.getPieces();

            if (this.piecesHashes.length * this.pieceLength < this.getTotalLength()) {
                throw new IllegalArgumentException(
                        "Torrent size does not " + "match the number of pieces and the piece size!");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Error reading torrent meta-info fields!");
        }

        List<FileStorage> files = new LinkedList<>();
        long offset = 0L;
        for (int i = 0; i < filenames.length; i++) {
            File actual = new File(dest, this.getName() + File.separator + filenames[i]);

            if (!actual.getCanonicalPath().startsWith(parentPath)) {
                throw new SecurityException("Torrent file path attempted " + "to break directory jail!");
            }

            actual.getParentFile().mkdirs();
            files.add(new FileStorage(actual, offset, lengths[i]));
            offset += lengths[i];
        }
        this.storage = new MutiFileStorage(files, this.getTotalLength());

        this.stop = false;

        this.upload = 0;
        this.download = 0;
        this.left = this.getTotalLength();

        this.initialized = false;
        this.pieces = new Piece[0];
        this.rarest = Collections.synchronizedSortedSet(new TreeSet<>());
        this.completedPieces = new BitSet();
        this.requestedPieces = new BitSet();

    }

    public static byte[] hash(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest crypt;
        crypt = MessageDigest.getInstance("SHA-1");
        crypt.reset();
        crypt.update(data);
        return crypt.digest();
    }

    private void initAnnounceList() {

        List outside_tier = (List) this.dictionary.get("announce-list");

        for (Object o : outside_tier) {
//            System.out.println(announceList.get(i));
            List<String> tier = new ArrayList<>();
            String str_list = String.valueOf(o);
            str_list = str_list.substring(1, str_list.length() - 1);

            for (; ; ) {
                int spilt = str_list.indexOf(':');
                int size = Integer.parseInt(str_list.substring(0, spilt));
                tier.add(str_list.substring(spilt + 1, spilt + size + 1));
                if (spilt + size + 1 >= str_list.length()) {
                    break;
                }
                str_list = str_list.substring(spilt + size + 1);
            }

            announceList.add(tier);
        }
    }

    public List<List<String>> getAnnounceList() {
        return announceList;
    }

    public long getLeft() {
        return left;
    }

    public long getUpload() {
        return upload;
    }

    public long getDownload() {
        return download;
    }

    public double getMaxUploadRate() {
        return this.maxUploadRate;
    }

    public void setMaxUploadRate(double rate) {
        this.maxUploadRate = rate;
    }

    public double getMaxDownloadRate() {
        return this.maxDownloadRate;
    }

    public void setMaxDownloadRate(double rate) {
        this.maxDownloadRate = rate;
    }

    public boolean isInitialized() {
        return this.initialized;
    }

    public void stop() {
        this.stop = true;
    }

    /**
     * 我们是否初始化为一个seeder
     * @return boolean
     */
    public boolean isSeeder() {
        return this.seeder;
    }

//    /**
//     * 如果环境变量有TORRENT_HASHING_THREADS
//     * 则按照这个来，否则默认按照Java Runtime确定
//     * @return
//     */
//    protected static int getHashingThreadsCount() {
//        String threads = System.getenv("TORRENT_HASHING_THREADS");
//
//        if (threads != null) {
//            try {
//                int count = Integer.parseInt(threads);
//                if (count > 0) {
//                    return count;
//                }
//            } catch (NumberFormatException nfe) {
//                // Pass
//            }
//        }
//
//        return Runtime.getRuntime().availableProcessors();
//    }

    /**
     * 初始化piece[]数组
     */
    public synchronized void init() throws InterruptedException, IOException {

        if (this.isInitialized()) {
            throw new IllegalStateException("Torrent was already initialized!");
        }

//        int threads = getHashingThreadsCount();
        int nPieces = (int) (Math.ceil((double) this.getTotalLength() / this.pieceLength));
        int step = 10;

        this.pieces = new Piece[nPieces];
        this.completedPieces = new BitSet(nPieces);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<Piece>> results = new LinkedList<>();

        try {
            logger.info("Analyzing local data for " + this.getName() +
                            " with " + threads + " threads" +
                            " (" + nPieces + " pieces)...");
            for (int idx = 0; idx < nPieces; idx++) {
                byte[] hash = piecesHashes[idx].getBytes(StandardCharsets.ISO_8859_1);

                // 最后一个piece可能比设定的piece长度要小，我们要确保它正确
                long off = ((long) idx) * this.pieceLength;
                long len = Math.min(this.storage.size() - off, this.pieceLength);

                this.pieces[idx] = new Piece(this.storage, idx, off, len, hash, this.isSeeder());

                Callable<Piece> hasher = new Piece.CallableHasher(this.pieces[idx]);
                results.add(executor.submit(hasher));

                if (results.size() >= threads) {
                    this.validatePieces(results);
                }

                if (idx / (float) nPieces * 100f > step) {
                    logger.info("  ... " + step + "% complete");
                    step += 10;
                }
            }

            this.validatePieces(results);
        } finally {
            executor.shutdown();
            while (!executor.isTerminated()) {
                if (this.stop) {
                    throw new InterruptedException("Torrent data analysis " + "interrupted.");
                }

                Thread.sleep(10);
            }
        }

        logger.info("init torrent:" + this.getName() +
                "\n" + "pieces: " + pieces.length +
                "\n" + "bytes: " + this.getTotalLength());
        this.initialized = true;
    }

    private void validatePieces(List<Future<Piece>> results) throws IOException {
        try {
            for (Future<Piece> task : results) {
                Piece piece = task.get();
                if (this.pieces[piece.getIndex()].isValid()) {
                    this.completedPieces.set(piece.getIndex());
                    this.left -= piece.size();
                }
            }

            results.clear();
        } catch (Exception e) {
            logger.severe(e.getMessage());
            throw new IOException("Error while hashing a torrent piece!", e);
        }
    }

    public synchronized void close() {
        try {
            this.storage.close();
        } catch (IOException ioe) {
            logger.severe("Error closing torrent byte storage: " + ioe.getMessage());
        }
    }

    /**
     * 获取piece个数
     * @return int
     */
    public int getPieceCount() {
        if (this.pieces == null) {
            throw new IllegalStateException("Torrent not initialized yet.");
        }

        return pieces.length;
    }

    public Piece getPiece(int index) {
        if (this.pieces == null) {
            throw new IllegalStateException("Torrent not initialized yet.");
        }
        if(index >= pieces.length) {
            throw new IllegalArgumentException("piece out of index!");
        }
        return pieces[index];
    }

    /**
     * 是否完全下载了文件
     * @return boolean
     */
    public synchronized boolean isComplete() {
        return this.pieces.length > 0 && this.completedPieces.cardinality() == this.pieces.length;
    }

    /**
     * 返回以可使用的piece位图的复制
     * @return BitSet
     */
    public BitSet getAvailablePieces() {
        if (!this.isInitialized()) {
            throw new IllegalStateException("Torrent not yet initialized!");
        }

        BitSet availablePieces = new BitSet(this.pieces.length);

        synchronized (this.pieces) {
            for (Piece piece : this.pieces) {
                if (piece.available()) {
                    availablePieces.set(piece.getIndex());
                }
            }
        }

        return availablePieces;
    }

    /**
     * 返回以完成piece位图的复制
     * @return BitSet
     */
    public BitSet getCompletedPieces() {
        if (!this.isInitialized()) {
            throw new IllegalStateException("Torrent not yet initialized!");
        }

        synchronized (this.completedPieces) {
            return (BitSet) this.completedPieces.clone();
        }
    }

    /**
     * 返回一个被请求的piece位图的复制
     * @return BitSet
     */
    public BitSet getRequestedPieces() {
        if (!this.isInitialized()) {
            throw new IllegalStateException("Torrent not yet initialized!");
        }

        synchronized (this.requestedPieces) {
            return (BitSet) this.requestedPieces.clone();
        }
    }

    public synchronized void finish() throws IOException {
        if (!this.isInitialized()) {
            throw new IllegalStateException("Torrent not yet initialized!");
        }

        if (!this.isComplete()) {
            throw new IllegalStateException("Torrent download is not complete!");
        }

        this.storage.finish();
    }

    public synchronized boolean isFinished() {
        return this.isComplete() && this.storage.isFinished();
    }

    /**
     * 返回torrent的完成度
     * @return float
     */
    public float getCompletion() {
        return this.isInitialized() ? (float) this.completedPieces.cardinality() / (float) this.pieces.length * 100.0f
                : 0.0f;
    }

    /**
     * 标记一个piece已完成
     * 修改left
     * @param piece
     */
    public synchronized void markCompleted(Piece piece) {
        if (this.completedPieces.get(piece.getIndex())) {
            return;
        }

        this.left -= piece.size();
        this.completedPieces.set(piece.getIndex());
    }

    /**
     * 随机选择peer策略
     */
    public Piece choosePiece(SortedSet<Piece> rarest, BitSet interesting, Piece[] pieces) {
        // Extract the RAREST_PIECE_JITTER rarest pieces from the interesting
        // pieces of this peer.
        ArrayList<Piece> choice = new ArrayList<Piece>(RAREST_PIECE_JITTER);
        synchronized (rarest) {
            for (Piece piece : rarest) {
                if (interesting.get(piece.getIndex())) {
                    choice.add(piece);
                    if (choice.size() >= RAREST_PIECE_JITTER) {
                        break;
                    }
                }
            }
        }

        if (choice.size() == 0) return null;

        Piece chosen = choice.get(
                this.random.nextInt(
                        choice.size()));
        return chosen;
    }

    @Override
    public void handlePeerChoked(SharingPeer peer) {
        Piece piece = peer.getRequestedPiece();

        if (piece != null) {
            this.requestedPieces.set(piece.getIndex(), false);
        }

        logger.info("peer: " + peer.getAddress() +
                " choked, change bitset.");
    }

    @Override
    public void handlePeerReady(SharingPeer peer) {
        BitSet interesting = peer.getAvailablePieces();
        interesting.andNot(this.completedPieces);
        interesting.andNot(this.requestedPieces);

        // If we didn't find interesting pieces, we need to check if we're in
        // an end-game situation. If yes, we request an already requested piece
        // to try to speed up the end.
        if (interesting.cardinality() == 0) {
            interesting = peer.getAvailablePieces();
            interesting.andNot(this.completedPieces);
            if (interesting.cardinality() == 0) {
                logger.info("No interesting piece from: " + peer.getAddress());
                return;
            }

            if (this.completedPieces.cardinality() < ENG_GAME_COMPLETION_RATIO * this.pieces.length) {
                logger.info("Not far along enough to warrant end-game mode.");
                return;
            }

            logger.info("Possible end-game, we're about to request a piece "
                    + "that was already requested from another peer.");
        }

        Piece chosen = choosePiece(rarest, interesting, pieces);
        this.requestedPieces.set(chosen.getIndex());

        logger.info("Requesting peer: " + peer.getAddress());

        peer.downloadPiece(chosen);
    }

    @Override
    public void handlePieceAvailability(SharingPeer peer, Piece piece) {
        // If we don't have this piece, tell the peer we're interested in
        // getting it from him.
        if (!this.completedPieces.get(piece.getIndex()) && !this.requestedPieces.get(piece.getIndex())) {
            peer.interesting();
        }

        this.rarest.remove(piece);
        piece.seenAt(peer);
        this.rarest.add(piece);

        logger.info("Peer "+ peer.getAddress() + " contributes "
                        + peer.getAvailablePieces().cardinality() + " piece(s) ");

        if (!peer.isChoked() && peer.isInteresting() && !peer.isDownloading()) {
            this.handlePeerReady(peer);
        }
    }

    @Override
    public void handleBitfieldAvailability(SharingPeer peer, BitSet availablePieces) {
        // 决定peer感不感兴趣，或者忽视它
        BitSet interesting = (BitSet) availablePieces.clone();
        interesting.andNot(this.completedPieces);
        interesting.andNot(this.requestedPieces);

        if (interesting.cardinality() == 0) {
            peer.notInteresting();
        } else {
            peer.interesting();
        }

        // Record that the peer has all the pieces it told us it had.
        for (int i = availablePieces.nextSetBit(0); i >= 0; i = availablePieces.nextSetBit(i + 1)) {
            this.rarest.remove(this.pieces[i]);
            this.pieces[i].seenAt(peer);
            this.rarest.add(this.pieces[i]);
        }

        logger.info("Peer: " + peer.getAddress() +" contributes "
                + availablePieces.cardinality() + " piece(s)");
    }

    @Override
    public void handlePieceSent(SharingPeer peer, Piece piece) {
        this.upload += piece.size();
    }

    @Override
    public void handlePieceCompleted(SharingPeer peer, Piece piece) {

        this.download += piece.size();
        this.requestedPieces.set(piece.getIndex(), false);

    }

    @Override
    public void handlePeerDisconnected(SharingPeer peer) {
        BitSet availablePieces = peer.getAvailablePieces();

        for (int i = availablePieces.nextSetBit(0); i >= 0; i = availablePieces.nextSetBit(i + 1)) {
            this.rarest.remove(this.pieces[i]);
            this.pieces[i].noLongerSeenAt(peer);
            this.rarest.add(this.pieces[i]);
        }

        Piece requested = peer.getRequestedPiece();
        if (requested != null) {
            this.requestedPieces.set(requested.getIndex(), false);
        }

        logger.info("handlePeerDisconnected in Torrentfile.");
    }

    @Override
    public void handleIOException(SharingPeer peer, IOException ioe) {}

//    public static void main(String[] args) throws IOException {
//        String path = "D:\\uTorrent\\[PuyaSubs!] Kimetsu no Yaiba - 14 [1080p][7DCCE8EF].mkv.torrent";
//        Torrentfile f = new Torrentfile(new File(path), new File(".\\"));
//        List<List<String>> l =  f.getAnnounceList();
//        String s = "pause";
//        TorrentFile t;t.
//    }
}
