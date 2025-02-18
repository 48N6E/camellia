package com.netease.nim.camellia.redis.proxy.upstream.local.storage.key.slot;

import com.netease.nim.camellia.redis.proxy.upstream.local.storage.constants.LocalStorageConstants;
import com.netease.nim.camellia.redis.proxy.upstream.local.storage.file.FileNames;
import com.netease.nim.camellia.redis.proxy.util.RedisClusterCRC16Utils;
import com.netease.nim.camellia.redis.proxy.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.netease.nim.camellia.redis.proxy.upstream.local.storage.constants.LocalStorageConstants.max_key_capacity;


/**
 * Created by caojiajun on 2024/12/31
 */
public class KeyManifest implements IKeyManifest {

    private static final Logger logger = LoggerFactory.getLogger(KeyManifest.class);

    private static final byte[] magic_header = "camellia_header".getBytes(StandardCharsets.UTF_8);
    private static final byte[] magic_footer = "camellia_footer".getBytes(StandardCharsets.UTF_8);

    private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();

    private final Map<Short, SlotInfo> slotInfoMap = new HashMap<>();//slot -> slotInfo
    private final Map<Long, BitSet> fileBitsMap = new TreeMap<>();

    private final String dir;
    private final String fileName;
    private FileChannel fileChannel;

    public KeyManifest(String dir) {
        this.dir = dir;
        this.fileName = FileNames.keyManifestFile(dir);
    }

    @Override
    public String dir() {
        return dir;
    }

