/**
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
package org.apache.hadoop.hbase.procedure;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.errorhandling.ForeignException;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.zookeeper.KeeperException;

import com.google.protobuf.InvalidProtocolBufferException;

/**
 * ZooKeeper based {@link ProcedureCoordinatorRpcs} for a {@link ProcedureCoordinator}
 * 基于 ZK 实现的 ProcedureCoordinator
 */
@InterfaceAudience.Private
public class ZKProcedureCoordinatorRpcs implements ProcedureCoordinatorRpcs {
  public static final Log LOG = LogFactory.getLog(ZKProcedureCoordinatorRpcs.class);
  private ZKProcedureUtil zkProc = null;
  protected ProcedureCoordinator coordinator = null;  // if started this should be non-null

  ZooKeeperWatcher watcher;
  String procedureType;
  String coordName;

  /**
   * @param watcher zookeeper watcher. Owned by <tt>this</tt> and closed via {@link #close()}
   * @param procedureClass procedure type name is a category for when there are multiple kinds of
   *    procedures.-- this becomes a znode so be aware of the naming restrictions
   * @param coordName name of the node running the coordinator
   * @throws KeeperException if an unexpected zk error occurs
   */
  public ZKProcedureCoordinatorRpcs(ZooKeeperWatcher watcher,
      String procedureClass, String coordName) throws KeeperException {
    this.watcher = watcher;
    this.procedureType = procedureClass;
    this.coordName = coordName;
  }

  /**
   * The "acquire" phase.  The coordinator creates a new procType/acquired/ znode dir. If znodes
   * appear, first acquire to relevant listener or sets watch waiting for notification of
   * the acquire node
   *
   * @param proc the Procedure
   * @param info data to be stored in the acquire node
   * @param nodeNames children of the acquire phase
   * @throws IOException if any failure occurs.
   */
  @Override
  final public void sendGlobalBarrierAcquire(Procedure proc, byte[] info, List<String> nodeNames)
      throws IOException, IllegalArgumentException {
    String procName = proc.getName();
    // start watching for the abort node
    String abortNode = zkProc.getAbortZNode(procName);
    try {
      // 检查/hbase/pt/abort/{procedureName}是否存在，存在则表明放弃procedureName这个procedure。
      // check to see if the abort node already exists
      if (ZKUtil.watchAndCheckExists(zkProc.getWatcher(), abortNode)) {
        abort(abortNode);
      }
      // If we get an abort node watch triggered here, we'll go complete creating the acquired
      // znode but then handle the acquire znode and bail out
    } catch (KeeperException e) {
      LOG.error("Failed to watch abort", e);
      throw new IOException("Failed while watching abort node:" + abortNode, e);
    }

    // node: /hbase/pt/acquired/{procedureName}
    // create the acquire barrier
    String acquire = zkProc.getAcquiredBarrierNode(procName);
    LOG.debug("Creating acquire znode:" + acquire);
    try {
      // notify all the procedure listeners to look for the acquire node
      byte[] data = ProtobufUtil.prependPBMagic(info);
      // 在zk上创建/hbase/pt/acquired/{procedureName}节点
      // 后面在说subprocedure会提到，subprocedure会监控这个节点判断是不是一个新的procedure启动了，一旦监控到这个节点参与者就进入prepare阶段
      ZKUtil.createWithParents(zkProc.getWatcher(), acquire, data);
      // loop through all the children of the acquire phase and watch for them
      for (String node : nodeNames) {
        // znode : /hbase/pt/acquired/{procedureName}/{memberName}
        String znode = ZKUtil.joinZNode(acquire, node);
        LOG.debug("Watching for acquire node:" + znode);
        // 检查/hbase/pt/acquired/{procedureName}/{memberName}是否存在，一旦存在表明memberName这个参与者完成prepare。
        if (ZKUtil.watchAndCheckExists(zkProc.getWatcher(), znode)) {
          //通知一个member完成prepare
          coordinator.memberAcquiredBarrier(procName, node);
        }
      }
    } catch (KeeperException e) {
      throw new IOException("Failed while creating acquire node:" + acquire, e);
    }
  }

