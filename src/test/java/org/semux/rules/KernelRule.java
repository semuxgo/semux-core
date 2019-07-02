/**
 * Copyright (c) 2017-2018 The Semux Developers
 *
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 */
package org.semux.rules;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.rules.TemporaryFolder;
import org.semux.KernelMock;
import org.semux.config.Config;
import org.semux.config.DevnetConfig;
import org.semux.core.Block;
import org.semux.core.BlockHeader;
import org.semux.core.Blockchain;
import org.semux.core.BlockchainFactory;
import org.semux.core.Genesis;
import org.semux.core.PendingManager;
import org.semux.core.Transaction;
import org.semux.core.TransactionResult;
import org.semux.core.Wallet;
import org.semux.crypto.Hex;
import org.semux.crypto.Key;
import org.semux.db.LeveldbDatabase.LeveldbFactory;
import org.semux.util.Bytes;
import org.semux.util.MerkleUtil;
import org.semux.util.TimeUtil;

/**
 * A kernel rule creates a temporary folder as the data directory. Ten accounts
 * will be created automatically and the first one will be used as coinbase.
 */
public class KernelRule extends TemporaryFolder {

    private int p2pPort;
    private int apiPort;

    private List<Key> keys;
    private Key coinbase;
    private Genesis genesis;

    private String password;
    private KernelMock kernel;

    private LeveldbFactory dbFactory;

    public KernelRule(int p2pPort, int apiPort) {
        super();

        this.p2pPort = p2pPort;
        this.apiPort = apiPort;

        this.keys = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            keys.add(new Key());
        }
        this.coinbase = keys.get(0);
    }

    public void setGenesis(Genesis genesis) {
        this.genesis = genesis;
    }

    @Override
    protected void before() throws Throwable {
        create();

        // generate random password
        this.password = Hex.encode(Bytes.random(12));

        // config
        Config config = mockConfig(p2pPort, apiPort);

        // genesis
        if (genesis == null) {
            genesis = Genesis.load(config.network());
        }

        // wallet
        Wallet wallet = new Wallet(new File(getRoot(), "wallet.data"), config.network());
        wallet.unlock(password);
        wallet.setHdSeed(Bytes.random(32));
        for (Key key : keys) {
            wallet.addAccount(key);
        }
        wallet.flush();

        // kernel
        this.kernel = new KernelMock(config, genesis, wallet, coinbase);
        this.kernel.setPendingManager(mock(PendingManager.class));
    }

    @Override
    protected void after() {
        kernel.stop();
        delete();
    }

    protected Config mockConfig(int p2pPort, int apiPort) {
        Config config = spy(new DevnetConfig(getRoot().getAbsolutePath()));

        when(config.p2pDeclaredIp()).thenReturn(Optional.of("127.0.0.1"));
        when(config.p2pListenIp()).thenReturn("127.0.0.1");
        when(config.p2pListenPort()).thenReturn(p2pPort);
        when(config.apiListenIp()).thenReturn("127.0.0.1");
        when(config.apiListenPort()).thenReturn(apiPort);
        when(config.apiEnabled()).thenReturn(true);
        when(config.apiUsername()).thenReturn("username");
        when(config.apiPassword()).thenReturn("password");

        return config;
    }

    /**
     * Returns the password.
     *
     * @return
     */
    public String getPassword() {
        return password;
    }

    public List<Key> getKeys() {
        return keys;
    }

    public Key getCoinbase() {
        return coinbase;
    }

    /**
     * Returns the kernel.
     *
     * @return
     */
    public KernelMock getKernel() {
        return kernel;
    }

    /**
     * Speed up the consensus.
     */
    public void speedUpConsensus() throws IOException {
        Config config = kernel.getConfig();

        // speed up consensus
        when(config.bftNewHeightTimeout()).thenReturn(1000L);
        when(config.bftProposeTimeout()).thenReturn(1000L);
        when(config.bftValidateTimeout()).thenReturn(1000L);
        when(config.bftPreCommitTimeout()).thenReturn(1000L);
        when(config.bftCommitTimeout()).thenReturn(1000L);
        when(config.bftFinalizeTimeout()).thenReturn(1000L);
    }

    /**
     * Opens the database.
     */
    public void openBlockchain() {
        dbFactory = new LeveldbFactory(kernel.getConfig().databaseDir());
        BlockchainFactory blockchainFactory = new BlockchainFactory(kernel.getConfig(), genesis, dbFactory);
        Blockchain chain = blockchainFactory.getBlockchainInstance();
        kernel.setBlockchain(chain);
    }

    /**
     * Closes the database.
     */
    public void closeBlockchain() {
        dbFactory.close();
    }

    /**
     * Helper method to create a testing block.
     *
     * @param txs
     *            list of transaction
     * @param lastBlock
     *            last block header
     * @return created block
     */
    public Block createBlock(List<Transaction> txs, BlockHeader lastBlock) {
        List<TransactionResult> res = txs.stream().map(tx -> new TransactionResult()).collect(Collectors.toList());

        long number;
        byte[] prevHash;
        if (lastBlock == null) {
            number = getKernel().getBlockchain().getLatestBlock().getNumber() + 1;
            prevHash = getKernel().getBlockchain().getLatestBlock().getHash();
        } else {
            number = lastBlock.getNumber() + 1;
            prevHash = lastBlock.getHash();
        }
        Key key = new Key();
        byte[] coinbase = key.toAddress();
        long timestamp = TimeUtil.currentTimeMillis();
        byte[] transactionsRoot = MerkleUtil.computeTransactionsRoot(txs);
        byte[] resultsRoot = MerkleUtil.computeResultsRoot(res);
        byte[] stateRoot = Bytes.EMPTY_HASH;
        byte[] data = {};

        BlockHeader header = new BlockHeader(
                number,
                coinbase,
                prevHash,
                timestamp,
                transactionsRoot,
                resultsRoot,
                stateRoot,
                data);

        return new Block(header, txs, res);
    }

    public Block createBlock(List<Transaction> txs) {
        return createBlock(txs, null);
    }
}
