package Download.Protocol.Torrent.Tracker;

import Download.Protocol.Torrent.Listener.TrackerListener;
import Download.Protocol.Torrent.Peer.Peer;
import Download.Protocol.Torrent.Torrentfile;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.eclipse.bittorrent.internal.encode.BEncodedDictionary;
import org.eclipse.bittorrent.internal.encode.Decode;

public class Tracker {

    Logger logger = Logger.getGlobal();

    private final Torrentfile torrent;

    private final String url;

    private final Peer peer;

    private final Set<TrackerListener> listeners;

    public Tracker(String url, Torrentfile torrent, Peer peer) {
        this.url = url;
        this.torrent = torrent;
        this.peer = peer;

        listeners = new HashSet<>();
    }

    /**
     * 注册监听者，该对象被监听
     * 通过调用监听者方法可以实现想爱你向监听者传递消息
     * @param listener 监听者对象
     */
    public void register(TrackerListener listener) {
        listeners.add(listener);
    }

    public String getTrackerUrl() {
        return url;
    }

    public void sendMessage(TrackerMessage.RequestEvent event, boolean inhibit) throws TrackerException {

        logger.info("try to connect to tracker: " + url + "\n" +
                "status: " + event.getEventName() + "\n" +
                "uploaded: " + torrent.getUpload() + "\n" +
                "downloaded: " + torrent.getDownload() + "\n" +
                "left: " + torrent.getLeft() + "\n");

        CloseableHttpClient httpclient = HttpClients.createDefault();
        URI uri = null;

        // 构建uri
        try {
            uri = buildRequest(event);
        } catch (URISyntaxException e) {
            throw new TrackerException("build uri error:" + e.getMessage());
        }

        // 尝试与tracker交互
        HttpGet httpget = new HttpGet(uri);
        InputStream in = null;
        try {
            HttpResponse response = httpclient.execute(httpget);
            int status = response.getStatusLine().getStatusCode();
            if (!(status >= 200 && status < 300)) {
                throw new ClientProtocolException("Unexpected response status: " + status);
            }
            HttpEntity entity = response.getEntity();
            in = entity.getContent();

        } catch (ClientProtocolException e) {
            throw new TrackerException("status code is wrong. " + e.getMessage());
        } catch (IOException e) {
            throw new TrackerException("cannot get content: " + e.getMessage());
        }

        if(in == null) {
            throw new TrackerException("No response or unreachable tracker!");
        }

        // 对返回的信息进行解码
        try {
            BEncodedDictionary dictionary = Decode.bDecode(in);
            String peers = null;
            long interval = 0;
            if(dictionary.containsKey("interval") && dictionary.containsKey("peers")) {
                peers = (String) dictionary.get("peers");
                interval = (long) dictionary.get("interval");
            }

            ArrayList<Peer> peerList = new ArrayList<>();

            // 将peer装进list
            for (int i = 0; i < peers.length() / 6; i++) {
                StringBuilder ss = new StringBuilder();

                int j = 6 * i;
                for (int k = 0; k < 3; k++) {
                    ss.append((int) peers.charAt(j++)).append('.');
                }
                ss.append((int) peers.charAt(j++));

                int port = (peers.charAt(j++)) << 8 | peers.charAt(j);
                peerList.add(new Peer(ss.toString(), port));

            }
            if(!inhibit) {
                fireEvent_TrackerResponse((int)interval);
                fireEvent_NewPeer(peerList);
            }

        } catch (IOException e) {
            throw new TrackerException("response parse error: " + e.getMessage());
        } finally {
            try {
                in.close();

            } catch (IOException e) {
                logger.severe("Problem ensuring error stream closed!\n" + e.getMessage());
            }
        }

    }

    public URI buildRequest(TrackerMessage.RequestEvent event) throws URISyntaxException {
        URI uri = new URIBuilder(url)
                .addParameter("info_hash", torrent.getInfoHash())
                .addParameter("peer_id", peer.getPeer_id())
                .addParameter("port",String.valueOf(peer.getPort()))
                .addParameter("uploaded", String.valueOf(torrent.getUpload()))
                .addParameter("downloaded", String.valueOf(torrent.getDownload()))
                .addParameter("left", String.valueOf(torrent.getLeft()))
                .addParameter("numwant", "200")
                .addParameter("compact", "1")
                .addParameter("no_peer_id", "0")
                .addParameter("event", event.getEventName())
                .setCharset(StandardCharsets.ISO_8859_1)
                .build();
        logger.info("build request as: " + uri);
        return uri;
    }

    /**
     * 触发tracker回复的监听
     */
    public void fireEvent_TrackerResponse(int interval) {
        for(TrackerListener listener : listeners) {
            listener.handleTrackerResponse(interval);
        }
    }

    /**
     * 触发发现新Peer的监听
     */
    public void fireEvent_NewPeer(List<Peer> peers) {
        for(TrackerListener listener : listeners) {
            listener.handleDiscoveredPeers(peers);
        }
    }
}
