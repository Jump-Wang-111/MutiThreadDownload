package Download.Protocol.Torrent.Peer;

import java.io.Serializable;
import java.util.Comparator;

public class Rate implements Comparable<Rate>{
    public static final Comparator<Rate> RATE_COMPARATOR =
            new RateComparator();

    private long bytes = 0;
    private long reset = 0;
    private long last = 0;

    public synchronized void add(long count) {
        this.bytes += count;
        if (this.reset == 0) {
            this.reset = System.currentTimeMillis();
        }
        this.last = System.currentTimeMillis();
    }

    public synchronized float get() {
        if (this.last - this.reset == 0) {
            return 0;
        }

        return this.bytes / ((this.last - this.reset) / 1000.0f);
    }

    public synchronized void reset() {
        this.bytes = 0;
        this.reset = System.currentTimeMillis();
        this.last = this.reset;
    }

    @Override
    public int compareTo(Rate other) {
        return RATE_COMPARATOR.compare(this, other);
    }

    /**
     * 该类提供一个比较器
     * 我们可以通过比较上传速率来对peer排序
     */
    private static class RateComparator
            implements Comparator<Rate>, Serializable {

        private static final long serialVersionUID = 72460233003600L;

        @Override
        public int compare(Rate a, Rate b) {
            if (a.get() > b.get()) {
                return 1;
            }

            return -1;
        }
    }
}
