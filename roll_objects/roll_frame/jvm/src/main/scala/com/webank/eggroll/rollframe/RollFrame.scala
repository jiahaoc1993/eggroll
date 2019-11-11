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

package com.webank.eggroll.rollframe

import java.io._
import java.net.URI
import java.util.Random
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicInteger

import com.google.protobuf.{ByteString, Message => PbMessage}
import com.webank.eggroll.core.command.{CommandRouter, CommandService, CommandURI}
import com.webank.eggroll.core.constant.StringConstants
import com.webank.eggroll.core.meta
import com.webank.eggroll.core.meta._
import com.webank.eggroll.core.schedule.{BaseTaskPlan, JobRunner, ListScheduler}
import com.webank.eggroll.core.serdes.DefaultScalaSerdes
import com.webank.eggroll.format.{FrameBatch, FrameDB, _}
import io.grpc.ServerBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag


// TODO: always close in finally

//import com.webank.eggroll.rollframe.MessageSerializer._

/*
// TODO: Use a dag to express program with base plan like reading/writing/scatter/broadcast etc.
// A program packages all jobs/tasks to optimize together
class ExecuteProgram {

  private val plans:ListBuffer[TaskPlan] = ListBuffer[TaskPlan]()
  private val functorSerdes = ScalaFunctorSerdes
  private def newTaskId(jobId: String) = jobId + "-task" + Math.abs(new Random().nextLong())

  private def newJobId() = "job" + Math.abs(new Random().nextLong())

  def addPlan(plan: TaskPlan):ExecuteProgram = {
    plans.append(plan)
    this
  }

  def build(inputs: List[RfStore], clusterManager: ClusterManager,
            outputs: List[RfStore]): List[EndpointCommand] = {
    // only one input&output&plan supported now
    val input = inputs.head
    val plan = plans.head
    val jobId = newJobId()
    val output = outputs.head.copy(partitions = null)
    val partitionRoutes = clusterManager.getPreferredServer(input)
    val job = RfJob(jobId, partitionRoutes.size, List(output))
    def getTask(taskType:String, part: RfPartition, functors: List[(String, Any)]): RfTask = {
      val funcs = functors.map { case (name, f) =>
        RfFunctor(name, f match {
          case bytes:Array[Byte] => bytes
          case _ => functorSerdes.serialize(f)
        })
      }
      part.store = input.copy(partitions = null)
      RfTask(newTaskId(jobId),taskType, funcs, List(part), job)
    }

    input.partitions.flatMap{ part =>
      val rfTasks = plan match {
        case task: AggregateBatchTask =>
          val zero = if(task.broadcastZero || task.byColumn) Array[Byte]() else FrameUtil.toBytes(task.zeroValue)
          var tasks = getTask("AggregateBatchTask", part,
            List(("zeroValue", zero), ("seqOp", task.seqOp), ("combOp", task.combOp), ("byColumn", task.byColumn))
          ) :: Nil
          val zeroPath = "broadcast:" + jobId
          // Write to current server node's store for self using and transfer
          FrameDB.cache(zeroPath)
            .writeAll(Iterator(task.zeroValue))
          if(part.id == 0 && task.broadcastZero) {
            // TODO: scatter zero value when aggregating byColumn
            tasks ::= getTask("BroadcastTask", part, List(("broadcast", zeroPath)))
          }
          tasks
        case task: MapBatchTask => getTask("MapBatchTask",part, List(("mapBatches", task.mapper))) :: Nil
        case task: ReduceBatchTask => getTask("ReduceBatchTask",part, List(("mapBatches", task.reducer))) :: Nil
        case _ => throw new UnsupportedOperationException("unsupported task")
      }
      // TODO: command service should merge the same endpoint's commands to one. And use local invoking instead of network
      rfTasks.map(t => EndpointCommand(
        new URI("/grpc/v1?route=com.webank.eggroll.rollframe.EggFrame.runTask"),
        t.toBytes(), null, partitionRoutes(part.id)))
    }
  }
}

trait TaskPlan
case class AggregateBatchTask(zeroValue:FrameBatch, seqOp: (FrameBatch, FrameBatch) => FrameBatch,
                    combOp: (FrameBatch, FrameBatch) => FrameBatch, byColumn:Boolean = false,
                    broadcastZero:Boolean = true) extends TaskPlan
case class MapBatchTask(mapper: FrameBatch => FrameBatch) extends TaskPlan
case class ReduceBatchTask(reducer:(FrameBatch, FrameBatch) => FrameBatch) extends TaskPlan
case class MapPartitionTask(mapper:Iterator[FrameBatch] => Iterator[FrameBatch]) extends TaskPlan
*/


