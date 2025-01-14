package com.netease.nim.camellia.redis.proxy.upstream.local.storage.value.block;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.netease.nim.camellia.redis.proxy.upstream.local.storage.constants.EmbeddedStorageConstants.data_file_size;

/**
 * Created by caojiajun on 2025/1/6
 */
public class ValueManifest implements IValueManifest {

    private static final Logger logger = LoggerFactory.getLogger(ValueManifest.class);

    private final String dir;

    private final ConcurrentHashMap<Long, BlockType> typeMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BlockType, List<Long>> fileIdMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> allocateOffsetMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, BitSet> bits1Map = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, BitSet> bits2Map = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ReentrantLock> lockMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, MappedByteBuffer> bitsMmp = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, MappedByteBuffer> slotsMmp = new ConcurrentHashMap<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public ValueManifest(String dir) {
        this.dir = dir;
    }

    @Override
    public void load() throws IOException {
        File dict = new File(dir);
        if (dict.isFile()) {
            throw new IOException(dir + " is not dict");
        }
        File[] files = dict.listFiles();
        if (files != null) {
            for (File file : files) {
                loadIndexFile(file);
            }
        }
        logger.info("value manifest load success, dir = {}, index.file.count = {}", dir, typeMap.size());
    }

    @Override
    public BlockLocation allocate(short slot, BlockType blockType) throws IOException {
        int index = 0;
        while (true) {
            long fileId = selectFileId(blockType, index);
            BlockLocation blockLocation = allocate0(fileId);
            if (blockLocation == null) {
                index ++;
                continue;
            }
            return blockLocation;
        }
    }

    @Override
    public void commit(short slot, BlockLocation blockLocation) throws IOException {
        long fileId = blockLocation.fileId();
        int blockId = blockLocation.blockId();
        //
        BitSet bitSet1 = bits1Map.get(fileId);
        if (!bitSet1.get(blockId)) {
            throw new IOException("fileId=" + fileId + ",blockId=" + blockId + " not allocated");
        }
        //bits2
        BitSet bitSet2 = bits2Map.get(fileId);
        bitSet2.set(blockId, true);
        //update file
        //bits
        int index = blockId / 64;
        long changed = bitSet2.toLongArray()[index];
        MappedByteBuffer buffer1 = bitsMmp.get(fileId);
        buffer1.putLong(index*8, changed);
        //slot
        MappedByteBuffer buffer2 = slotsMmp.get(fileId);
        buffer2.putShort(blockId*2, slot);
    }

    @Override
    public void recycle(short slot, BlockLocation blockLocation) throws IOException {
        long fileId = blockLocation.fileId();
        int blockId = blockLocation.blockId();
        //bits1
        BitSet bitSet1 = bits1Map.get(fileId);
        if (!bitSet1.get(blockId)) {
            throw new IOException("fileId=" + fileId + ",blockId=" + blockId + " not allocated");
        }
        bitSet1.set(blockId, false);
        Integer offset = allocateOffsetMap.get(fileId);
        if (offset > blockId) {
            allocateOffsetMap.put(fileId, blockId);
        }
        //bits2
        BitSet bitSet2 = bits2Map.get(fileId);
        bitSet2.set(blockId, false);
        //update file
        int index = blockId / 64;
        long[] longArray = bitSet2.toLongArray();
        long changed;
        if (longArray.length > index) {
            changed = longArray[index];
        } else {
            changed = 0;
        }
        MappedByteBuffer buffer1 = bitsMmp.get(fileId);
        buffer1.putLong(index*8, changed);

        MappedByteBuffer buffer2 = slotsMmp.get(fileId);
        buffer2.putShort(blockId*2, (short) -1);
    }

    @Override
    public BlockType blockType(long fileId) {
        return typeMap.get(fileId);
    }

