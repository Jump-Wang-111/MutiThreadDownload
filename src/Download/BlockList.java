package Download;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 单例模式
 * 构造全局使用的list
 */
public class BlockList {
    private static final BlockList instance = new BlockList();
    private final List<Block> list = new CopyOnWriteArrayList<>();

    private BlockList() {}
    public static BlockList getInstance() {
        return instance;
    }

    public List<Block> getList() {
        return list;
    }
}