class AggregateBatchTask(uri: CommandURI, job: ErJob) extends BaseTaskPlan(uri, job)
class MapBatchTask(uri: CommandURI, job: ErJob) extends BaseTaskPlan(uri, job)
class ReduceBatchTask(uri: CommandURI, job: ErJob) extends BaseTaskPlan(uri, job)
class MapPartitionTask(uri: CommandURI, job: ErJob) extends BaseTaskPlan(uri, job)



trait RollFrame

// create a instance when start a new job
// TODO: reuse ErJob generate and separate client mode and cluster mode
class RollFrameClientMode(val store: ErStore) extends RollFrame {

  val serdes = new DefaultScalaSerdes

  val rollFrameService = new RollFrameService

  def mapBatch(f: FrameBatch => FrameBatch, output: ErStore = null): RollFrameClientMode = {
    val jobType = RollFrame.mapBatch
    val job = ErJob(id = jobType,
      // name = s"${RollFrame.rollFrame}.${RollFrame.mapBatch}",
      name = EggFrame.mapBatchTask,
      inputs = List(store),
      outputs = List(if (output == null) store.fork(postfix = jobType) else output),
      functors = List(ErFunctor(name = RollFrame.mapBatch, body = serdes.serialize(f))))

    new RollFrameClientMode(rollFrameService.mapBatches(job))
  }

  def reduce(f: (FrameBatch, FrameBatch) => FrameBatch, output: ErStore = null): RollFrameClientMode = {
    val jobType = RollFrame.reduce
    val job = ErJob(id = RollFrame.reduce,
      name = EggFrame.reduceTask,
      inputs = List(store),
      outputs = List(if (output == null) store.fork(postfix = jobType) else output),
      functors = List(ErFunctor(name = RollFrame.reduce, body = serdes.serialize(f))))

    new RollFrameClientMode(rollFrameService.reduce(job))
  }

  def aggregate(zeroValue: FrameBatch,
                seqOp: (FrameBatch, FrameBatch) => FrameBatch,
                combOp: (FrameBatch, FrameBatch) => FrameBatch,
                byColumn: Boolean = false,
                broadcastZeroValue: Boolean = false,
                output: ErStore = null): RollFrameClientMode = {
    val jobType = RollFrame.aggregate
    val job = ErJob(id = RollFrame.aggregate,
      name = EggFrame.aggregateBatchTask,
      inputs = List(store),
      outputs = List(if (output == null) store.fork(postfix = jobType) else output),
      // todo: broadcast of zeroValue needs another implementation because there is a gRPC message size limit here
      functors = List(ErFunctor(name = "zeroValue", body = FrameUtils.toBytes(zeroValue)),
        ErFunctor(name = "seqOp", body = serdes.serialize(seqOp)),
        ErFunctor(name = "combOp", body = serdes.serialize(combOp)),
        ErFunctor(name = "byColumn", body = serdes.serialize(byColumn)),
        ErFunctor(name = "broadcastZeroValue", body = serdes.serialize(broadcastZeroValue))))

    new RollFrameClientMode(rollFrameService.aggregate(job))
  }

/*
  def jobBuilder(methodName: String, name: String, inputs: List[ErStore] = List(store), outputs: List[ErStore] = List(), functors: List[Any] = List()): ErJob = {
    ErJob(id = methodName,
      name = s"${RollFrame}.${methodName}",
      inputs = inputs,
      outputs = outputs,
      functors =
  }*/
}