    private BlockLocation allocate0(long fileId) {
        ReentrantLock lock = lockMap.get(fileId);
        lock.lock();
        try {
            BitSet bitSet = bits1Map.get(fileId);
            Integer start = allocateOffsetMap.get(fileId);
            for (int i=start; i<bitSet.size(); i++) {
                boolean used = bitSet.get(i);
                if (!used) {
                    bitSet.set(i, true);
                    allocateOffsetMap.put(fileId, i + 1);
                    return new BlockLocation(fileId, i);
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    private long selectFileId(BlockType blockType, int index) throws IOException {
        List<Long> list = fileIdMap.computeIfAbsent(blockType, k -> new CopyOnWriteArrayList<>());
        lock.readLock().lock();
        try {
            if (index < list.size()) {
                return list.get(index);
            }
        } finally {
            lock.readLock().unlock();
        }
        lock.writeLock().lock();
        try {
            long fileId = init(blockType);
            list.add(fileId);
            return fileId;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private long init(BlockType blockType) throws IOException {
        long fileId = System.currentTimeMillis();
        typeMap.put(fileId, blockType);
        int bitSize = (int) (data_file_size / blockType.getBlockSize());
        bits1Map.put(fileId, new BitSet(bitSize));
        bits2Map.put(fileId, new BitSet(bitSize));
        lockMap.put(fileId, new ReentrantLock());
        allocateOffsetMap.put(fileId, 0);
        String indexFileName = dir + "/" + fileId + "_" + blockType.getType() + ".index";
        String slotFileName = dir + "/" + fileId + "_" + blockType.getType() + ".slot";
        String dataFileName = dir + "/" + fileId + "_" + blockType.getType() + ".data";
        File indexFile = new File(indexFileName);
        if (!indexFile.exists()) {
            boolean newFile = indexFile.createNewFile();
            logger.info("create index.file = {}, result = {}", indexFileName, newFile);
        }
        File slotFile = new File(slotFileName);
        if (!slotFile.exists()) {
            boolean newFile = slotFile.createNewFile();
            logger.info("create slot.file = {}, result = {}", slotFileName, newFile);
        }
        File dataFile = new File(dataFileName);
        if (!dataFile.exists()) {
            boolean newFile = dataFile.createNewFile();
            logger.info("create data.file = {}, result = {}", indexFileName, newFile);
        }
        {
            FileChannel fileChannel = FileChannel.open(Paths.get(indexFileName), StandardOpenOption.READ, StandardOpenOption.WRITE);
            MappedByteBuffer map = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, bitSize / 8);
            bitsMmp.put(fileId, map);
        }
        {
            FileChannel fileChannel = FileChannel.open(Paths.get(slotFileName), StandardOpenOption.READ, StandardOpenOption.WRITE);
            MappedByteBuffer map = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, bitSize * 2L);
            slotsMmp.put(fileId, map);
        }
        return fileId;
    }

    private void loadIndexFile(File file) throws IOException {
        IndexFile indexFile = IndexFile.parse(file);
        if (indexFile == null) {
            return;
        }
        long fileId = indexFile.fileId();
        BlockType blockType = indexFile.blockType();
        FileChannel fileChannel = FileChannel.open(Paths.get(file.getPath()), StandardOpenOption.READ, StandardOpenOption.WRITE);
        ByteBuffer buffer = ByteBuffer.allocate((int) fileChannel.size());
        fileChannel.read(buffer);
        buffer.flip();
        long[] longArray = new long[(int) (fileChannel.size() / 8)];
        int index = 0;
        while (buffer.hasRemaining()) {
            long l = buffer.getLong();
            longArray[index] = l;
            index ++;
        }
        BitSet bitSet1 = BitSet.valueOf(longArray);
        BitSet bitSet2 = BitSet.valueOf(longArray);
        bits1Map.put(fileId, bitSet1);
        bits2Map.put(fileId, bitSet2);
        typeMap.put(fileId, blockType);
        List<Long> list = fileIdMap.computeIfAbsent(blockType, k -> new ArrayList<>());
        list.add(fileId);
        int offset = 0;
        for (int i=0; i<bitSet1.size(); i++) {
            if (!bitSet1.get(i)) {
                offset = i;
                break;
            }
        }
        allocateOffsetMap.put(fileId, offset);
        lockMap.put(fileId, new ReentrantLock());
        bitsMmp.put(fileId, fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, blockType.valueManifestSize(data_file_size)));

        File slotFile = new File(dir + "/" + fileId + "_" + blockType.getType() + ".slot");
        if (!slotFile.exists()) {
            boolean newFile = slotFile.createNewFile();
            logger.warn("create slot.file = {} for not exists, result = {}", slotFile.getName(), newFile);
        }
        FileChannel slotFileChannel = FileChannel.open(slotFile.getAbsoluteFile().toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE);
        MappedByteBuffer map = slotFileChannel.map(FileChannel.MapMode.READ_WRITE, 0, blockType.valueSlotManifestSize(data_file_size));
        slotsMmp.put(fileId, map);
    }
}
