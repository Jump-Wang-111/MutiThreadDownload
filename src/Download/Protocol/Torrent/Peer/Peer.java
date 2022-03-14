package Download.Protocol.Torrent.Peer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public class Peer {

    private final InetSocketAddress address;
    private String peer_id;
    private String host_id;

    public Peer(InetSocketAddress address, String peer_id) {
        this.address = address;
        this.peer_id = peer_id;
        this.host_id = String.format("%s:%d", this.address.getAddress(), this.address.getPort());
    }

    public Peer(String ip, int port, String peer_id) {
        this(new InetSocketAddress(ip, port), peer_id);
    }

    public Peer(String ip, int port) {
        this(new InetSocketAddress(ip, port));
    }

    public Peer(InetSocketAddress address) {
        this(address, null);
    }

    /**
     * 获取peer的ip地址
     * @return ip
     */
    public String getIp() {
        return address.getAddress().getHostAddress();
    }

    /**
     * 获取peer的端口
     * @return port
     */
    public int getPort() {
        return address.getPort();
    }

    /**
     * 获取整个address
     * @return InetAddress
     */
    public InetAddress getAddress() {
        return address.getAddress();
    }

    /**
     * 获取peer_id
     * @return String
     */
    public String getPeer_id() {
        return peer_id;
    }

    /**
     * s设置peer id
     * 主要用来更新peer id
     * @param peer_id String
     */
    public void setPeer_id(String peer_id) {
        this.peer_id = peer_id;
    }

    /**
     * 是否具有peer id
     * @return peer id == null
     */
    public boolean hasPeerId() {
        return this.peer_id != null;
    }

    /**
     * 获取client的唯一标识
     * @return ip:port
     */
    public String getHost_id() {
        return host_id;
    }
}