object RollFrame {
  val rollFrame = "RollFrame"
  val eggFrame = "EggFrame"
  val mapBatch = "mapBatch"
  val reduce = "reduce"
  val aggregate = "aggregate"
}

/*
class ExecutorPool {
  private val threadId = new AtomicInteger()
//  private val executorService = Executors.newCachedThreadPool()
  private val executorService = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
    new SynchronousQueue[Runnable],
    new ThreadFactory {
    override def newThread(r: Runnable): Thread = new Thread(r, "eggroll-executor-pool" + threadId.getAndIncrement().toString)
  })



  def submit[T](r: Callable[T]): Future[T] = {
    executorService.submit(r)
  }
  def submit(r: Runnable): Unit = {
    executorService.submit(r)
  }
  val cores:Int = Runtime.getRuntime.availableProcessors()
}

object ExecutorPool {
  private val instance = new ExecutorPool()
  def apply(): ExecutorPool = instance
}


class EggFrame {
  private val rootPath = "/tmp/unittests/RollFrameTests/filedb/"
  private val clusterManager = new ClusterManager
  private val serverNodes = clusterManager.getServerCluster().nodes
  private val transferService = new NioCollectiveTransfer(serverNodes)
  private val executorPool = ExecutorPool()
  private  val rootServer = serverNodes.head
  private val deser = ScalaFunctorSerdes

  private def sliceByColumn(frameBatch: FrameBatch):List[(ServerNode, (Int, Int))] = {
    val columns = frameBatch.rootVectors.length
    val servers = serverNodes.length
    val partSize = (servers + columns - 1) / servers
    (0 until servers).map{ sid =>
      (serverNodes(sid),(sid * partSize, Math.min((sid + 1) * partSize, columns)))
    }.toList
  }
  private def sliceByRow(parts:Int, frameBatch: FrameBatch):List[(Int, Int)] = {
    val rows = frameBatch.rowCount
    val partSize = (parts + rows - 1) / parts
    (0 until parts).map{ sid =>
      (sid * partSize, Math.min((sid + 1) * partSize, rows))
    }.toList
  }

  def runBroadcast(path:String):Unit = {
    val list = FrameDB.cache(path).readAll()
    serverNodes.foreach{server =>
      if(!server.equals(rootServer)) {
        list.foreach( fb => transferService.send(server.id, path, fb))
      }
    }
  }

  def runMapBatch(task:RfTask, input:Iterator[FrameBatch], output:FrameDB,
                  mapper: FrameBatch => FrameBatch): Unit = {
    // for concurrent writing
    val queuePath = task.taskId + "-doing"
    val queue = FrameDB.queue(queuePath, task.oprands.head.batchSize)
    input.foreach{ store =>
      executorPool.submit(new Runnable {
        override def run(): Unit = {
          queue.append(mapper(store))
        }
      })
    }
    output.writeAll(queue.readAll())
  }

  def runReduceBatch(task:RfTask, input:Iterator[FrameBatch], output:FrameDB,
                     reducer: (FrameBatch, FrameBatch) => FrameBatch, byColumn:Boolean): Unit = {
    if(!input.hasNext) return
    val zero = input.next()
    runAggregateBatch(task, input, output, zero, reducer, (a, _)=> a, byColumn)
  }
  //TODO: allgather = List[(FB)=>FB]
  def runAggregateBatch(task:RfTask, input:Iterator[FrameBatch], output:FrameDB,
                        zeroValue:FrameBatch, seqOp:(FrameBatch, FrameBatch) => FrameBatch,
                        combOp: (FrameBatch, FrameBatch) => FrameBatch, byColumn:Boolean): Unit = {
    val part = task.oprands.head
    val batchSize = part.batchSize
    // TODO: more generally, like repartition?

    // TODO: route
    val localServer = clusterManager.getPreferredServer(part.store)(part.id)
    println(s"runAggregateBatch $localServer ${part.id}")
    var localQueue: FrameDB = null

    val zeroPath = "broadcast:" + task.job.jobId
    val zero: FrameBatch =
      if(zeroValue == null) {
        if(localServer.equals(rootServer))
          FrameDB.cache(zeroPath).readOne()
        else
          FrameDB.queue(zeroPath, 1).readOne()
      } else {
        zeroValue
      }
    // TODO: more generally, like repartition?
    if(batchSize == 1) {
      if(input.hasNext) {
        val fb = input.next()
        val parallel = Math.min(executorPool.cores, fb.rowCount) // split by row to grow parallel
        // for concurrent writing
        localQueue = FrameDB.queue(task.taskId + "-doing", parallel)
        sliceByRow(parallel, fb).foreach{case (from, to) =>
          executorPool.submit(new Callable[Unit] {
            override def call(): Unit = {
              localQueue.append(seqOp(FrameUtil.copy(zero), fb.spliceByRow(from, to)))
            }
          })
        }
      }
    } else {
      val parallel = Math.min(executorPool.cores, batchSize) // reduce zero value copy
      // for concurrent writing
      localQueue = FrameDB.queue(task.taskId + "-doing", parallel)
      var batchIndex = 0
      (0 until parallel).foreach{ i =>
        if(input.hasNext) { // merge to avoid zero copy
          executorPool.submit(new Callable[Unit] {
            override def call(): Unit = {
              val tmpZero = FrameUtil.copy(zero)
              var tmp = seqOp(tmpZero, input.next())
              batchIndex += 1
              while (batchIndex < ((parallel + batchSize - 1) / batchSize) * i && input.hasNext) {
                tmp = seqOp(tmp, input.next())
                batchIndex += 1
              }
              localQueue.append(tmp)
            }
          })
        }
      }
    }

    if(localQueue == null) {
      return
    }

    val resultIterator = localQueue.readAll()
    if(!resultIterator.hasNext) throw new IllegalStateException("empty result")
    var localBatch: FrameBatch = resultIterator.next()
    while (resultIterator.hasNext) {
      localBatch = combOp(localBatch, resultIterator.next())
    }
    val transferQueueSize = task.job.taskSize - 1
    // TODO: check asynchronous call
    if(byColumn) {
      val splicedBatches = sliceByColumn(localBatch)
      // Don't block next receive step
      splicedBatches.foreach{ case (server, (from, to)) =>
        val queuePath ="all2all:" + task.job.jobId + ":" + server.id
        if(!server.equals(localServer)) {
          transferService.send(server.id, queuePath, localBatch.sliceByColumn(from, to))
        }
      }
      splicedBatches.foreach{ case (server, (from, to)) =>
        val queuePath ="all2all:" + task.job.jobId + ":" + server.id
        if(server.equals(localServer)){
          for(tmp <- FrameDB.queue(queuePath, transferQueueSize).readAll()) {
            localBatch = combOp(localBatch, tmp.spareByColumn(localBatch.rootVectors.length, from, to))
          }
        }
      }
    } else {
      val queuePath = "gather:" + task.job.jobId
      if(localServer.equals(rootServer)) {
        for(tmp <- FrameDB.queue(queuePath, transferQueueSize).readAll()) {
          localBatch = combOp(localBatch, tmp)
        }
      } else {
        transferService.send(rootServer.id, queuePath, localBatch)
      }
    }
    output.append(localBatch)
  }

  private def getStorePath(rfStore: RfStore, partId: Int):String = {
    val dir = if(rfStore.path == null || rfStore.path.isEmpty)
      rootPath + "/" + rfStore.namespace + "/" + rfStore.name
    else rfStore.path
    dir + "/" + partId
  }

  def runTask(pbTask: RollFrameGrpc.Task): RollFrameGrpc.TaskResult = {
    val task = pbTask.fromProto()
    val part = task.oprands.head
    val inputStore = part.store
    val outputStore = task.job.outputs.head

    val inputPath = getStorePath(inputStore, part.id)
    val outputPath = getStorePath(outputStore, part.id)
    println(s"runTask, input:$inputPath, output:$outputPath")

    val inputDB = FrameDB(inputStore, part.id)
    val outputDB = FrameDB(outputStore, part.id)

    pbTask.getTaskType match {
      case "BroadcastTask" =>
        runBroadcast(deser.deserialize(task.functors.head.body))
      case "MapBatchTask" =>
        runMapBatch(task, inputDB.readAll(), outputDB, deser.deserialize(task.functors.head.body))
      case "ReduceBatchTask" =>
        val reducer: (FrameBatch, FrameBatch) => FrameBatch = deser.deserialize(task.functors.head.body)
        runReduceBatch(task, inputDB.readAll(), outputDB,reducer,byColumn = false)
      case "AggregateBatchTask" =>
        val zeroBytes = task.functors.head.body
        val zeroValue: FrameBatch = if(zeroBytes.isEmpty) null else FrameUtil.fromBytes(zeroBytes)
        val seqOp: (FrameBatch, FrameBatch) => FrameBatch = deser.deserialize(task.functors(1).body)
        val combOp: (FrameBatch, FrameBatch) => FrameBatch = deser.deserialize(task.functors(2).body)
        val byColumn: Boolean = deser.deserialize(task.functors(3).body)
        runAggregateBatch(task, inputDB.readAll(), outputDB,zeroValue,seqOp, combOp, byColumn)
      case t => throw new UnsupportedOperationException(t)
    }
    outputDB.close()
    inputDB.close()
    RollFrameGrpc.TaskResult.newBuilder().setTaskId(pbTask.getTaskId).build()
  }
}
*/

