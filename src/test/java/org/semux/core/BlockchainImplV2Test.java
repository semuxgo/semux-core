/**
 * Copyright (c) 2017-2018 The Semux Developers
 *
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 */
package org.semux.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.semux.core.Amount.Unit.NANO_SEM;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.semux.Network;
import org.semux.TestUtils;
import org.semux.config.AbstractConfig;
import org.semux.config.Config;
import org.semux.config.Constants;
import org.semux.config.DevnetConfig;
import org.semux.crypto.Key;
import org.semux.rules.TemporaryDatabaseRule;
import org.semux.util.Bytes;
import org.semux.util.MerkleUtil;
import org.semux.util.TimeUtil;

public class BlockchainImplV2Test {

    @Rule
    public TemporaryDatabaseRule temporaryDBFactory = new TemporaryDatabaseRule();

    private Config config;
    private BlockchainImplV2 chain;
    private BlockchainFactory blockchainFactory;

    private byte[] coinbase = Bytes.random(30);
    private byte[] prevHash = Bytes.random(32);

    private Network network = Network.DEVNET;
    private Key key = new Key();
    private byte[] from = key.toAddress();
    private byte[] to = Bytes.random(20);
    private Amount value = NANO_SEM.of(20);
    private Amount fee = NANO_SEM.of(1);
    private long nonce = 12345;
    private byte[] data = Bytes.of("test");
    private long timestamp = TimeUtil.currentTimeMillis() - 60 * 1000;
    private Transaction tx = new Transaction(network, TransactionType.TRANSFER, to, value, fee, nonce, timestamp,
            data)
                    .sign(key);
    private TransactionResult res = new TransactionResult();

    @Before
    public void setUp() {
        config = new DevnetConfig(Constants.DEFAULT_DATA_DIR);
        blockchainFactory = (new BlockchainFactory(new DevnetConfig(Constants.DEFAULT_DATA_DIR),
                Genesis.load(Network.DEVNET), temporaryDBFactory));
        chain = (BlockchainImplV2) blockchainFactory.getBlockchainInstance();
    }

    @Test
    public void testGetLatestBlock() {
        assertEquals(0, chain.getLatestBlockNumber());
        assertNotNull(chain.getLatestBlockHash());
        assertNotNull(chain.getLatestBlock());

        Block newBlock = createBlock(1);
        chain.addBlock(newBlock);
        chain.commit();

        assertNotEquals(0, chain.getLatestBlockNumber());
        assertEquals(newBlock.getNumber(), chain.getLatestBlock().getNumber());
    }

    @Test
    public void testGetLatestBlockHash() {
        Block newBlock = createBlock(1);
        chain.addBlock(newBlock);

        assertEquals(newBlock.getNumber(), chain.getLatestBlockNumber());
        assertArrayEquals(newBlock.getHash(), chain.getLatestBlockHash());
    }

    @Test
    public void testGetBlock() {
        assertEquals(0, chain.getBlock(0).getNumber());
        assertNull(chain.getBlock(1));

        long number = 1;
        Block newBlock = createBlock(number);
        chain.addBlock(newBlock);
        chain.commit();

        assertEquals(number, chain.getBlock(number).getNumber());
        assertEquals(number, chain.getBlock(newBlock.getHash()).getNumber());
    }

    @Test
    public void testHasBlock() {
        assertFalse(chain.hasBlock(-1));
        assertTrue(chain.hasBlock(0));
        assertFalse(chain.hasBlock(1));
    }

    @Test
    public void testGetBlockNumber() {
        long number = 1;
        Block newBlock = createBlock(number);
        chain.addBlock(newBlock);
        chain.commit();

        assertEquals(number, chain.getBlockNumber(newBlock.getHash()));
    }

    @Test
    public void testGetGenesis() {
        assertArrayEquals(Genesis.load(network).getHash(), chain.getGenesis().getHash());
    }

    @Test
    public void testGetBlockHeader() {
        assertArrayEquals(Genesis.load(network).getHash(), chain.getBlockHeader(0).getHash());

        long number = 1;
        Block newBlock = createBlock(number);
        chain.addBlock(newBlock);
        chain.commit();

        assertArrayEquals(newBlock.getHash(), chain.getBlockHeader(1).getHash());
        assertEquals(newBlock.getNumber(), chain.getBlockHeader(newBlock.getHash()).getNumber());
    }

