/**
 * Copyright (c) 2017-2018 The Semux Developers
 *
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 */
package org.semux.core;

import static org.semux.core.Amount.Unit.NANO_SEM;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.semux.Network;
import org.semux.crypto.Hex;
import org.semux.crypto.Key;
import org.semux.util.ByteArray;
import org.semux.util.ByteArray.ByteArrayKeyDeserializer;
import org.semux.util.Bytes;
import org.semux.util.SystemUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public class Genesis extends Block {

    private static final Logger logger = LoggerFactory.getLogger(Genesis.class);

    @JsonDeserialize(keyUsing = ByteArrayKeyDeserializer.class)
    private final Map<ByteArray, Premine> premines;

    private final Map<String, Delegate> delegates;

    private final Map<String, Object> config;

    /**
     * Creates a {@link Genesis} block instance.
     *
     * @param header
     * @param premines
     * @param delegates
     * @param config
     */
    public Genesis(BlockHeader header, Map<ByteArray, Premine> premines, Map<String, Delegate> delegates,
            Map<String, Object> config) {
        super(header, Collections.emptyList(), Collections.emptyList());

        this.premines = premines;
        this.delegates = delegates;
        this.config = config;
    }

    @JsonCreator
    public static Genesis jsonCreator(
            @JsonProperty("number") long number,
            @JsonProperty("coinbase") String coinbase,
            @JsonProperty("parentHash") String parentHash,
            @JsonProperty("timestamp") long timestamp,
            @JsonProperty("data") String data,
            @JsonProperty("premine") List<Premine> premineList,
            @JsonProperty("delegates") Map<String, Delegate> delegates,
            @JsonProperty("config") Map<String, Object> config) {

        // load premines
        Map<ByteArray, Premine> premineMap = new HashMap<>();
        for (Premine premine : premineList) {
            premineMap.put(new ByteArray(premine.getAddress()), premine);
        }

        // load block header
        BlockHeader header = new BlockHeader(number, Hex.decode0x(coinbase), Hex.decode0x(parentHash), timestamp,
                Bytes.EMPTY_HASH, Bytes.EMPTY_HASH, Bytes.EMPTY_HASH, Bytes.of(data));

        return new Genesis(header, premineMap, delegates, config);
    }

    /**
     * Loads the genesis file.
     *
     * @return
     */
    public static Genesis load(Network network) {
        try {
            InputStream in = Genesis.class.getResourceAsStream("/genesis/" + network.label() + ".json");

            if (in != null) {
                return new ObjectMapper().readValue(in, Genesis.class);
            }
        } catch (IOException e) {
            logger.error("Failed to load genesis file", e);
        }

        SystemUtil.exitAsync(SystemUtil.Code.FAILED_TO_LOAD_GENESIS);
        return null;
    }

    /**
     * Get premine.
     *
     * @return
     */
    public Map<ByteArray, Premine> getPremines() {
        return premines;
    }

    /**
     * Get delegates.
     *
     * @return
     */
    public Map<String, Delegate> getDelegates() {
        return delegates;
    }

    /**
     * Get genesis configurations.
     *
     * @return
     */
    public Map<String, Object> getConfig() {
        return config;
    }

    public static class Premine {
        private final byte[] address;
        private final byte[] Abyte;
        private final Amount amount;
        private final String note;

        public Premine(byte[] address, byte[] Abyte, Amount amount, String note) {
            this.address = address;
            this.Abyte = Abyte;
            this.amount = amount;
            this.note = note;
        }

        @JsonCreator
        public Premine(@JsonProperty("address") String address, @JsonProperty("Abyte") String Abyte,
                @JsonProperty("amount") long amount,
                @JsonProperty("note") String note) {
            this(Hex.decode0x(address), Hex.decode0x(Abyte), NANO_SEM.of(amount), note);
            assert (Arrays.equals(this.address, Key.Address.fromAbyte(this.Abyte)));
        }

        public byte[] getAddress() {
            return address;
        }

        public byte[] getAbyte() {
            return Abyte;
        }

        public Amount getAmount() {
            return amount;
        }

        public String getNote() {
            return note;
        }
    }

    public static class Delegate {
        private final byte[] address;
        private final byte[] Abyte;

        public Delegate(byte[] address, byte[] Abyte) {
            assert (address.length == Key.ADDRESS_LEN);
            assert (Abyte.length == 32);
            this.address = address;
            this.Abyte = Abyte;
        }

        @JsonCreator
        public Delegate(@JsonProperty("address") String address, @JsonProperty("Abyte") String Abyte) {
            this(Hex.decode0x(address), Hex.decode0x(Abyte));
            assert (Arrays.equals(this.address, Key.Address.fromAbyte(this.Abyte)));
        }

        public byte[] getAddress() {
            return address;
        }

        public byte[] getAbyte() {
            return Abyte;
        }
    }
}