// TODO: MOCK
class ClusterManager(mode: String = "local") {
  val clusterNode0 = ErServerNode(id = "0", commandEndpoint = ErEndpoint("node1", 20100), dataEndpoint = ErEndpoint("node1", 20200), tag = "boss")
  val clusterNode1 = ErServerNode(id = "1", commandEndpoint = ErEndpoint("node2", 20101), dataEndpoint = ErEndpoint("node2", 20201), tag = "worker")
  val clusterNode2 = ErServerNode(id = "2", commandEndpoint = ErEndpoint("node3", 20102), dataEndpoint = ErEndpoint("node3", 20202), tag = "worker")

  val localNode0 = ErServerNode(id = "0", commandEndpoint = ErEndpoint("127.0.0.1", 20100), dataEndpoint = ErEndpoint("127.0.0.1", 20200), tag = "boss")
  val localNode1 = ErServerNode(id = "1", commandEndpoint = ErEndpoint("127.0.0.1", 20101), dataEndpoint = ErEndpoint("127.0.0.1", 20201), tag = "worker")
  def getServerCluster(clusterId: String = null): ErServerCluster = {
    val cluster = mode match {
      case "cluster" =>
        ErServerCluster(id = clusterId, nodes = List(clusterNode0, clusterNode1, clusterNode2))
      case _ => ErServerCluster(clusterId, List(localNode0, localNode1))
    }
    cluster
  }