    @Test
    public void testGetTransaction() {
        assertNull(chain.getTransaction(tx.getHash()));

        Block newBlock = createBlock(1);
        chain.addBlock(newBlock);
        chain.commit();

        Transaction t = chain.getTransaction(tx.getHash());
        assertNotNull(t);
        assertTrue(Arrays.equals(from, t.getFrom()));
        assertTrue(Arrays.equals(to, t.getTo()));
        assertTrue(Arrays.equals(data, t.getData()));
        assertEquals(value, t.getValue());
        assertEquals(nonce, t.getNonce());
        assertEquals(timestamp, t.getTimestamp());
    }

    @Test
    public void testHasTransaction() {
        assertFalse(chain.hasTransaction(tx.getHash()));

        Block newBlock = createBlock(1);
        chain.addBlock(newBlock);
        chain.commit();

        assertTrue(chain.hasTransaction(tx.getHash()));
    }

    @Test
    public void testGetTransactionResult() {
        assertNull(chain.getTransaction(tx.getHash()));

        Block newBlock = createBlock(1);
        chain.addBlock(newBlock);
        chain.commit();

        TransactionResult r = chain.getTransactionResult(tx.getHash());
        assertArrayEquals(res.toBytes(), r.toBytes());
    }

    @Test
    public void testGetTransactionBlockNumber() {
        Block newBlock = createBlock(1);
        chain.addBlock(newBlock);
        chain.commit();

        assertEquals(newBlock.getNumber(), chain.getTransactionBlockNumber(tx.getHash()));
    }

    @Test
    public void testGetCoinbaseTransactionBlockNumber() {
        for (int i = 1; i <= 10; i++) {
            byte[] coinbase = new Key().toAddress();
            Block newBlock = createBlock(i, coinbase, Bytes.EMPTY_BYTES, Collections.emptyList(),
                    Collections.emptyList());
            chain.addBlock(newBlock);
            chain.commit();
            List<Transaction> transactions = chain.getTransactions(coinbase, 0, 1);
            assertEquals(1, transactions.size());
            assertEquals(newBlock.getNumber(), transactions.get(0).getNonce());
            assertEquals(TransactionType.COINBASE, transactions.get(0).getType());
            assertEquals(newBlock.getNumber(), chain.getTransactionBlockNumber(transactions.get(0).getHash()));
            assertEquals(chain.getCoinbaseTransaction(i), transactions.get(0));
        }
    }

    @Test
    public void testGetTransactionCount() {
        assertNull(chain.getTransaction(tx.getHash()));

        Block newBlock = createBlock(1);
        chain.addBlock(newBlock);
        chain.commit();

        assertEquals(1, chain.getTransactionCount(tx.getFrom()));
    }

    @Test
    public void testGetAccountTransactions() {
        assertNull(chain.getTransaction(tx.getHash()));

        Block newBlock = createBlock(1);
        chain.addBlock(newBlock);
        chain.commit();

        List<Transaction> txs = chain.getTransactions(tx.getFrom(), 0, 100);
        assertEquals(1, txs.size());
        assertArrayEquals(tx.toBytes(), txs.get(0).toBytes());
    }

    @Test
    public void testSerialization() {
        Block block1 = createBlock(1);

        BlockEncoder blockEncoder = new BlockEncoderV1();
        Block block2 = new BlockDecoderV1().decodeComponents(
                blockEncoder.encoderHeader(block1),
                blockEncoder.encodeTransactions(block1),
                blockEncoder.encodeTransactionResults(block1),
                blockEncoder.encodeVotes(block1));
        assertArrayEquals(block1.getHash(), block2.getHash());
        assertArrayEquals(block1.getCoinbase(), block2.getCoinbase());
        assertArrayEquals(block1.getParentHash(), block2.getParentHash());
        assertEquals(block1.getNumber(), block2.getNumber());

        assertEquals(block1.getTransactions().size(), block2.getTransactions().size());
    }

    @Test
    public void testGetTransactions() {
        Block block = createBlock(1);
        chain.addBlock(block);
        chain.commit();

        List<Transaction> list = chain.getTransactions(from, 0, 1024);
        assertEquals(1, list.size());
        assertArrayEquals(tx.getHash(), list.get(0).getHash());

        list = chain.getTransactions(to, 0, 1024);
        assertEquals(1, list.size());
        assertArrayEquals(tx.getHash(), list.get(0).getHash());
    }

