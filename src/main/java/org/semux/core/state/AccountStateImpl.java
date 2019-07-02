/**
 * Copyright (c) 2017-2018 The Semux Developers
 *
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 */
package org.semux.core.state;

import static org.semux.core.Amount.sum;

import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.semux.core.Amount;
import org.semux.db.Database;
import org.semux.util.ByteArray;
import org.semux.util.Bytes;

/**
 * @deprecated only being used for database upgrade from v0, v1 to v2
 *
 *             Account state implementation.
 *
 *             <pre>
 * account DB structure:
 *
 * [0, address] => [account_object]
 * [1, address] => [code]
 * [2, address, storage_key] = [storage_value]
 *             </pre>
 */
public class AccountStateImpl implements Cloneable, AccountState {

    protected static final byte TYPE_ACCOUNT = 0;
    protected static final byte TYPE_CODE = 1;
    protected static final byte TYPE_STORAGE = 2;

    protected Database accountDB;
    protected AccountStateImpl prev;

    /**
     * All updates, or deletes if the value is null.
     */
    protected final Map<ByteArray, byte[]> updates = new ConcurrentHashMap<>();

    /**
     * Create an {@link AccountState} that work directly on a database.
     *
     * @param accountDB
     */
    public AccountStateImpl(Database accountDB) {
        this.accountDB = accountDB;
    }

    /**
     * Create an {@link AccountState} based on a previous AccountState.
     *
     * @param prev
     */
    public AccountStateImpl(AccountStateImpl prev) {
        this.prev = prev;
    }

    @Override
    public AccountV1 getAccount(byte[] address) {
        ByteArray k = getKey(TYPE_ACCOUNT, address);
        Amount noAmount = Amount.ZERO;

        if (updates.containsKey(k)) {
            byte[] v = updates.get(k);
            return v == null ? new AccountV1(address, noAmount, noAmount, 0)
                    : new AccountDecoderV1().decode(address, v);
        } else if (prev != null) {
            return prev.getAccount(address);
        } else {
            byte[] v = accountDB.get(k.getData());
            return v == null ? new AccountV1(address, noAmount, noAmount, 0)
                    : new AccountDecoderV1().decode(address, v);
        }
    }

    @Override
    public long increaseNonce(byte[] address) {
        ByteArray k = getKey(TYPE_ACCOUNT, address);

        AccountV1 acc = getAccount(address);
        long nonce = acc.getNonce() + 1;
        acc.setNonce(nonce);
        updates.put(k, new AccountEncoderV1().encode(acc));
        return nonce;
    }

    @Override
    public void adjustAvailable(byte[] address, Amount delta) {
        ByteArray k = getKey(TYPE_ACCOUNT, address);

        AccountV1 acc = getAccount(address);
        acc.setAvailable(sum(acc.getAvailable(), delta));
        updates.put(k, new AccountEncoderV1().encode(acc));
    }

    @Override
    public void adjustLocked(byte[] address, Amount delta) {
        ByteArray k = getKey(TYPE_ACCOUNT, address);

        AccountV1 acc = getAccount(address);
        acc.setLocked(sum(acc.getLocked(), delta));
        updates.put(k, new AccountEncoderV1().encode(acc));
    }

    @Override
    public byte[] getCode(byte[] address) {
        ByteArray k = getKey(TYPE_CODE, address);

        if (updates.containsKey(k)) {
            return updates.get(k);
        } else if (prev != null) {
            return prev.getCode(address);
        } else {
            return accountDB.get(k.getData());
        }
    }

    @Override
    public void setCode(byte[] address, byte[] code) {
        ByteArray k = getKey(TYPE_CODE, address);
        updates.put(k, code);
    }

    @Override
    public byte[] getStorage(byte[] address, byte[] key) {
        ByteArray k = getStorageKey(address, key);

        if (updates.containsKey(k)) {
            return updates.get(k);
        } else if (prev != null) {
            return prev.getStorage(address, key);
        } else {
            return accountDB.get(k.getData());
        }
    }

    @Override
    public void putStorage(byte[] address, byte[] key, byte[] value) {
        ByteArray storeKey = getStorageKey(address, key);
        updates.put(storeKey, value);
    }

    @Override
    public void removeStorage(byte[] address, byte[] key) {
        ByteArray storeKey = getStorageKey(address, key);
        updates.put(storeKey, null);
    }

    @Override
    public AccountState track() {
        return new AccountStateImpl(this);
    }

    @Override
    public void commit() {
        synchronized (updates) {
            if (prev == null) {
                for (Entry<ByteArray, byte[]> entry : updates.entrySet()) {
                    if (entry.getValue() == null) {
                        accountDB.delete(entry.getKey().getData());
                    } else {
                        accountDB.put(entry.getKey().getData(), entry.getValue());
                    }
                }
            } else {
                for (Entry<ByteArray, byte[]> e : updates.entrySet()) {
                    prev.updates.put(e.getKey(), e.getValue());
                }
            }

            updates.clear();
        }
    }

    @Override
    public void rollback() {
        updates.clear();
    }

    @Override
    public boolean exists(byte[] address) {
        ByteArray k = getKey(TYPE_ACCOUNT, address);

        if (updates.containsKey(k)) {
            return true;
        } else if (prev != null) {
            return prev.exists(address);
        } else {
            byte[] v = accountDB.get(k.getData());
            return v != null;
        }
    }

    @Override
    public long setNonce(byte[] address, long nonce) {
        ByteArray k = getKey(TYPE_ACCOUNT, address);

        AccountV1 acc = getAccount(address);
        acc.setNonce(nonce);
        updates.put(k, new AccountEncoderV1().encode(acc));
        return nonce;
    }

    @Override
    public AccountState clone() {
        AccountStateImpl clone = new AccountStateImpl(accountDB);
        clone.prev = prev;
        clone.updates.putAll(updates);

        return clone;
    }

    protected ByteArray getKey(byte type, byte[] address) {
        return ByteArray.of(Bytes.merge(type, address));
    }

    protected ByteArray getStorageKey(byte[] address, byte[] key) {
        byte[] buf = new byte[1 + address.length + key.length];
        buf[0] = TYPE_STORAGE;
        System.arraycopy(address, 0, buf, 1, address.length);
        System.arraycopy(key, 0, buf, 1 + address.length, key.length);

        return ByteArray.of(buf);
    }
}
