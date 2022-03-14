package Download;

import java.util.Objects;

/**
 * 表示下载的文件块
 * 重写equals和hashcode方法来比较
 */
public final class Block {

    private final long blockstart;
    private final long blockend;

    public Block(long start, long end) {
        blockstart = start;
        blockend = end;
    }

    public long getBlockstart() {
        return blockstart;
    }

    public long getBlockend() {
        return blockend;
    }
    @Override
    public boolean equals(Object o) {
        if(this == o) {
            return true;
        }
        Block b = (Block)o;
        return b.blockstart == blockstart && b.blockend == blockend;
    }

    @Override
    public int hashCode() {
        return Objects.hash(blockstart, blockend);
    }

}