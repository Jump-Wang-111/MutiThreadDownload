package Download.Protocol.Torrent.Tracker;

/**
 * 我们需要与tracker发送不同类型的信息
 * 连接需要发送开始
 * 结束时需要发送完成
 * 中断要发送停止
 * 以及定期刷新发送NONE
 */
public interface TrackerMessage {
     enum RequestEvent {
        NONE(0),
        COMPLETED(1),
        STARTED(2),
        STOPPED(3);

        private final int id;
        RequestEvent(int id) {
            this.id = id;
        }

        public String getEventName() {
            return this.name().toLowerCase();
        }

        public int getId() {
            return this.id;
        }

        public static RequestEvent getByName(String name) {
            for (RequestEvent e : RequestEvent.values()) {
                if (e.name().equalsIgnoreCase(name)) {
                    return e;
                }
            }
            return null;
        }

        public static RequestEvent getById(int id) {
            for (RequestEvent e : RequestEvent.values()) {
                if (e.getId() == id) {
                    return e;
                }
            }
            return null;
        }
    }
}
