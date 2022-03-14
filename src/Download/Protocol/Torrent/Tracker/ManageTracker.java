package Download.Protocol.Torrent.Tracker;

import Download.Protocol.Torrent.Listener.TrackerListener;
import Download.Protocol.Torrent.Peer.Peer;
import Download.Protocol.Torrent.Torrentfile;

import java.util.*;
import java.util.logging.Logger;

public class ManageTracker implements Runnable {

    Logger logger = Logger.getGlobal();

    private final Torrentfile torrent;
    private final Peer peer;

    private final List<List<Tracker>> trackers;
    private final Set<Tracker> allTrackers;

    private Thread thread;

    // 发送间隔
    private int interval;

    private boolean stop;
    private boolean forceStop;

    private int currentTier;
    private int currentTracker;

    public ManageTracker(Torrentfile torrent, Peer peer) {
        this.torrent = torrent;
        this.peer = peer;
        this.trackers = new ArrayList<>();
        this.allTrackers = new HashSet<>();

        /*
          将torrent中的http tracker提取出来
          完成trackers和allTracker的初始化
         */
        for(List<String> tier : torrent.getAnnounceList()) {
            ArrayList<Tracker> tierTracker = new ArrayList<>();
            for(String trackerUrl : tier) {

                // 我们只处理http的tracker，udp协议暂不实现
                if(!trackerUrl.substring(0, 4).equals("http")) {
                    logger.info("udp is not supported.");
                    continue;
                }
                Tracker tracker = new Tracker(trackerUrl, torrent, peer);

                tierTracker.add(tracker);
                allTrackers.add(tracker);
            }

            // 根据bep12，随机打乱该层的tracker顺序
            Collections.shuffle(tierTracker);
            trackers.add(tierTracker);
        }

        this.thread = null;
        logger.info("start init trackers, total " + allTrackers.size());

    }

    /**
     * 注册监听者
     * 这里实际用来监听Tracker消息，故传给Tracker
     * 通过调用监听者方法可以实现想爱你向监听者传递消息
     * @param listener 监听者对象
     */
    public void register(TrackerListener listener) {
        for(Tracker tracker : allTrackers) {
            tracker.register(listener);
        }
    }

    /**
     * 开始Tracker部分的程序
     */
    public void start() {
        stop = false;
        forceStop = false;

        if(trackers.size() > 0 && (thread == null || !thread.isAlive())) {
            thread = new Thread(this);
            thread.setName("bt-manageTracker");
            thread.start();
        }

    }

    /**
     * 停止该线程
     */
    public void stop() {
        stop = true;

        if(thread != null && thread.isAlive()) {
            thread.interrupt();

            try {
                this.thread.join();
            } catch (InterruptedException e) {
                logger.severe(e.getMessage());
            }
        }
        thread = null;
    }

    /**
     * 根据 bep12中的说明
     * The tiers of announces will be processed sequentially;
     * all URLs in each tier must be checked before the client goes on to the next tier.
     * URLs within each tier will be processed in a randomly chosen order;
     * in other words, the list will be shuffled when first read, and then parsed in order.
     * In addition, if a connection with a tracker is successful,
     * it will be moved to the front of the tier.
     */

    @Override
    public void run() {
        logger.info("Tracker part start...");

        // 设置初始间隔，间隔会随tracker的响应更新
        interval = 10;

        TrackerMessage.RequestEvent event = TrackerMessage.RequestEvent.STARTED;

        while(!stop) {
            // 若不能与当前tracker成功交互，则尝试下一个tracker
            try {
                getCurrentTracker().sendMessage(event, false);
                promoteTracker();
                // 连接成功改为定期刷新
                event = TrackerMessage.RequestEvent.NONE;
                // 只有连接成功时才会sleep，否则直接尝试下一个tracker
                Thread.sleep(interval * 1000L);

            } catch (TrackerException te) {
                logger.severe(te.getMessage());
                nextTracker();
            } catch (Exception e) {
                logger.severe(e.getMessage());
            }

        }

        logger.info("Tracker communicate over");

        if(!forceStop) {
            // 强制停止，设置发送终止信号
            event = TrackerMessage.RequestEvent.STOPPED;

            // 在命令之后等一会终止
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                logger.severe(e.getMessage());
            }

            // 发送终止信息给tracker
            try {
                getCurrentTracker().sendMessage(event, false);
            } catch (TrackerException te) {
                logger.severe(te.getMessage());
            }
        }

    }

    /**
     * 设置和tracker交互的时间间隔
     * 由Torrent监听者触发
     */
    public void setInterval(int interval) {
        if (interval <= 0) {
            this.stop(true);
            return;
        }

        if (this.interval == interval) {
            return;
        }

        logger.info("Setting announce interval " + interval + "s");
        this.interval = interval;
    }

    /**
     * 获取当前正在连接的tracker
     * @return Tracker
     */
    public Tracker getCurrentTracker() {
        return trackers.get(currentTier).get(currentTracker);
    }

    /**
     * 根据bep12
     * 每次成功与tracker交互后，将该tracker提到本层的第一个位置
     */
    private void promoteTracker() {
        Collections.swap(trackers.get(currentTier), currentTracker, 0);
        logger.info("move tracker in tier " + currentTier + " to first");
        currentTracker = 0;
    }

    /**
     * 若连接tracker失败，则需要尝试与下一个tracker开始交互
     */
    private void nextTracker() {
        int tier = currentTier;
        int tracker = currentTracker + 1;

        if(tracker >= trackers.get(tier).size()) {
            tracker = 0;
            tier++;
            if(tier >= trackers.size()) {
                tier = 0;
            }
        }
        currentTier = tier;
        currentTracker = tracker;

        logger.info("Move to new tracker in tier " + tier
                + " index " + tracker);
    }

    /**
     * 停止该线程
     * @param force 是否强制停止
     */
    private void stop(boolean force) {
        this.forceStop = force;
        this.stop();
    }

}