    @Override
    public void load() throws IOException {
        logger.info("try load key.manifest.file = {}", fileName);
        FileNames.createKeyManifestFileIfNotExists(dir);
        fileChannel = FileChannel.open(Paths.get(fileName), StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (fileChannel.size() == 0) {
            ByteBuffer buffer1 = ByteBuffer.wrap(magic_header);
            while (buffer1.hasRemaining()) {
                int write = fileChannel.write(buffer1);
                logger.info("init key.manifest.file, magic_header, key.manifest.file = {}, write.len = {}", fileName, write);
            }
            ByteBuffer buffer2 = ByteBuffer.wrap(magic_footer);
            while (buffer2.hasRemaining()) {
                int write = fileChannel.write(buffer2, magic_header.length + RedisClusterCRC16Utils.SLOT_SIZE * (8+8+8));
                logger.info("init key.manifest.file, magic_footer, key.manifest.file = {}, write.len = {}", fileName, write);
            }
        } else {
            int len = magic_header.length + magic_footer.length + RedisClusterCRC16Utils.SLOT_SIZE * (8+8+8);
            if (fileChannel.size() != len) {
                throw new IOException("key.manifest.file illegal size");
            }
            ByteBuffer buffer = ByteBuffer.allocate(len);
            fileChannel.read(buffer);
            buffer.flip();
            byte[] realMagicHeader = new byte[magic_header.length];
            buffer.get(realMagicHeader);
            if (!Arrays.equals(realMagicHeader, magic_header)) {
                throw new IOException("key.manifest.file magic_header not match!");
            }
            long totalCapacity = 0;
            for (short slot=0; slot<RedisClusterCRC16Utils.SLOT_SIZE; slot++) {
                long fileId = buffer.getLong();
                long offset = buffer.getLong();
                long capacity = buffer.getLong();
                if (fileId == 0) {
                    continue;
                }
                BitSet bits = fileBitsMap.computeIfAbsent(fileId, k -> new BitSet(LocalStorageConstants.key_manifest_bit_size));
                int bitsStart = (int)(offset / LocalStorageConstants._64k);
                int bitsEnd = (int)((offset + capacity) / LocalStorageConstants._64k);
                for (int index=bitsStart; index<bitsEnd; index++) {
                    bits.set(index, true);
                }
                SlotInfo slotInfo = new SlotInfo(fileId, offset, capacity);
                slotInfoMap.put(slot, slotInfo);
                logger.info("load slot info, slot = {}, fileId = {}, offset = {}, capacity = {}", slot, fileId, offset, capacity);
                totalCapacity += capacity;
            }
            logger.info("load slot info, key.file.count = {}, key.file.size = {}/{}",
                    fileBitsMap.size(), totalCapacity, Utils.humanReadableByteCountBin(totalCapacity));
            byte[] realMagicFooter = new byte[magic_footer.length];
            buffer.get(realMagicFooter);
            if (!Arrays.equals(realMagicFooter, magic_footer)) {
                throw new IOException("key.manifest.file magic_footer not match!");
            }
            logger.info("load key.manifest.file success, key.manifest.file = {}, slot.count = {}", fileName, slotInfoMap.size());
        }
    }

    @Override
    public Set<Long> getFileIds() {
        return new HashSet<>(fileBitsMap.keySet());
    }

    @Override
    public SlotInfo get(short slot) {
        readWriteLock.readLock().lock();
        try {
            return slotInfoMap.get(slot);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    @Override
    public SlotInfo init(short slot) throws IOException {
        readWriteLock.writeLock().lock();
        try {
            SlotInfo slotInfo = slotInfoMap.get(slot);
            if (slotInfo != null) {
                throw new IOException("slot has init");
            }
            for (Map.Entry<Long, BitSet> entry : fileBitsMap.entrySet()) {
                Long fileId = entry.getKey();
                BitSet bits = entry.getValue();
                for (int i = 0; i< LocalStorageConstants.key_manifest_bit_size; i++) {
                    boolean used = bits.get(i);
                    if (!used) {
                        update(slot, fileId, (long) i * LocalStorageConstants._64k, LocalStorageConstants._64k);
                        bits.set(i, true);
                        return slotInfoMap.get(slot);
                    }
                }
            }
            long fileId = initFileId();
            update(slot, fileId, 0, LocalStorageConstants._64k);
            fileBitsMap.get(fileId).set(0, true);
            return slotInfoMap.get(slot);
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    @Override
    public SlotInfo expand(short slot) throws IOException {
        readWriteLock.writeLock().lock();
        try {
            SlotInfo slotInfo = slotInfoMap.get(slot);
            if (slotInfo == null) {
                throw new IllegalArgumentException("slot not init");
            }
            long fileId = slotInfo.fileId();
            long offset = slotInfo.offset();
            long capacity = slotInfo.capacity();
            if (capacity * 2 <= 0) {
                throw new IOException("slot capacity exceed");
            }
            if (capacity * 2 > max_key_capacity) {
                throw new IOException("slot capacity exceed");
            }
            int bitsStep = (int) (capacity / LocalStorageConstants._64k);
            if (bitsStep <= 0) {
                throw new IOException("slot capacity exceed");
            }
            BitSet bits = fileBitsMap.get(fileId);
            int bitsStart = (int)(offset / LocalStorageConstants._64k);
            int bitsEnd = (int)((offset + capacity) / LocalStorageConstants._64k);
            if (bitsStart < 0 || bitsEnd < 0) {
                throw new IOException("slot capacity exceed");
            }
            //直接顺延扩容
            if (bitsStart + (bitsEnd - bitsStart) * 2 < LocalStorageConstants.key_manifest_bit_size) {
                boolean directExpand = true;
                for (int i=bitsEnd; i<bitsEnd + bitsStep; i++) {
                    boolean used = bits.get(i);
                    if (used) {
                        directExpand = false;
                        break;
                    }
                }
                if (directExpand) {
                    //set new
                    for (int i=bitsEnd; i<bitsEnd + bitsStep; i++) {
                        bits.set(i, true);
                    }
                    //update
                    update(slot, fileId, offset, capacity*2);
                    return slotInfoMap.get(slot);
                }
            }
            //使用同一个文件的空闲区域，优先复用其他slot回收的区域
            if (LocalStorageConstants.key_manifest_bit_size - bits.cardinality() >= bitsStep*2) {
                for (int i = 0; i< LocalStorageConstants.key_manifest_bit_size -bitsStep*2; i++) {
                    if (bits.get(i, i + bitsStep * 2).cardinality() == 0) {
                        //clear old
                        for (int j=bitsStart; j<bitsEnd; j++) {
                            bits.set(j, false);
                        }
                        //set new
                        for (int j=i; j<i+bitsStep*2; j++) {
                            bits.set(j, true);
                        }
                        //update
                        update(slot, fileId, (long) i * LocalStorageConstants._64k, capacity*2);
                        return slotInfoMap.get(slot);
                    }
                }
            }
            //尝试其他文件
            for (Map.Entry<Long, BitSet> entry : fileBitsMap.entrySet()) {
                Long otherFileId = entry.getKey();
                if (otherFileId == fileId) {
                    continue;
                }
                BitSet bitSet = entry.getValue();
                if (LocalStorageConstants.key_manifest_bit_size - bitSet.cardinality() >= bitsStep*2) {
                    for (int i = 0; i< LocalStorageConstants.key_manifest_bit_size -bitsStep*2; i++) {
                        if (bitSet.get(i, i + bitsStep * 2).cardinality() == 0) {
                            //clear old
                            for (int j=bitsStart; j<bitsEnd; j++) {
                                bitSet.set(j, false);
                            }
                            //set new
                            for (int j=i; j<i+bitsStep*2; j++) {
                                bitSet.set(j, true);
                            }
                            //update
                            update(slot, otherFileId, (long) i * LocalStorageConstants._64k, capacity*2);
                            return slotInfoMap.get(slot);
                        }
                    }
                }
            }
            //分配不出来就使用新文件
            for (int i=bitsStart; i<bitsEnd; i++) {
                bits.set(i, false);
            }
            fileId = initFileId();
            update(slot, fileId, 0, capacity*2);
            BitSet bitSet = fileBitsMap.get(fileId);
            for (int i=0; i<bitsStep*2; i++) {
                bitSet.set(i, true);
            }
            return slotInfoMap.get(slot);
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    private long initFileId() throws IOException {
        long fileId = System.currentTimeMillis();
        while (fileBitsMap.containsKey(fileId)) {
            fileId ++;
        }
        fileBitsMap.put(fileId, new BitSet(LocalStorageConstants.key_manifest_bit_size));
        FileNames.createKeyFile(dir, fileId);
        return fileId;
    }

    private void update(short slot, long fileId, long offset, long capacity) throws IOException {
        slotInfoMap.put(slot, new SlotInfo(fileId, offset, capacity));
        ByteBuffer buffer = ByteBuffer.allocate(8+8+8);
        buffer.putLong(fileId);
        buffer.putLong(offset);
        buffer.putLong(capacity);
        buffer.flip();
        long position = magic_header.length + slot * (8+8+8);
        while (buffer.hasRemaining()) {
            int write = fileChannel.write(buffer, position);
            if (logger.isDebugEnabled()) {
                logger.debug("write slot info, slot = {}, result = {}", slot, write);
            }
        }
        logger.info("update slot info, slot = {}, fileId = {}, offset = {}, capacity = {}", slot, fileId, offset, capacity);
    }
}
