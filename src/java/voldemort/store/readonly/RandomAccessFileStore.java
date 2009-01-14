/*
 * Copyright 2008-2009 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.store.readonly;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.log4j.Logger;

import voldemort.VoldemortException;
import voldemort.store.Entry;
import voldemort.store.PersistenceFailureException;
import voldemort.store.StorageEngine;
import voldemort.utils.ByteUtils;
import voldemort.utils.ClosableIterator;
import voldemort.versioning.VectorClock;
import voldemort.versioning.Version;
import voldemort.versioning.Versioned;

import com.google.common.base.Objects;

/**
 * A read-only store that fronts a big file
 * 
 * @author jay
 * 
 */
public class RandomAccessFileStore implements StorageEngine<byte[], byte[]> {

    private static Logger logger = Logger.getLogger(RandomAccessFileStore.class);

    public static int KEY_HASH_SIZE = 16;
    public static int POSITION_SIZE = 8;

    private final String name;
    private final long waitTimeoutMs;
    private long indexFileSize;
    private long dataFileSize;
    private final int numBackups;
    private final int numFileHandles;
    private final File storageDir;
    private final File dataFile;
    private final File indexFile;
    private final ReadWriteLock fileModificationLock;
    private BlockingQueue<RandomAccessFile> indexFiles;
    private BlockingQueue<RandomAccessFile> dataFiles;

    public RandomAccessFileStore(String name,
                                 File storageDir,
                                 int numBackups,
                                 int numFileHandles,
                                 long waitTimeoutMs) {
        this.storageDir = storageDir;
        this.numBackups = numBackups;
        this.indexFile = new File(storageDir, name + ".index");
        this.dataFile = new File(storageDir, name + ".data");
        this.name = Objects.nonNull(name);
        this.waitTimeoutMs = waitTimeoutMs;
        this.dataFiles = new ArrayBlockingQueue<RandomAccessFile>(numFileHandles);
        this.indexFiles = new ArrayBlockingQueue<RandomAccessFile>(numFileHandles);
        this.numFileHandles = numFileHandles;
        this.fileModificationLock = new ReentrantReadWriteLock();
        if(indexFileSize % (KEY_HASH_SIZE + POSITION_SIZE) != 0)
            throw new IllegalArgumentException("Invalid index file, file length must be a multiple of "
                                               + (KEY_HASH_SIZE + POSITION_SIZE) + ".");
        open();
    }

    public void open() {
        fileModificationLock.writeLock().lock();
        try {
            this.indexFiles = new ArrayBlockingQueue<RandomAccessFile>(numFileHandles);
            this.dataFiles = new ArrayBlockingQueue<RandomAccessFile>(numFileHandles);
            for(int i = 0; i < numFileHandles; i++) {
                indexFiles.add(new RandomAccessFile(indexFile, "r"));
                dataFiles.add(new RandomAccessFile(dataFile, "r"));
            }
            this.indexFileSize = getFileSize(indexFiles);
            this.dataFileSize = getFileSize(dataFiles);
        } catch(FileNotFoundException e) {
            throw new VoldemortException("Could not open store.", e);
        } finally {
            fileModificationLock.writeLock().unlock();
        }
        this.indexFileSize = getFileSize(indexFiles);
        this.dataFileSize = getFileSize(dataFiles);
    }

    public void swapFiles(String newIndexFile, String newDataFile) {
        fileModificationLock.writeLock().lock();
        try {
            close();

            // backup index and data files
            shiftBackups(".index");
            shiftBackups(".data");
            File firstIndexBackup = new File(storageDir, name + ".index.1");
            File firstDataBackup = new File(storageDir, name + ".data.1");
            boolean success = indexFile.getAbsoluteFile().renameTo(firstIndexBackup)
                              && dataFile.getAbsoluteFile().renameTo(firstDataBackup);
            if(!success)
                throw new VoldemortException("Error while renaming backups.");

            // copy in new files
            success = new File(newIndexFile).renameTo(indexFile)
                      && new File(newDataFile).renameTo(dataFile);
            if(!success) {
                logger.error("Failure while copying in new data files, restoring from backup and aborting.");
                success = firstIndexBackup.renameTo(indexFile)
                          && firstDataBackup.renameTo(dataFile);
                if(success) {
                    logger.error("Restored from backup.");
                    throw new VoldemortException("Failure while copying in new data files, but managed to restore from backup.");
                } else {
                    logger.error("Rollback failed too.");
                    throw new VoldemortException("Failure while copying in new data files, and restoration failed, everything is FUBAR.");
                }
            }

            open();
        } finally {
            fileModificationLock.writeLock().unlock();
        }
    }