    @Test
    public void testGetTransactionsSelfTx() {
        Transaction selfTx = new Transaction(network, TransactionType.TRANSFER, key.toAddress(), value, fee, nonce,
                timestamp, data).sign(key);
        Block block = createBlock(
                1,
                Collections.singletonList(selfTx),
                Collections.singletonList(res));

        chain.addBlock(block);
        chain.commit();

        // there should be only 1 transaction added into index database
        List<Transaction> list = chain.getTransactions(key.toAddress(), 0, 1024);
        assertEquals(1, list.size());
        assertArrayEquals(selfTx.getHash(), list.get(0).getHash());
    }

    @Test
    public void testValidatorStates() {
        byte[] address = Bytes.random(20);

        assertEquals(0, chain.getValidatorStats(address).getBlocksForged());
        assertEquals(0, chain.getValidatorStats(address).getTurnsHit());
        assertEquals(0, chain.getValidatorStats(address).getTurnsMissed());

        chain.adjustValidatorStats(address, ValidatorStatsType.FORGED, 1);
        chain.commit();
        assertEquals(1, chain.getValidatorStats(address).getBlocksForged());

        chain.adjustValidatorStats(address, ValidatorStatsType.HIT, 1);
        chain.commit();
        assertEquals(1, chain.getValidatorStats(address).getTurnsHit());

        chain.adjustValidatorStats(address, ValidatorStatsType.MISSED, 1);
        chain.commit();
        assertEquals(1, chain.getValidatorStats(address).getTurnsMissed());
        chain.adjustValidatorStats(address, ValidatorStatsType.MISSED, 1);
        chain.commit();
        assertEquals(2, chain.getValidatorStats(address).getTurnsMissed());
    }

    @Test
    public void testForkActivated() {
        final Fork fork = Fork.UNIFORM_DISTRIBUTION;
        for (long i = 1; i <= fork.activationBlocksLookup; i++) {
            chain.addBlock(
                    createBlock(i, coinbase, BlockHeaderData.v1(new BlockHeaderData.ForkSignalSet(fork)).toBytes(),
                            Collections.singletonList(tx), Collections.singletonList(res)));
            chain.commit();

            if (i <= fork.activationBlocks) {
                for (long j = 0; j <= i; j++) {
                    assertFalse(chain.isForkActivated(fork, i));
                }
            } else {
                for (long j = i; j > fork.activationBlocks; j--) {
                    assertTrue(chain.isForkActivated(fork, j));
                }

                for (long j = fork.activationBlocks; j >= 0; j--) {
                    assertFalse(chain.isForkActivated(fork, j));
                }
            }
        }

        for (long i = 0; i <= fork.activationBlocks; i++) {
            assertFalse(chain.isForkActivated(fork, i));
        }

        for (long i = fork.activationBlocks + 1; i <= fork.activationBlocksLookup; i++) {
            assertTrue(chain.isForkActivated(fork, i));
        }
    }

    @Test
    public void testForkCompatibility() {
        Fork fork = Fork.UNIFORM_DISTRIBUTION;
        Block block = createBlock(1, coinbase, BlockHeaderData.v1(new BlockHeaderData.ForkSignalSet(fork)).toBytes(),
                Collections.singletonList(tx), Collections.singletonList(res));
        TestUtils.setInternalState(config, "forkUniformDistributionEnabled", false, AbstractConfig.class);
        chain = (BlockchainImplV2) blockchainFactory.getBlockchainInstance();
        chain.addBlock(block);
    }

    private Block createBlock(long number) {
        return createBlock(number, Collections.singletonList(tx), Collections.singletonList(res));
    }

    private Block createBlock(long number, List<Transaction> transactions, List<TransactionResult> results) {
        return createBlock(number, coinbase, Bytes.EMPTY_BYTES, transactions, results);
    }

    private Block createBlock(long number, byte[] coinbase, byte[] data, List<Transaction> transactions,
            List<TransactionResult> results) {
        byte[] transactionsRoot = MerkleUtil.computeTransactionsRoot(transactions);
        byte[] resultsRoot = MerkleUtil.computeResultsRoot(results);
        byte[] stateRoot = Bytes.EMPTY_HASH;
        long timestamp = TimeUtil.currentTimeMillis();

        BlockHeader header = new BlockHeader(number, coinbase, prevHash, timestamp, transactionsRoot, resultsRoot,
                stateRoot, data);
        return new Block(header, transactions, results);
    }
}