  @Override
  public void sendGlobalBarrierReached(Procedure proc, List<String> nodeNames) throws IOException {
    String procName = proc.getName();
    String reachedNode = zkProc.getReachedBarrierNode(procName);
    LOG.debug("Creating reached barrier zk node:" + reachedNode);
    try {
      // create the reached znode and watch for the reached znodes
      ZKUtil.createWithParents(zkProc.getWatcher(), reachedNode);
      // loop through all the children of the acquire phase and watch for them
      for (String node : nodeNames) {
        String znode = ZKUtil.joinZNode(reachedNode, node);
        if (ZKUtil.watchAndCheckExists(zkProc.getWatcher(), znode)) {
          byte[] dataFromMember = ZKUtil.getData(zkProc.getWatcher(), znode);
          // ProtobufUtil.isPBMagicPrefix will check null
          if (dataFromMember != null && dataFromMember.length > 0) {
            if (!ProtobufUtil.isPBMagicPrefix(dataFromMember)) {
              throw new IOException(
                "Failed to get data from finished node or data is illegally formatted: "
                    + znode);
            } else {
              dataFromMember = Arrays.copyOfRange(dataFromMember, ProtobufUtil.lengthOfPBMagic(),
                dataFromMember.length);
              coordinator.memberFinishedBarrier(procName, node, dataFromMember);
            }
          } else {
            coordinator.memberFinishedBarrier(procName, node, dataFromMember);
          }
        }
      }
    } catch (KeeperException e) {
      throw new IOException("Failed while creating reached node:" + reachedNode, e);
    } catch (InterruptedException e) {
      throw new InterruptedIOException("Interrupted while creating reached node:" + reachedNode);
    }
  }


  /**
   * Delete znodes that are no longer in use.
   */
  @Override
  final public void resetMembers(Procedure proc) throws IOException {
    String procName = proc.getName();
    boolean stillGettingNotifications = false;
    do {
      try {
        LOG.debug("Attempting to clean out zk node for op:" + procName);
        zkProc.clearZNodes(procName);
        stillGettingNotifications = false;
      } catch (KeeperException.NotEmptyException e) {
        // recursive delete isn't transactional (yet) so we need to deal with cases where we get
        // children trickling in
        stillGettingNotifications = true;
      } catch (KeeperException e) {
        throw new IOException("Failed to complete reset procedure " + procName, e);
      }
    } while (stillGettingNotifications);
  }

  /**
   * Start monitoring znodes in ZK - subclass hook to start monitoring znodes they are about.
   * 开始监视 ZK 上的节点。
   * @return true if succeed, false if encountered initialization errors.
   */
  final public boolean start(final ProcedureCoordinator coordinator) {
    if (this.coordinator != null) {
      throw new IllegalStateException(
        "ZKProcedureCoordinator already started and already has listener installed");
    }
    this.coordinator = coordinator;

    try {
        /**
        假设procedureType是pt， ZKProcedureUtil构造函数会在zk上创建下面三个node：
          1. acquiredZnode : /hbase/pt/acquired
          2. reachedZnode: /hbase/pt/reached
          3. abortZnode: /hbase/pt/abort
        所以基于zk的实现的2PC是通过node的改变来通知所有参与者当前处在哪一个阶段。
      */ 
      this.zkProc = new ZKProcedureUtil(watcher, procedureType) {
        // nodeCreated方法会在zk上新的node创建时回调
        @Override
        public void nodeCreated(String path) {
          //判断一下node的路径是不是/hbase/pt,不是的话表示node改变和当前procedure没关系
          if (!isInProcedurePath(path)) return;
          LOG.debug("Node created: " + path);
          logZKTree(this.baseZNode);
          // 判断新建的node是不是符合路径/hbase/pt/acquired/{procedureName}/{memberName}. 
          // 关于procedureName，每一个procedure调用都会有一个新的名称.  memberName即参与者名称。
          // 这个路径的创建表明有一个参与者完成prepare阶段。后面讲到参与者部分时会提到参与者完成prepare后会创建这个路径。
          if (isAcquiredPathNode(path)) {
            // 通知一下一个参与者完成prepare，毕竟Procedure 还阻塞在acquiredBarrierLatch上等待参与者都完成prepare阶段。
            // node wasn't present when we created the watch so zk event triggers acquire
            coordinator.memberAcquiredBarrier(ZKUtil.getNodeName(ZKUtil.getParent(path)),
              ZKUtil.getNodeName(path));
            // 和上面的if类似，判断新建的node符不符合/hbase/pt/reached/{procedureName}/{memberName}. 符合表明一个参与者完成commit阶段。
          } else if (isReachedPathNode(path)) {
            // node was absent when we created the watch so zk event triggers the finished barrier.

            // TODO Nothing enforces that acquire and reached znodes from showing up in wrong order.
            String procName = ZKUtil.getNodeName(ZKUtil.getParent(path));
            String member = ZKUtil.getNodeName(path);
            // get the data from the procedure member
            try {
              // 前面说到Subprocedure # insideBarrier会有返回值，这个返回值被设置成node的data，此处解析出返回值。
              byte[] dataFromMember = ZKUtil.getData(watcher, path);
              // ProtobufUtil.isPBMagicPrefix will check null
              if (dataFromMember != null && dataFromMember.length > 0) {
                if (!ProtobufUtil.isPBMagicPrefix(dataFromMember)) {
                  ForeignException ee = new ForeignException(coordName,
                    "Failed to get data from finished node or data is illegally formatted:"
                        + path);
                  coordinator.abortProcedure(procName, ee);
                } else {
                  dataFromMember = Arrays.copyOfRange(dataFromMember, ProtobufUtil.lengthOfPBMagic(),
                    dataFromMember.length);
                  LOG.debug("Finished data from procedure '" + procName
                    + "' member '" + member + "': " + new String(dataFromMember));
                  // 通知一个参与者完成commit，此时Procedure阻塞在releasedBarrierLatch等待所有参与者完成commit。
                  coordinator.memberFinishedBarrier(procName, member, dataFromMember);
                }
              } else {
                coordinator.memberFinishedBarrier(procName, member, dataFromMember);
              }
            } catch (KeeperException e) {
              ForeignException ee = new ForeignException(coordName, e);
              coordinator.abortProcedure(procName, ee);
            } catch (InterruptedException e) {
              ForeignException ee = new ForeignException(coordName, e);
              coordinator.abortProcedure(procName, ee);
            }
          } else if (isAbortPathNode(path)) {
            abort(path);
          } else {
            LOG.debug("Ignoring created notification for node:" + path);
          }
        }
      };
      zkProc.clearChildZNodes();
    } catch (KeeperException e) {
      LOG.error("Unable to start the ZK-based Procedure Coordinator rpcs.", e);
      return false;
    }

    LOG.debug("Starting the controller for procedure member:" + coordName);
    return true;
  }