    private void shiftBackups(String suffix) {
        for(int i = numBackups - 1; i > 0; i--) {
            File theFile = new File(storageDir, name + suffix + "." + i);
            if(theFile.exists()) {
                File theDest = new File(storageDir, name + suffix + "." + i + 1);
                boolean succeeded = theFile.renameTo(theDest);
                if(!succeeded)
                    throw new VoldemortException("Rename of " + theFile + " to " + theDest
                                                 + " failed.");
            }
        }
    }

    private long getFileSize(BlockingQueue<RandomAccessFile> files) {
        RandomAccessFile f = null;
        try {
            f = getFile(files);
            return f.length();
        } catch(IOException e) {
            throw new VoldemortException(e);
        } catch(InterruptedException e) {
            throw new VoldemortException(e);
        } finally {
            if(f != null)
                files.add(f);
        }
    }

    public ClosableIterator<Entry<byte[], Versioned<byte[]>>> entries() {
        throw new RuntimeException("Not implemented.");
    }

    public List<Versioned<byte[]>> get(byte[] key) throws VoldemortException {
        RandomAccessFile index = null;
        RandomAccessFile data = null;
        try {
            fileModificationLock.readLock().lock();
            index = getFile(indexFiles);
            long valueLocation = getValueLocation(index, key);
            if(valueLocation < 0) {
                return Collections.emptyList();
            } else {
                data = getFile(dataFiles);
                data.seek(valueLocation);
                int size = data.readInt();
                byte[] value = new byte[size];
                data.readFully(value);
                return Collections.singletonList(new Versioned<byte[]>(value, new VectorClock()));
            }
        } catch(InterruptedException e) {
            throw new VoldemortException("Thread was interrupted.", e);
        } catch(IOException e) {
            throw new PersistenceFailureException(e);
        } finally {
            fileModificationLock.readLock().unlock();
            if(index != null)
                indexFiles.add(index);
            if(data != null)
                dataFiles.add(data);
        }
    }

    private long getValueLocation(RandomAccessFile index, byte[] key) throws IOException,
            InterruptedException {
        byte[] keyMd5 = ByteUtils.md5(key);
        byte[] foundKey = new byte[KEY_HASH_SIZE];
        int chunkSize = KEY_HASH_SIZE + POSITION_SIZE;
        long low = 0;
        long high = indexFileSize / chunkSize - 1;
        while(low < high) {
            long mid = low + (high - low) / 2;
            index.seek(mid * chunkSize);
            index.readFully(foundKey);
            int cmp = ByteUtils.compare(foundKey, keyMd5);
            if(cmp == 0) {
                // they are equal, return the location stored here
                return index.readLong();
            } else if(cmp > 0) {
                // midVal is bigger
                high = mid;
            } else if(cmp < 0) {
                // the keyMd5 is bigger
                low = mid;
            }
        }

        return -1;
    }

    /**
     * Not supported, throws UnsupportedOperationException if called
     */
    public boolean delete(byte[] key, Version version) throws VoldemortException {
        throw new UnsupportedOperationException("Delete is not supported on this store, it is read-only.");
    }

    /**
     * Not supported, throws UnsupportedOperationException if called
     */
    public void put(byte[] key, Versioned<byte[]> value) throws VoldemortException {
        throw new UnsupportedOperationException("Put is not supported on this store, it is read-only.");
    }

    public String getName() {
        return name;
    }

    public void close() throws VoldemortException {
        logger.debug("Close called for read-only store.");
        this.fileModificationLock.writeLock().lock();
        try {
            while(this.indexFiles.size() > 0) {
                RandomAccessFile f = this.indexFiles.take();
                f.close();
            }

            while(this.dataFiles.size() > 0) {
                RandomAccessFile f = this.dataFiles.poll();
                f.close();
            }
        } catch(IOException e) {
            throw new VoldemortException("Error while closing store.", e);
        } catch(InterruptedException e) {
            throw new VoldemortException("Interrupted while waiting for file descriptor.");
        } finally {
            this.fileModificationLock.writeLock().unlock();
        }
    }

    private RandomAccessFile getFile(BlockingQueue<RandomAccessFile> files)
            throws InterruptedException {
        RandomAccessFile file = files.poll(waitTimeoutMs, TimeUnit.MILLISECONDS);
        if(file == null)
            throw new VoldemortException("Timeout after waiting for " + waitTimeoutMs
                                         + " ms to acquire file descriptor");
        else
            return file;
    }

}