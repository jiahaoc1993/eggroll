/*
 * Copyright (c) 2019 - now, Eggroll Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */

package com.webank.eggroll.core.nodemanager

import com.webank.eggroll.core.client.NodeManagerClient
import com.webank.eggroll.core.command.{CommandRouter, CommandService}
import com.webank.eggroll.core.constant.{DeployConfKeys, NodeManagerCommands, SessionConfKeys}
import com.webank.eggroll.core.meta.{ErProcessor, ErProcessorBatch, ErSessionMeta}
import com.webank.eggroll.core.session.{RuntimeErConf, StaticErConf}
import com.webank.eggroll.core.transfer.TransferService
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.junit.{Before, Test}

class TestNodeManager {
  private var nodeManagerClient: NodeManagerClient = _
  private var sessionMeta: ErSessionMeta = _
  @Before
  def setup(): Unit = {
    CommandRouter.register(serviceName = NodeManagerCommands.getOrCreateEggsServiceName,
      serviceParamTypes = Array(classOf[ErSessionMeta]),
      serviceResultTypes = Array(classOf[ErProcessorBatch]),
      routeToClass = classOf[NodeManager],
      routeToMethodName = NodeManagerCommands.getOrCreateEggs)

    CommandRouter.register(serviceName = NodeManagerCommands.getOrCreateRollsServiceName,
      serviceParamTypes = Array(classOf[ErSessionMeta]),
      serviceResultTypes = Array(classOf[ErProcessorBatch]),
      routeToClass = classOf[NodeManager],
      routeToMethodName = NodeManagerCommands.getOrCreateRolls)

    CommandRouter.register(serviceName = NodeManagerCommands.heartbeatServiceName,
      serviceParamTypes = Array(classOf[ErProcessor]),
      serviceResultTypes = Array(classOf[ErProcessor]),
      routeToClass = classOf[NodeManager],
      routeToMethodName = NodeManagerCommands.heartbeat)

    val port = 9394
    val clusterManager = NettyServerBuilder
      .forPort(port)
      .addService(new CommandService)
      .build()

    val server: Server = clusterManager.start()
    nodeManagerClient = new NodeManagerClient()

    StaticErConf.setPort(port)

  }

  @Test
  def startAsService(): Unit = {
    println("node manager started")
    Thread.sleep(10000000)
  }

  @Test
  def testGetOrCreateServicer(): Unit = {
    println("hello")

    val result = RollManager.getOrCreate(sessionMeta)
    println(result)
  }

  @Test
  def testGetOrCreateServicerWithClient(): Unit = {
    val result = nodeManagerClient.getOrCreateRolls(sessionMeta)

    print(result)
  }

  @Test
  def testGetOrCreateProcessorBatch(): Unit = {
    val result = EggManager.getOrCreate(sessionMeta)
    print(result)
  }

  @Test
  def testGetOrCreateProcessorBatchWithClient(): Unit = {
    val result = nodeManagerClient.getOrCreateEggs(sessionMeta)
    print(result)
  }
}
