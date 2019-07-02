/**
 * Copyright (c) 2017-2018 The Semux Developers
 *
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 */
package org.semux.db;

import static org.fusesource.leveldbjni.JniDBFactory.factory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.tuple.Pair;
import org.fusesource.leveldbjni.JniDBFactory;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.WriteBatch;
import org.semux.db.exception.DatabaseException;
import org.semux.util.ClosableIterator;
import org.semux.util.FileUtil;
import org.semux.util.SystemUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LeveldbDatabase implements Database {

    private static final Logger logger = LoggerFactory.getLogger(LeveldbDatabase.class);

    private final File file;
    private DB db;
    private boolean isOpened;

    /**
     * Creates an LevelDB instance and opens it.
     *
     * @param file
     */
    public LeveldbDatabase(File file) {
        this.file = file;

        File dir = file.getParentFile();
        if (!dir.exists() && !dir.mkdirs()) {
            logger.error("Failed to create directory: {}", dir);
        }

        open(createOptions());
    }

    /**
     * Creates the default options.
     *
     * @return
     */
    protected Options createOptions() {
        Options options = new Options();
        options.createIfMissing(true);
        options.compressionType(CompressionType.SNAPPY);
        options.blockSize(4 * 1024 * 1024);
        options.writeBufferSize(8 * 1024 * 1024);
        options.cacheSize(64L * 1024L * 1024L);
        options.paranoidChecks(true);
        options.verifyChecksums(true);
        options.maxOpenFiles(128);

        return options;
    }

    /**
     * Open the database.
     *
     * @param options
     */
    protected void open(Options options) {
        try {
            db = JniDBFactory.factory.open(file, options);
            isOpened = true;
        } catch (IOException e) {
            if (e.getMessage().contains("Corruption")) {
                // recover
                recover(options);

                // reopen
                try {
                    db = JniDBFactory.factory.open(file, options);
                    isOpened = true;
                } catch (IOException ex) {
                    logger.error("Failed to open database", e);
                    SystemUtil.exitAsync(SystemUtil.Code.FAILED_TO_OPEN_DB);
                }
            } else {
                logger.error("Failed to open database", e);
                SystemUtil.exitAsync(SystemUtil.Code.FAILED_TO_OPEN_DB);
            }
        }
    }

    /**
     * Tries to recover the database in case of corruption.
     *
     * @param options
     */
    protected void recover(Options options) {
        try {
            logger.info("Trying to repair the database: {}", file);
            factory.repair(file, options);
            logger.info("Repair done!");
        } catch (IOException ex) {
            logger.error("Failed to repair the database", ex);
            SystemUtil.exitAsync(SystemUtil.Code.FAILED_TO_REPAIR_DB);
        }
    }

    @Override
    public byte[] get(byte[] key) {
        return db.get(key);
    }

    @Override
    public void put(byte[] key, byte[] value) {
        db.put(key, value);
    }

    @Override
    public void delete(byte[] key) {
        db.delete(key);
    }

    @Override
    public void updateBatch(List<Pair<byte[], byte[]>> pairs) {
        try (WriteBatch batch = db.createWriteBatch()) {
            for (Pair<byte[], byte[]> p : pairs) {
                if (p.getValue() == null) {
                    batch.delete(p.getLeft());
                } else {
                    batch.put(p.getLeft(), p.getRight());
                }
            }
            db.write(batch);
        } catch (IOException e) {
            logger.error("Failed to update batch", e);
            SystemUtil.exitAsync(SystemUtil.Code.FAILED_TO_WRITE_BATCH_TO_DB);
        }
    }

    WriteBatch createWriteBatch() {
        return db.createWriteBatch();
    }

    void writeBatch(WriteBatch batch) {
        db.write(batch);
    }

    @Override
    public void close() {
        try {
            if (isOpened) {
                db.close();
                isOpened = false;
            }
        } catch (IOException e) {
            logger.error("Failed to close database: {}", file, e);
        }
    }

    @Override
    public void destroy() {
        close();
        FileUtil.recursiveDelete(file);
    }

    @Override
    public Path getDataDir() {
        return file.toPath();
    }

    @Override
    public ClosableIterator<Entry<byte[], byte[]>> iterator() {
        return iterator(null);
    }

    @Override
    public ClosableIterator<Entry<byte[], byte[]>> iterator(byte[] prefix) {

        return new ClosableIterator<Entry<byte[], byte[]>>() {
            final DBIterator itr = db.iterator();

            private ClosableIterator<Entry<byte[], byte[]>> initialize() {
                if (prefix != null) {
                    itr.seek(prefix);
                } else {
                    itr.seekToFirst();
                }
                return this;
            }

            @Override
            public boolean hasNext() {
                return itr.hasNext();
            }

            @Override
            public Entry<byte[], byte[]> next() {
                return itr.next();
            }

            @Override
            public void close() {
                try {
                    itr.close();
                } catch (IOException e) {
                    throw new DatabaseException(e);
                }
            }
        }.initialize();
    }

    public static class LeveldbFactory implements DatabaseFactory {

        private final Map<DatabaseName, Database> databases = Collections
                .synchronizedMap(new EnumMap<>(DatabaseName.class));

        private final File dataDir;
        private final Map<DatabaseName, AtomicBoolean> open = Collections
                .synchronizedMap(new EnumMap<>(DatabaseName.class));

        public LeveldbFactory(File dataDir) {
            this.dataDir = dataDir;

            for (DatabaseName name : DatabaseName.values()) {
                open.put(name, new AtomicBoolean(false));
            }
        }

        @Override
        public void open() {
            // do nothing
            // resources will be lazy loaded
        }

        @Override
        public Database getDB(DatabaseName name) {
            // lazy load
            if (open.get(name).compareAndSet(false, true)) {
                File file = getDatabaseFile(name);
                databases.put(name, new LeveldbDatabase(file));
            }

            return databases.get(name);
        }

        @Override
        public void close() {
            databases.forEach((dbName, db) -> {
                if (open.get(dbName).compareAndSet(true, false)) {
                    db.close();
                }
            });
        }

        @Override
        public boolean exists(DatabaseName name) {
            return getDatabaseFile(name).exists();
        }

        @Override
        public Path getDataDir() {
            return dataDir.toPath();
        }

        private File getDatabaseFile(DatabaseName name) {
            return new File(dataDir.getAbsolutePath(), name.toString().toLowerCase(Locale.ROOT));
        }
    }
}
