package Download.Protocol;

public class Magnet {

    String magnet;
    int threads;
    String output;

    public Magnet(String url, int t, String output) {
        this.magnet = url;
        threads = t;
        this.output = output;
    }
}
