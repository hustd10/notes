/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.hadoop.hbase.client;


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HBaseIOException;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.RegionLocations;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.ipc.PayloadCarryingRpcController;
import org.apache.hadoop.hbase.ipc.RpcControllerFactory;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.RequestConverter;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;

import com.google.protobuf.ServiceException;


import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Caller that goes to replica if the primary region does no answer within a configurable
 * timeout. If the timeout is reached, it calls all the secondary replicas, and returns
 * the first answer. If the answer comes from one of the secondary replica, it will
 * be marked as stale.
 * 如果 primary region server 在指定的超时时间内没有恢复，则转向副本的region发送请求。
 * primary region server超时后，向所有的副本发送请求，并返回第一个答复的。如果答复来自其中一个
 * secondary 副本。
 */
@InterfaceAudience.Private
public class RpcRetryingCallerWithReadReplicas {
  static final Log LOG = LogFactory.getLog(RpcRetryingCallerWithReadReplicas.class);

  protected final ExecutorService pool;
  protected final ClusterConnection cConnection;
  protected final Configuration conf;
  protected final Get get;
  protected final TableName tableName;
  protected final int timeBeforeReplicas;
  private final int callTimeout;
  private final int retries;
  private final RpcControllerFactory rpcControllerFactory;
  private final RpcRetryingCallerFactory rpcRetryingCallerFactory;

  public RpcRetryingCallerWithReadReplicas(
      RpcControllerFactory rpcControllerFactory, TableName tableName,
      ClusterConnection cConnection, final Get get,
      ExecutorService pool, int retries, int callTimeout,
      int timeBeforeReplicas) {
    this.rpcControllerFactory = rpcControllerFactory;
    this.tableName = tableName;
    this.cConnection = cConnection;
    this.conf = cConnection.getConfiguration();
    this.get = get;
    this.pool = pool;
    this.retries = retries;
    this.callTimeout = callTimeout;
    this.timeBeforeReplicas = timeBeforeReplicas;
    this.rpcRetryingCallerFactory = new RpcRetryingCallerFactory(conf);
  }

  /**
   * A RegionServerCallable that takes into account the replicas, i.e.
   * - the call can be on any replica
   * - we need to stop retrying when the call is completed
   * - we can be interrupted
   */
  class ReplicaRegionServerCallable extends RegionServerCallable<Result> implements Cancellable {
    final int id;
    private final PayloadCarryingRpcController controller;

    public ReplicaRegionServerCallable(int id, HRegionLocation location) {
      super(RpcRetryingCallerWithReadReplicas.this.cConnection,
          RpcRetryingCallerWithReadReplicas.this.tableName, get.getRow());
      this.id = id;
      this.location = location;
      this.controller = rpcControllerFactory.newController();
      controller.setPriority(tableName);
    }

    @Override
    public void cancel() {
      controller.startCancel();
    }

    /**
     * Two responsibilities
     * - if the call is already completed (by another replica) stops the retries.
     * - set the location to the right region, depending on the replica.
     */
    @Override
    public void prepare(final boolean reload) throws IOException {
      if (controller.isCanceled()) return;

      if (Thread.interrupted()) {
        throw new InterruptedIOException();
      }

      if (reload || location == null) {
        RegionLocations rl = getRegionLocations(false, id, cConnection, tableName, get.getRow());
        location = id < rl.size() ? rl.getRegionLocation(id) : null;
      }

      if (location == null || location.getServerName() == null) {
        // With this exception, there will be a retry. The location can be null for a replica
        //  when the table is created or after a split.
        throw new HBaseIOException("There is no location for replica id #" + id);
      }

      ServerName dest = location.getServerName();

      setStub(cConnection.getClient(dest));
    }

    @Override
    public Result call(int callTimeout) throws Exception {
      if (controller.isCanceled()) return null;

      if (Thread.interrupted()) {
        throw new InterruptedIOException();
      }

      byte[] reg = location.getRegionInfo().getRegionName();

      ClientProtos.GetRequest request =
          RequestConverter.buildGetRequest(reg, get);
      controller.setCallTimeout(callTimeout);

      try {
        ClientProtos.GetResponse response = getStub().get(controller, request);
        if (response == null) {
          return null;
        }
        return ProtobufUtil.toResult(response.getResult());
      } catch (ServiceException se) {
        throw ProtobufUtil.getRemoteException(se);
      }
    }

    @Override
    public boolean isCancelled() {
      return controller.isCanceled();
    }
  }

