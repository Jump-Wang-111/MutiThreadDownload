package Download.Protocol;

public class NetFile {

    long length;
    String name;

    public NetFile(long length, String name) {
        this.length = length;
        this.name = name;
    }

    public long getLength() {
        return length;
    }

    public String getName() {
        return name;
    }
}