  def getRollFrameStore(name: String, namespace: String): ErStore = {
    // TODO:How to get partition num, frameBatch count?
    val storeLocator = ErStoreLocator(
      storeType = StringConstants.FILE,
      namespace = namespace,
      name = name)
    val partitions = mode match {
      case "cluster" => List(
        ErPartition(id = "0", storeLocator = storeLocator, node = clusterNode0),
        ErPartition(id = "1", storeLocator = storeLocator, node = clusterNode1),
        ErPartition(id = "2", storeLocator = storeLocator, node = clusterNode2)
      )
      case _ => List(
        ErPartition(id = "0", storeLocator = storeLocator, node = localNode0),
        ErPartition(id = "1", storeLocator = storeLocator, node = localNode1))
    }
    ErStore(storeLocator = storeLocator, partitions = partitions)
  }

  def getPreferredServer(store: ErStore, clusterId: String = null): Map[Int, ErServerNode] = {
    val nodes = getServerCluster(clusterId).nodes

    nodes.indices.zip(nodes).toMap
  }

  def startServerCluster(clusterId: String = null, nodeId: String): Unit = {
/*
    CommandRouter.register("com.webank.eggroll.rollframe.EggFrame.runTask",
      List(classOf[RollFrameGrpc.Task]), classOf[RollFrameGrpc.TaskResult])
*/

    CommandRouter.register(
      serviceName = "EggFrame.runTask",
      serviceParamTypes = List(classOf[ErTask]),
      serviceReturnTypes = List(classOf[ErStore]),
      routeToClass = classOf[EggFrame],
      routeToMethodName = "runTask")

/*    CommandRouter.register(
      serviceName = s"EggFrame.${RollFrame.mapBatch}",
      serviceParamTypes = List(classOf[ErTask]),
      serviceReturnTypes = List(classOf[ErStore]),
      routeToClass = classOf[EggFrame],
      routeToMethodName = "runTask")*/

    getServerCluster(clusterId).nodes.foreach { server =>
      val idMatch = mode match {
        case "cluster" => server.id == nodeId
        case _ => true
      }
      val commandEndpoint = server.commandEndpoint
      val dataEndpoint = server.dataEndpoint
      if (idMatch) {
        val sb = NettyServerBuilder.forPort(commandEndpoint.port)
        sb.addService(new CommandService).build.start
        println("Start GrpcCommandService...")
        new Thread("transfer-" + dataEndpoint.port) {
          override def run(): Unit = {
            try {
              println(s"Start TransferServer:server.host: ${server.dataEndpoint.host}, transferPost: ${server.dataEndpoint.port}")
              new NioTransferEndpoint().runServer(server.dataEndpoint.host, server.dataEndpoint.port)
            } catch {
              case e: Throwable => e.printStackTrace()
            }
          }
        }.start()
      }
    }
  }
}

