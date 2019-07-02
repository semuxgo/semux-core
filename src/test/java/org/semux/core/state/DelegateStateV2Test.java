/**
 * Copyright (c) 2017-2018 The Semux Developers
 *
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 */
package org.semux.core.state;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.semux.core.Amount.Unit.NANO_SEM;
import static org.semux.core.Amount.Unit.SEM;
import static org.semux.core.Amount.ZERO;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.semux.Network;
import org.semux.config.Constants;
import org.semux.config.DevnetConfig;
import org.semux.core.Amount;
import org.semux.core.Blockchain;
import org.semux.core.BlockchainFactory;
import org.semux.core.Genesis;
import org.semux.crypto.Key;
import org.semux.rules.TemporaryDatabaseRule;
import org.semux.util.ByteArray;
import org.semux.util.Bytes;

public class DelegateStateV2Test {

    private Blockchain chain;
    private DelegateState ds;
    Map<String, Genesis.Delegate> genesisDelegates;

    @Rule
    public TemporaryDatabaseRule temporaryDBFactory = new TemporaryDatabaseRule();

    @Before
    public void init() {
        chain = (new BlockchainFactory(new DevnetConfig(Constants.DEFAULT_DATA_DIR), Genesis.load(Network.DEVNET),
                temporaryDBFactory)).getBlockchainInstance();
        ds = chain.getDelegateState();
        genesisDelegates = chain.getGenesis().getDelegates();
    }

    @Test
    public void testAtGenesis() {
        genesisDelegates.forEach((key, value) -> {
            Delegate d = ds.getDelegateByAddress(value.getAddress());
            assertNotNull(d);
            assertArrayEquals(value.getAddress(), d.getAddress());
            assertArrayEquals(Bytes.of(key), d.getName());
            assertEquals(ZERO, d.getVotes());
        });

        assertEquals(genesisDelegates.size(), ds.getDelegates().size());
        assertEquals(genesisDelegates.size(), chain.getValidators().size());
    }

    @Test
    public void testVoteWithoutRegistration() {
        Key delegate = new Key();
        Key voter = new Key();

        assertFalse(ds.vote(voter.toAddress(), delegate.toAddress(), NANO_SEM.of(1)));
    }

    @Test
    public void testVoteOneDelegate() {
        Key delegateKey = new Key();
        byte[] delegateName = Bytes.of("delegate");

        byte[] voter = new Key().toAddress();

        assertTrue(ds.register(delegateKey.getAbyte(), delegateName));
        for (int i = 0; i < 1000; i++) {
            assertTrue(ds.vote(voter, delegateKey.toAddress(), NANO_SEM.of(1000)));
        }

        List<Delegate> list = ds.getDelegates();
        assertEquals(genesisDelegates.size() + 1, list.size());

        assertArrayEquals(delegateKey.toAddress(), list.get(0).getAddress());
        assertArrayEquals(delegateName, list.get(0).getName());
        assertEquals(NANO_SEM.of(1000 * 1000), list.get(0).getVotes());
    }

    @Test
    public void testMultipleDelegates() {
        Key delegateKey = null;
        byte[] delegateName = null;

        byte[] voter = new Key().toAddress();

        for (int i = 0; i < 200; i++) {
            delegateKey = new Key();
            delegateName = Bytes.of("delegate" + i);
            assertTrue(ds.register(delegateKey.getAbyte(), delegateName));
            assertTrue(ds.vote(voter, delegateKey.toAddress(), NANO_SEM.of(i)));
        }

        List<Delegate> list = ds.getDelegates();
        assertEquals(genesisDelegates.size() + 200, list.size());

        assertArrayEquals(delegateKey.toAddress(), list.get(0).getAddress());
        assertArrayEquals(delegateName, list.get(0).getName());
        assertEquals(NANO_SEM.of(200 - 1), list.get(0).getVotes());
    }

    @Test
    public void testUnvote() {
        byte[] voter = new Key().toAddress();
        Key delegateKey = new Key();
        Amount value = SEM.of(2);

        ds.register(delegateKey.getAbyte(), Bytes.of("test"));
        assertFalse(ds.unvote(voter, delegateKey.toAddress(), value));
        ds.vote(voter, delegateKey.toAddress(), value);
        assertTrue(ds.vote(voter, delegateKey.toAddress(), value));
    }

    @Test
    public void testGetVote() {
        byte[] voter = new Key().toAddress();
        Key delegateKey = new Key();
        Amount value = SEM.of(2);

        ds.register(delegateKey.getAbyte(), Bytes.of("test"));
        assertTrue(ds.vote(voter, delegateKey.toAddress(), value));
        assertEquals(value, ds.getVote(voter, delegateKey.toAddress()));
        assertTrue(ds.vote(voter, delegateKey.toAddress(), value));
        assertEquals(SEM.of(4), ds.getVote(voter, delegateKey.toAddress()));
        commit();
        assertEquals(SEM.of(4), ds.getVote(voter, delegateKey.toAddress()));
    }

    @Test
    public void testGetVotes() {
        Key delegateKey = new Key();
        Key voterKey1 = new Key();
        Amount value1 = SEM.of(1);
        Key voterKey2 = new Key();
        Amount value2 = SEM.of(2);

        ds.register(delegateKey.getAbyte(), Bytes.of("test"));
        assertTrue(ds.vote(voterKey1.toAddress(), delegateKey.toAddress(), value1));
        assertTrue(ds.vote(voterKey2.toAddress(), delegateKey.toAddress(), value2));
        commit();

        Map<ByteArray, Amount> votes = ds.getVotes(delegateKey.toAddress());
        assertEquals(value1, votes.get(new ByteArray(voterKey1.toAddress())));
        assertEquals(value2, votes.get(new ByteArray(voterKey2.toAddress())));
    }

    @After
    public void rollback() {
        ds.rollback();
    }

    private void commit() {
        ds.commit();
        chain.commit();
    }
}
