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

package net.consensys.linea.zktracer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.consensys.linea.zktracer.module.Module;
import net.consensys.linea.zktracer.module.hub.Hub;
import net.consensys.linea.zktracer.opcode.OpCodes;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.PendingTransaction;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.gascalculator.LondonGasCalculator;
import org.hyperledger.besu.evm.log.Log;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.worldstate.WorldView;
import org.hyperledger.besu.plugin.data.BlockBody;
import org.hyperledger.besu.plugin.data.BlockHeader;
import org.hyperledger.besu.plugin.data.ProcessableBlockHeader;

@RequiredArgsConstructor
public class ZkTracer implements ZkBlockAwareOperationTracer {
  /** The {@link GasCalculator} used in this version of the arithmetization */
  public static final GasCalculator gasCalculator = new LondonGasCalculator();

  private final ZkTraceBuilder zkTraceBuilder = new ZkTraceBuilder();
  @Getter private final Hub hub;
  private Hash hashOfLastTransactionTraced = Hash.EMPTY;

  public ZkTracer() {
    // Load opcodes configured in src/main/resources/opcodes.yml.
    OpCodes.load();

    this.hub = new Hub();
  }

  public ZkTrace getTrace() {
    for (Module module : this.hub.getModulesToTrace()) {
      zkTraceBuilder.addTrace(module);
    }
    return zkTraceBuilder.build();
  }

  @Override
  public void traceStartConflation(final long numBlocksInConflation) {
    hub.traceStartConflation(numBlocksInConflation);
  }

  @Override
  public void traceEndConflation() {
    this.hub.traceEndConflation();
  }

  @Override
  public String getJsonTrace() {
    return getTrace().toJson();
  }

  @Override
  public void traceStartBlock(final ProcessableBlockHeader processableBlockHeader) {
    this.hub.traceStartBlock(processableBlockHeader);
  }

  @Override
  public void traceStartBlock(final BlockHeader blockHeader, final BlockBody blockBody) {
    this.hub.traceStartBlock(blockHeader);
  }

  @Override
  public void traceEndBlock(final BlockHeader blockHeader, final BlockBody blockBody) {
    this.hub.traceEndBlock(blockHeader, blockBody);
  }

  @Override
  public void traceStartTransaction(WorldView worldView, Transaction transaction) {
    hashOfLastTransactionTraced = transaction.getHash();
    this.hub.traceStartTx(worldView, transaction);
  }

  @Override
  public void traceEndTransaction(
      WorldView worldView,
      Transaction tx,
      boolean status,
      Bytes output,
      List<Log> logs,
      long gasUsed,
      long timeNs) {
    this.hub.traceEndTx(worldView, tx, status, output, logs, gasUsed);
  }

  @Override
  public void tracePreExecution(final MessageFrame frame) {
    if (frame.getCode().getSize() > 0) {
      this.hub.tracePreOpcode(frame);
    }
  }

  @Override
  public void tracePostExecution(MessageFrame frame, Operation.OperationResult operationResult) {
    if (frame.getCode().getSize() > 0) {
      this.hub.tracePostExecution(frame, operationResult);
    }
  }

  @Override
  public void traceContextEnter(MessageFrame frame) {
    // We only want to trigger on creation of new contexts, not on re-entry in
    // existing contexts
    if (frame.getState() == MessageFrame.State.NOT_STARTED) {
      this.hub.traceContextEnter(frame);
    }
  }

  @Override
  public void traceContextReEnter(MessageFrame frame) {
    this.hub.traceContextReEnter(frame);
  }

  @Override
  public void traceContextExit(MessageFrame frame) {
    this.hub.traceContextExit(frame);
  }

  /** When called, erase all tracing related to the last included transaction. */
  public void popTransaction(final PendingTransaction pendingTransaction) {
    if (hashOfLastTransactionTraced.equals(pendingTransaction.getTransaction().getHash())) {
      hub.popTransaction();
    }
  }

  public Map<String, Integer> getModulesLineCount() {
    final HashMap<String, Integer> modulesLineCount = new HashMap<>();
    hub.getModulesToTrace()
        .forEach(m -> modulesLineCount.put(m.getClass().getSimpleName(), m.lineCount()));
    return modulesLineCount;
  }
}