object ClusterManager {
  def getOrCreate(): ClusterManager = new ClusterManager
}

/*
trait FunctorSerdes {
  def serialize(func: Any): Array[Byte]

  def deserialize[T](bytes: Array[Byte]): T
}

object ScalaFunctorSerdes extends FunctorSerdes {
  def serialize(func: Any): Array[Byte] = {
    val bo = new ByteArrayOutputStream()
    try {
      new ObjectOutputStream(bo).writeObject(func)
      bo.toByteArray
    } catch {
      case e:Throwable => e.printStackTrace()
        throw e
    }finally {
      bo.close()
    }
  }

  def deserialize[T](bytes: Array[Byte]): T = {
    if(bytes.isEmpty) return null.asInstanceOf[T]
    val bo = new ObjectInputStream(new ByteArrayInputStream(bytes))
    try {
      bo.readObject().asInstanceOf[T]
    } finally {
      bo.close()
    }
  }
}
*/



trait RpcMessage

case class ServerNode(host: String, port: Int, tag: String, id: String = "", transferPort: Int = -1)
  extends RpcMessage {
  override def equals(obj: scala.Any): Boolean = id.equals(obj.asInstanceOf[ServerNode].id)
}

case class ServerCluster(id: String, nodes: List[ServerNode]) extends RpcMessage

case class RfPartition(id: Int, batchSize:Int, var store: RfStore = null) extends RpcMessage

case class RfStore(name: String, namespace: String, partitionSize:Int,
                   var partitions: List[RfPartition] = List[RfPartition](),
                   path: String = null, storeType: String = "file") extends RpcMessage

case class RfJob(jobId: String, taskSize:Int, outputs: List[RfStore] = List[RfStore]()) extends RpcMessage

case class RfFunctor(name: String, body: Array[Byte]) extends RpcMessage

case class RfTask(taskId: String, taskType:String, functors: List[RfFunctor], oprands: List[RfPartition], job: RfJob)
  extends RpcMessage

trait MessageSerializer {
  def toBytes(): Array[Byte]
}

trait MessageDeserializer {
  def fromBytes[T: ClassTag](bytes: Array[Byte]): T
}

trait PbMessageSerializer extends MessageSerializer {
  def toProto[T >: PbMessage](): T

  override def toBytes(): Array[Byte] = toProto().toByteArray // toByteString preferred
  def toByteString(): ByteString = toProto().toByteString
}

