/*
 * Copyright Consensys Software Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package net.consensys.linea.zktracer.module.rlpAddr;

import static net.consensys.linea.zktracer.module.rlputils.Pattern.bitDecomposition;
import static net.consensys.linea.zktracer.module.rlputils.Pattern.byteCounting;
import static net.consensys.linea.zktracer.module.rlputils.Pattern.padToGivenSizeWithLeftZero;
import static net.consensys.linea.zktracer.module.rlputils.Pattern.padToGivenSizeWithRightZero;
import static net.consensys.linea.zktracer.types.Conversions.bigIntegerToBytes;
import static org.hyperledger.besu.crypto.Hash.keccak256;
import static org.hyperledger.besu.evm.internal.Words.clampedToLong;

import java.math.BigInteger;

import net.consensys.linea.zktracer.container.stacked.list.StackedList;
import net.consensys.linea.zktracer.module.Module;
import net.consensys.linea.zktracer.module.ModuleTrace;
import net.consensys.linea.zktracer.module.rlputils.BitDecOutput;
import net.consensys.linea.zktracer.module.rlputils.ByteCountAndPowerOutput;
import net.consensys.linea.zktracer.opcode.OpCode;
import net.consensys.linea.zktracer.types.UnsignedByte;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.worldstate.WorldView;

public class RlpAddr implements Module {
  private static final Bytes CREATE2_SHIFT = bigIntegerToBytes(BigInteger.valueOf(0xff));
  private static final Bytes INT_SHORT = bigIntegerToBytes(BigInteger.valueOf(0x80));
  private static final int LIST_SHORT = 0xc0;
  private static final int LLARGE = 16;

  private final StackedList<RlpAddrChunk> chunkList = new StackedList<>();

  @Override
  public String jsonKey() {
    return "rlpAddr";
  }

  @Override
  public void enterTransaction() {
    this.chunkList.enter();
  }

  @Override
  public void popTransaction() {
    this.chunkList.pop();
  }

  @Override
  public void traceStartTx(WorldView world, Transaction tx) {
    if (tx.getTo().isEmpty()) {
      RlpAddrChunk chunk = new RlpAddrChunk(OpCode.CREATE, tx.getNonce(), tx.getSender());
      this.chunkList.add(chunk);
    }
  }

  @Override
  public void tracePreOpcode(MessageFrame frame) {
    OpCode opcode = OpCode.of(frame.getCurrentOperation().getOpcode());
    switch (opcode) {
      case CREATE -> {
        final Address currentAddress = frame.getRecipientAddress();
        RlpAddrChunk chunk =
            new RlpAddrChunk(
                OpCode.CREATE,
                frame
                    .getWorldUpdater()
                    .get(currentAddress)
                    .getNonce(), // TODO: use the method done by @Lorenzo in OOB module
                currentAddress);
        this.chunkList.add(chunk);
      }
      case CREATE2 -> {
        final long offset = clampedToLong(frame.getStackItem(1));
        final long length = clampedToLong(frame.getStackItem(2));
        final Bytes initCode = frame.shadowReadMemory(offset, length);
        final Bytes32 salt = Bytes32.leftPad(frame.getStackItem(3));
        final Bytes32 hash = keccak256(initCode);

        RlpAddrChunk chunk =
            new RlpAddrChunk(OpCode.CREATE2, frame.getRecipientAddress(), salt, hash);
        this.chunkList.add(chunk);
      }
    }
  }

  private void traceCreate2(
      int stamp, Address address, Bytes32 salt, Bytes32 keccak, Trace.TraceBuilder trace) {
    final Address deployementAddress =
        Address.extract(keccak256(Bytes.concatenate(CREATE2_SHIFT, address, salt, keccak)));

    for (int ct = 0; ct < 6; ct++) {
      trace
          .stamp(BigInteger.valueOf(stamp))
          .recipe(BigInteger.valueOf(2))
          .recipe1(false)
          .recipe2(true)
          .depAddrHi(deployementAddress.slice(0, 4).toUnsignedBigInteger())
          .depAddrLo(deployementAddress.slice(4, LLARGE).toUnsignedBigInteger())
          .addrHi(address.slice(0, 4).toUnsignedBigInteger())
          .addrLo(address.slice(4, LLARGE).toUnsignedBigInteger())
          .saltHi(salt.slice(0, LLARGE).toUnsignedBigInteger())
          .saltLo(salt.slice(LLARGE, LLARGE).toUnsignedBigInteger())
          .kecHi(keccak.slice(0, LLARGE).toUnsignedBigInteger())
          .kecLo(keccak.slice(LLARGE, LLARGE).toUnsignedBigInteger())
          .lc(true)
          .index(BigInteger.valueOf(ct))
          .counter(BigInteger.valueOf(ct));

      switch (ct) {
        case 0 -> {
          trace.limb(
              padToGivenSizeWithRightZero(
                      Bytes.concatenate(CREATE2_SHIFT, address.slice(0, 4)), LLARGE)
                  .toUnsignedBigInteger());
          trace.nBytes(BigInteger.valueOf(5));
        }
        case 1 -> trace
            .limb(address.slice(4, LLARGE).toUnsignedBigInteger())
            .nBytes(BigInteger.valueOf(LLARGE));
        case 2 -> trace
            .limb(salt.slice(0, LLARGE).toUnsignedBigInteger())
            .nBytes(BigInteger.valueOf(LLARGE));
        case 3 -> trace
            .limb(salt.slice(LLARGE, LLARGE).toUnsignedBigInteger())
            .nBytes(BigInteger.valueOf(LLARGE));
        case 4 -> trace
            .limb(keccak.slice(0, LLARGE).toUnsignedBigInteger())
            .nBytes(BigInteger.valueOf(LLARGE));
        case 5 -> trace
            .limb(keccak.slice(LLARGE, LLARGE).toUnsignedBigInteger())
            .nBytes(BigInteger.valueOf(LLARGE));
      }

      // Columns unused for Recipe2
      trace
          .nonce(BigInteger.ZERO)
          .byte1(UnsignedByte.ZERO)
          .acc(BigInteger.ZERO)
          .accBytesize(BigInteger.ZERO)
          .power(BigInteger.ZERO)
          .bit1(false)
          .bitAcc(UnsignedByte.ZERO)
          .tinyNonZeroNonce(false);

      trace.validateRow();
    }
  }

  private void traceCreate(int stamp, BigInteger nonce, Address addr, Trace.TraceBuilder trace) {
    final int RECIPE1_CT_MAX = 8;

    Bytes nonceShifted = padToGivenSizeWithLeftZero(bigIntegerToBytes(nonce), RECIPE1_CT_MAX);
    Boolean tinyNonZeroNonce = true;
    if (nonce.compareTo(BigInteger.ZERO) == 0 || nonce.compareTo(BigInteger.valueOf(128)) >= 0) {
      tinyNonZeroNonce = false;
    }
    // Compute the BYTESIZE and POWER columns
    int nonceByteSize = bigIntegerToBytes(nonce).size();
    if (nonce.equals(BigInteger.ZERO)) {
      nonceByteSize = 0;
    }
    ByteCountAndPowerOutput byteCounting = byteCounting(nonceByteSize, RECIPE1_CT_MAX);

    // Compute the bit decomposition of the last input's byte
    final byte lastByte = nonceShifted.get(RECIPE1_CT_MAX - 1);
    BitDecOutput bitDecomposition = bitDecomposition(0xff & lastByte, RECIPE1_CT_MAX);

    int size_rlp_nonce = nonceByteSize;
    if (!tinyNonZeroNonce) {
      size_rlp_nonce += 1;
    }

    // Bytes RLP(nonce)
    Bytes rlpNonce;
    if (nonce.compareTo(BigInteger.ZERO) == 0) {
      rlpNonce = INT_SHORT;
    } else {
      if (tinyNonZeroNonce) {
        rlpNonce = bigIntegerToBytes(nonce);
      } else {
        rlpNonce =
            Bytes.concatenate(
                bigIntegerToBytes(
                    BigInteger.valueOf(
                        128 + byteCounting.accByteSizeList().get(RECIPE1_CT_MAX - 1))),
                bigIntegerToBytes(nonce));
      }
    }

    // Keccak of the Rlp to get the deployment address
    final Address deployementAddress = Address.contractAddress(addr, nonce.longValueExact());

    for (int ct = 0; ct < 8; ct++) {
      trace
          .stamp(BigInteger.valueOf(stamp))
          .recipe(BigInteger.ONE)
          .recipe1(true)
          .recipe2(false)
          .addrHi(addr.slice(0, 4).toUnsignedBigInteger())
          .addrLo(addr.slice(4, LLARGE).toUnsignedBigInteger())
          .depAddrHi(deployementAddress.slice(0, 4).toUnsignedBigInteger())
          .depAddrLo(deployementAddress.slice(4, LLARGE).toUnsignedBigInteger())
          .nonce(nonce)
          .counter(BigInteger.valueOf(ct))
          .byte1(UnsignedByte.of(nonceShifted.get(ct)))
          .acc(nonceShifted.slice(0, ct + 1).toUnsignedBigInteger())
          .accBytesize(BigInteger.valueOf(byteCounting.accByteSizeList().get(ct)))
          .power(byteCounting.powerList().get(ct).divide(BigInteger.valueOf(256)))
          .bit1(bitDecomposition.bitDecList().get(ct))
          .bitAcc(UnsignedByte.of(bitDecomposition.bitAccList().get(ct)))
          .tinyNonZeroNonce(tinyNonZeroNonce);

      switch (ct) {
        case 0, 1, 2, 3 -> trace
            .lc(false)
            .limb(BigInteger.ZERO)
            .nBytes(BigInteger.ZERO)
            .index(BigInteger.ZERO);
        case 4 -> trace
            .lc(true)
            .limb(
                padToGivenSizeWithRightZero(
                        bigIntegerToBytes(
                            BigInteger.valueOf(LIST_SHORT)
                                .add(BigInteger.valueOf(21))
                                .add(BigInteger.valueOf(size_rlp_nonce))),
                        LLARGE)
                    .toUnsignedBigInteger())
            .nBytes(BigInteger.ONE)
            .index(BigInteger.ZERO);
        case 5 -> trace
            .lc(true)
            .limb(
                padToGivenSizeWithRightZero(
                        Bytes.concatenate(
                            bigIntegerToBytes(BigInteger.valueOf(148)), addr.slice(0, 4)),
                        LLARGE)
                    .toUnsignedBigInteger())
            .nBytes(BigInteger.valueOf(5))
            .index(BigInteger.ONE);
        case 6 -> trace
            .lc(true)
            .limb(addr.slice(4, LLARGE).toUnsignedBigInteger())
            .nBytes(BigInteger.valueOf(16))
            .index(BigInteger.valueOf(2));
        case 7 -> trace
            .lc(true)
            .limb(padToGivenSizeWithRightZero(rlpNonce, LLARGE).toUnsignedBigInteger())
            .nBytes(BigInteger.valueOf(size_rlp_nonce))
            .index(BigInteger.valueOf(3));
      }

      // Column not used fo recipe 1:
      trace
          .saltHi(BigInteger.ZERO)
          .saltLo(BigInteger.ZERO)
          .kecHi(BigInteger.ZERO)
          .kecLo(BigInteger.ZERO)
          .validateRow();
    }
  }

  private void traceChunks(RlpAddrChunk chunk, int stamp, Trace.TraceBuilder trace) {
    if (chunk.opCode().equals(OpCode.CREATE)) {
      traceCreate(stamp, BigInteger.valueOf(chunk.nonce().get()), chunk.address(), trace);
    } else {
      traceCreate2(stamp, chunk.address(), chunk.salt().get(), chunk.keccak().get(), trace);
    }
  }

  public int chunkRowSize(RlpAddrChunk chunk) {
    if (chunk.opCode().equals(OpCode.CREATE)) {
      return 8;
    } else {
      return 6;
    }
  }

  @Override
  public int lineCount() {
    int traceRowSize = 0;
    for (RlpAddrChunk chunk : this.chunkList) {
      traceRowSize += chunkRowSize(chunk);
    }
    return traceRowSize;
  }

  @Override
  public ModuleTrace commit() {
    final Trace.TraceBuilder trace = Trace.builder(this.lineCount());

    for (int i = 0; i < this.chunkList.size(); i++) {
      traceChunks(chunkList.get(i), i + 1, trace);
    }
    return new RlpAddrTrace(trace.build());
  }
}