  /**
   * Algo:
   * - we put the query into the execution pool.
   * - after x ms, if we don't have a result, we add the queries for the secondary replicas
   * - we take the first answer
   * - when done, we cancel what's left. Cancelling means:
   * - removing from the pool if the actual call was not started
   * - interrupting the call if it has started
   * Client side, we need to take into account
   * - a call is not executed immediately after being put into the pool
   * - a call is a thread. Let's not multiply the number of thread by the number of replicas.
   * Server side, if we can cancel when it's still in the handler pool, it's much better, as a call
   * can take some i/o.
   * <p/>
   * Globally, the number of retries, timeout and so on still applies, but it's per replica,
   * not global. We continue until all retries are done, or all timeouts are exceeded.
   */
  public synchronized Result call()
      throws DoNotRetryIOException, InterruptedIOException, RetriesExhaustedException {
    boolean isTargetReplicaSpecified = (get.getReplicaId() >= 0);

    RegionLocations rl = getRegionLocations(true, (isTargetReplicaSpecified ? get.getReplicaId()
        : RegionReplicaUtil.DEFAULT_REPLICA_ID), cConnection, tableName, get.getRow());
    ResultBoundedCompletionService<Result> cs =
        new ResultBoundedCompletionService<Result>(this.rpcRetryingCallerFactory, pool, rl.size());

    if(isTargetReplicaSpecified) {
      addCallsForReplica(cs, rl, get.getReplicaId(), get.getReplicaId());
    } else {
      addCallsForReplica(cs, rl, 0, 0);
      try {
        // wait for the timeout to see whether the primary responds back
        Future<Result> f = cs.poll(timeBeforeReplicas, TimeUnit.MICROSECONDS); // Yes, microseconds
        if (f != null) {
          return f.get(); //great we got a response
        }
      } catch (ExecutionException e) {
        throwEnrichedException(e, retries);
      } catch (CancellationException e) {
        throw new InterruptedIOException();
      } catch (InterruptedException e) {
        throw new InterruptedIOException();
      }

      // submit call for the all of the secondaries at once
      addCallsForReplica(cs, rl, 1, rl.size() - 1);
    }

    try {
      try {
        Future<Result> f = cs.take();
        return f.get();
      } catch (ExecutionException e) {
        throwEnrichedException(e, retries);
      }
    } catch (CancellationException e) {
      throw new InterruptedIOException();
    } catch (InterruptedException e) {
      throw new InterruptedIOException();
    } finally {
      // We get there because we were interrupted or because one or more of the
      // calls succeeded or failed. In all case, we stop all our tasks.
      cs.cancelAll();
    }

    return null; // unreachable
  }

  /**
   * Extract the real exception from the ExecutionException, and throws what makes more
   * sense.
   */
  static void throwEnrichedException(ExecutionException e, int retries)
      throws RetriesExhaustedException, DoNotRetryIOException {
    Throwable t = e.getCause();
    assert t != null; // That's what ExecutionException is about: holding an exception

    if (t instanceof RetriesExhaustedException) {
      throw (RetriesExhaustedException) t;
    }

    if (t instanceof DoNotRetryIOException) {
      throw (DoNotRetryIOException) t;
    }

    RetriesExhaustedException.ThrowableWithExtraContext qt =
        new RetriesExhaustedException.ThrowableWithExtraContext(t,
            EnvironmentEdgeManager.currentTime(), null);

    List<RetriesExhaustedException.ThrowableWithExtraContext> exceptions =
        Collections.singletonList(qt);

    throw new RetriesExhaustedException(retries, exceptions);
  }

  /**
   * Creates the calls and submit them
   *
   * @param cs  - the completion service to use for submitting
   * @param rl  - the region locations
   * @param min - the id of the first replica, inclusive
   * @param max - the id of the last replica, inclusive.
   */
  private void addCallsForReplica(ResultBoundedCompletionService<Result> cs,
                                 RegionLocations rl, int min, int max) {
    for (int id = min; id <= max; id++) {
      HRegionLocation hrl = rl.getRegionLocation(id);
      ReplicaRegionServerCallable callOnReplica = new ReplicaRegionServerCallable(id, hrl);
      cs.submit(callOnReplica, callTimeout, id);
    }
  }

  // 根据表名、副本号、行row来定位Region的位置
  // useCache：是否使用本地缓存
  // replicaId：副本编号
  //
  // row：所在行
  static RegionLocations getRegionLocations(boolean useCache, int replicaId,
                 ClusterConnection cConnection, TableName tableName, byte[] row)
      throws RetriesExhaustedException, DoNotRetryIOException, InterruptedIOException {

    RegionLocations rl;
    try {
      if (!useCache) {
        rl = cConnection.relocateRegion(tableName, row, replicaId);
      } else {
        rl = cConnection.locateRegion(tableName, row, useCache, true, replicaId);
      }
    } catch (DoNotRetryIOException e) {
      throw e;
    } catch (RetriesExhaustedException e) {
      throw e;
    } catch (InterruptedIOException e) {
      throw e;
    } catch (IOException e) {
      throw new RetriesExhaustedException("Can't get the location", e);
    }
    if (rl == null) {
      throw new RetriesExhaustedException("Can't get the locations");
    }

    return rl;
  }
}