trait PbMessageDeserializer extends MessageDeserializer {
  def fromProto[T >: RpcMessage](): T

  def fromBytes[T: ClassTag](bytes: Array[Byte]): T = throw new NotImplementedError()
}

object MessageSerializer {

  implicit class RfPartitionSerializer(rfPartition: RfPartition) extends PbMessageSerializer {
    override def toProto[T >: PbMessage](): RollFrameGrpc.Partition = {
      val b = RollFrameGrpc.Partition.newBuilder().setId(rfPartition.id.toString).setBatchSize(rfPartition.batchSize)
      if (rfPartition.store != null) b.setStore(rfPartition.store.toProto())
      b.build()
    }
  }

  implicit class RfPartitionDeserializer(p: RollFrameGrpc.Partition) extends PbMessageDeserializer {
    override def fromProto[T >: RpcMessage](): RfPartition = RfPartition(p.getId.toInt, p.getBatchSize, p.getStore.fromProto())
  }

  implicit class RfFunctorSerializer(rfFunctor: RfFunctor) extends PbMessageSerializer {
    override def toProto[T >: PbMessage](): RollFrameGrpc.Functor = {
      RollFrameGrpc.Functor.newBuilder().setName(rfFunctor.name).setBody(ByteString.copyFrom(rfFunctor.body)).build()
    }
  }

  implicit class RfFunctorDeserializer(p: RollFrameGrpc.Functor) extends PbMessageDeserializer {
    override def fromProto[T >: RpcMessage](): RfFunctor = {
      RfFunctor(p.getName, p.getBody.toByteArray)
    }
  }

  implicit class RfTaskSerializer(rfTask: RfTask) extends PbMessageSerializer {
    override def toProto[T >: PbMessage](): T = {
      RollFrameGrpc.Task.newBuilder()
        .setTaskType(rfTask.taskType)
        .setJob(rfTask.job.toProto())
        .addAllFunctors(rfTask.functors.map(_.toProto()).asJava)
        .addAllOperands(rfTask.oprands.map(_.toProto()).asJava)
        .setTaskId(rfTask.taskId)
        .build()
    }
  }

  implicit class RfTaskDeserializer(p: RollFrameGrpc.Task) extends PbMessageDeserializer {
    override def fromProto[T >: RpcMessage](): RfTask = {
      RfTask(p.getTaskId,p.getTaskType,
        p.getFunctorsList.asScala.map(_.fromProto()).toList,
        p.getOperandsList.asScala.map(_.fromProto()).toList,
        p.getJob.fromProto()
      )
    }
  }

  implicit class RfJobSerializer(rfJob: RfJob) extends PbMessageSerializer {

    override def toProto[T >: PbMessage](): RollFrameGrpc.Job = {
      RollFrameGrpc.Job.newBuilder().setJobId(rfJob.jobId).setTaskSize(rfJob.taskSize)
        .addAllOutputs(rfJob.outputs.map(_.toProto()).asJava).build()
    }
  }

  implicit class RfJobDeserializer(p: RollFrameGrpc.Job) extends PbMessageDeserializer {
    override def fromProto[T >: RpcMessage](): RfJob =
      RfJob(p.getJobId, p.getTaskSize, p.getOutputsList.asScala.map(_.fromProto()).toList)
  }

  implicit class RfStoreSerializer(rfStore: RfStore) extends PbMessageSerializer {
    override def toProto[T >: PbMessage](): RollFrameGrpc.Store = {
      val b = RollFrameGrpc.Store.newBuilder().setName(rfStore.name)
        .setNamespace(rfStore.namespace).setType(rfStore.storeType).setPartitionSize(rfStore.partitionSize)
      if (rfStore.path != null) b.setPath(rfStore.path)
      b.build()
    }
  }

  implicit class RfStoreDeserializer(p: RollFrameGrpc.Store) extends PbMessageDeserializer {
    override def fromProto[T >: RpcMessage](): RfStore = RfStore(p.getName, p.getNamespace,p.getPartitionSize,
      path = p.getPath, storeType = p.getType)
  }

}