  /**
   * This is the abort message being sent by the coordinator to member
   *
   * TODO this code isn't actually used but can be used to issue a cancellation from the
   * coordinator.
   */
  @Override
  final public void sendAbortToMembers(Procedure proc, ForeignException ee) {
    String procName = proc.getName();
    LOG.debug("Aborting procedure '" + procName + "' in zk");
    String procAbortNode = zkProc.getAbortZNode(procName);
    try {
      LOG.debug("Creating abort znode:" + procAbortNode);
      String source = (ee.getSource() == null) ? coordName : ee.getSource();
      byte[] errorInfo = ProtobufUtil.prependPBMagic(ForeignException.serialize(source, ee));
      // first create the znode for the procedure
      ZKUtil.createAndFailSilent(zkProc.getWatcher(), procAbortNode, errorInfo);
      LOG.debug("Finished creating abort node:" + procAbortNode);
    } catch (KeeperException e) {
      // possible that we get this error for the procedure if we already reset the zk state, but in
      // that case we should still get an error for that procedure anyways
      zkProc.logZKTree(zkProc.baseZNode);
      coordinator.rpcConnectionFailure("Failed to post zk node:" + procAbortNode
          + " to abort procedure '" + procName + "'", new IOException(e));
    }
  }

  /**
   * Receive a notification and propagate it to the local coordinator
   * @param abortNode full znode path to the failed procedure information
   */
  protected void abort(String abortNode) {
    String procName = ZKUtil.getNodeName(abortNode);
    ForeignException ee = null;
    try {
      byte[] data = ZKUtil.getData(zkProc.getWatcher(), abortNode);
      if (data == null || data.length == 0) {
        // ignore
        return;
      } else if (!ProtobufUtil.isPBMagicPrefix(data)) {
        LOG.warn("Got an error notification for op:" + abortNode
            + " but we can't read the information. Killing the procedure.");
        // we got a remote exception, but we can't describe it
        ee = new ForeignException(coordName, "Data in abort node is illegally formatted.  ignoring content.");
      } else {

        data = Arrays.copyOfRange(data, ProtobufUtil.lengthOfPBMagic(), data.length);
        ee = ForeignException.deserialize(data);
      }
    } catch (InvalidProtocolBufferException e) {
      LOG.warn("Got an error notification for op:" + abortNode
          + " but we can't read the information. Killing the procedure.");
      // we got a remote exception, but we can't describe it
      ee = new ForeignException(coordName, e);
    } catch (KeeperException e) {
      coordinator.rpcConnectionFailure("Failed to get data for abort node:" + abortNode
          + zkProc.getAbortZnode(), new IOException(e));
    } catch (InterruptedException e) {
      coordinator.rpcConnectionFailure("Failed to get data for abort node:" + abortNode
          + zkProc.getAbortZnode(), new IOException(e));
      Thread.currentThread().interrupt();
    }
    coordinator.abortProcedure(procName, ee);
  }

  @Override
  final public void close() throws IOException {
    zkProc.close();
  }

  /**
   * Used in testing
   */
  final ZKProcedureUtil getZkProcedureUtil() {
    return zkProc;
  }
}
