package Download.Protocol.Torrent.Tracker;

/**
 * 捕捉服务器交互时产生的错误
 */
public class TrackerException extends Exception{
    public TrackerException(String message) {
        super(message);
    }

    public TrackerException(Throwable cause) {
        super(cause);
    }

    public TrackerException(String message, Throwable cause) {
        super(message, cause);
    }
}
