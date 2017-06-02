/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.scm.protocolPB;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.ipc.ProtobufHelper;
import org.apache.hadoop.ipc.ProtocolTranslator;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ozone.protocol.proto.ScmBlockLocationProtocolProtos
    .AllocateScmBlockRequestProto;
import org.apache.hadoop.ozone.protocol.proto.ScmBlockLocationProtocolProtos
    .AllocateScmBlockResponseProto;
import org.apache.hadoop.ozone.protocol.proto.ScmBlockLocationProtocolProtos
    .DeleteScmBlocksRequestProto;
import org.apache.hadoop.ozone.protocol.proto.ScmBlockLocationProtocolProtos
    .DeleteScmBlocksResponseProto;
import org.apache.hadoop.ozone.protocol.proto.ScmBlockLocationProtocolProtos
    .DeleteScmBlockResult;
import org.apache.hadoop.ozone.protocol.proto.ScmBlockLocationProtocolProtos
    .GetScmBlockLocationsRequestProto;
import org.apache.hadoop.ozone.protocol.proto.ScmBlockLocationProtocolProtos
    .GetScmBlockLocationsResponseProto;
import org.apache.hadoop.ozone.protocol.proto.ScmBlockLocationProtocolProtos
    .ScmLocatedBlockProto;
import org.apache.hadoop.scm.container.common.helpers.AllocatedBlock;
import org.apache.hadoop.scm.container.common.helpers.DeleteBlockResult;
import org.apache.hadoop.scm.container.common.helpers.Pipeline;
import org.apache.hadoop.scm.protocol.ScmBlockLocationProtocol;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * This class is the client-side translator to translate the requests made on
 * the {@link ScmBlockLocationProtocol} interface to the RPC server
 * implementing {@link ScmBlockLocationProtocolPB}.
 */
@InterfaceAudience.Private
public final class ScmBlockLocationProtocolClientSideTranslatorPB
    implements ScmBlockLocationProtocol, ProtocolTranslator, Closeable {

  /**
   * RpcController is not used and hence is set to null.
   */
  private static final RpcController NULL_RPC_CONTROLLER = null;

  private final ScmBlockLocationProtocolPB rpcProxy;

  /**
   * Creates a new StorageContainerLocationProtocolClientSideTranslatorPB.
   *
   * @param rpcProxy {@link StorageContainerLocationProtocolPB} RPC proxy
   */
  public ScmBlockLocationProtocolClientSideTranslatorPB(
      ScmBlockLocationProtocolPB rpcProxy) {
    this.rpcProxy = rpcProxy;
  }

  /**
   * Find the set of nodes to read/write a block, as
   * identified by the block key.  This method supports batch lookup by
   * passing multiple keys.
   *
   * @param keys batch of block keys to find
   * @return allocated blocks for each block key
   * @throws IOException if there is any failure
   */
  @Override
  public Set<AllocatedBlock> getBlockLocations(Set<String> keys)
      throws IOException {
    GetScmBlockLocationsRequestProto.Builder req =
        GetScmBlockLocationsRequestProto.newBuilder();
    for (String key : keys) {
      req.addKeys(key);
    }
    final GetScmBlockLocationsResponseProto resp;
    try {
      resp = rpcProxy.getScmBlockLocations(NULL_RPC_CONTROLLER,
          req.build());
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
    Set<AllocatedBlock> locatedBlocks =
        Sets.newLinkedHashSetWithExpectedSize(resp.getLocatedBlocksCount());
    for (ScmLocatedBlockProto locatedBlock : resp.getLocatedBlocksList()) {
      locatedBlocks.add(new AllocatedBlock.Builder()
          .setKey(locatedBlock.getKey())
          .setPipeline(Pipeline.getFromProtoBuf(locatedBlock.getPipeline()))
          .build());
    }
    return locatedBlocks;
  }

  /**
   * Asks SCM where a block should be allocated. SCM responds with the
   * set of datanodes that should be used creating this block.
   * @param size - size of the block.
   * @return allocated block accessing info (key, pipeline).
   * @throws IOException
   */
  @Override
  public AllocatedBlock allocateBlock(long size) throws IOException {
    Preconditions.checkArgument(size > 0,
        "block size must be greater than 0");

    AllocateScmBlockRequestProto request = AllocateScmBlockRequestProto
        .newBuilder().setSize(size).build();
    final AllocateScmBlockResponseProto response;
    try {
      response = rpcProxy.allocateScmBlock(NULL_RPC_CONTROLLER, request);
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
    if (response.getErrorCode() !=
        AllocateScmBlockResponseProto.Error.success) {
      throw new IOException(response.hasErrorMessage() ?
          response.getErrorMessage() : "Allocate block failed.");
    }
    AllocatedBlock.Builder builder = new AllocatedBlock.Builder()
        .setKey(response.getKey())
        .setPipeline(Pipeline.getFromProtoBuf(response.getPipeline()))
        .setShouldCreateContainer(response.getCreateContainer());
    return builder.build();
  }

  /**
   * Delete the set of keys specified.
   *
   * @param keys batch of block keys to delete.
   * @return list of block deletion results.
   * @throws IOException if there is any failure.
   *
   */
  @Override
  public List<DeleteBlockResult> deleteBlocks(Set<String> keys)
      throws IOException {
    Preconditions.checkArgument(keys == null || keys.isEmpty(),
        "keys to be deleted cannot be null or empty");
    DeleteScmBlocksRequestProto request = DeleteScmBlocksRequestProto
        .newBuilder()
        .addAllKeys(keys)
        .build();
    final DeleteScmBlocksResponseProto resp;
    try {
      resp = rpcProxy.deleteScmBlocks(NULL_RPC_CONTROLLER, request);
    } catch (ServiceException e) {
      throw ProtobufHelper.getRemoteException(e);
    }
    List<DeleteBlockResult> results = new ArrayList(resp.getResultsCount());
    for (DeleteScmBlockResult result : resp.getResultsList()) {
      results.add(new DeleteBlockResult(result.getKey(), result.getResult()));
    }
    return results;
  }

  @Override
  public Object getUnderlyingProxyObject() {
    return rpcProxy;
  }

  @Override
  public void close() {
    RPC.stopProxy(rpcProxy);
  }
}