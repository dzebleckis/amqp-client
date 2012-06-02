package com.aphelia.amqp

import org.scalatest.matchers.ShouldMatchers
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{WordSpec, BeforeAndAfter}
import akka.pattern.ask
import akka.testkit.{TestProbe, TestKit}
import com.aphelia.amqp.ConnectionOwner.CreateChannel
import com.rabbitmq.client.{Channel, ConnectionFactory}
import akka.util.duration._
import akka.actor.{Props, ActorSystem}
import java.util.concurrent.Executors
import akka.dispatch.Future
import akka.dispatch.{Await, ExecutionContext}
import com.aphelia.amqp.RpcClient.{Request, Response}
import com.aphelia.amqp.Amqp._
import com.aphelia.amqp.RpcServer.IProcessor

@RunWith(classOf[JUnitRunner])
class ChannelOwnerSpec extends TestKit(ActorSystem("TestSystem")) with WordSpec with ShouldMatchers with BeforeAndAfter {
  val connFactory = new ConnectionFactory()

  "ChannelOwner" should {
    "provide channels" in {
      checkConnection
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)), name = "conn")
      Thread.sleep(500)
      val probe = TestProbe()
      probe.send(conn, CreateChannel)
      probe.expectMsgClass(1 second, classOf[Channel])
      system.stop(conn)
    }
  }

  "Consumers" should {
    "receive messages sent by producers" in {
      checkConnection
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)), name = "conn")
      Thread.sleep(500)
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = QueueParameters(name = "", passive = false, exclusive = true)
      val probe = TestProbe()
      val consumer = ConnectionOwner.createActor(conn, Props(new Consumer(List(Binding(exchange, queue, "my_key", true)), probe.ref)), 5000 millis)
      val producer = ConnectionOwner.createActor(conn, Props(new ChannelOwner()))
      Thread.sleep(500)
      val message = "yo!".getBytes
      producer ! Publish(exchange.name, "my_key", message)
      probe.expectMsgClass(1 second, classOf[Delivery])
      system.stop(conn)
    }
  }

  "RPC Servers" should {
    "reply to clients" in {
      checkConnection
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)))
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = QueueParameters(name = "my_queue", passive = false)
      val proc = new RpcServer.IProcessor() {
        def process(delivery : Delivery) = {
          println("processing")
          val s = new String(delivery.body)
          if (s == "5") throw new Exception("I dont do 5s")
          Some(delivery.body)
        }

        def onFailure(delivery : Delivery, e : Exception) = Some(e.toString.getBytes)
      }
      val server = ConnectionOwner.createActor(conn, Props(new RpcServer(queue, exchange, "my_key", proc)), 2000 millis)
      val client1 = ConnectionOwner.createActor(conn, Props(new RpcClient()), 2000 millis)
      val client2 = ConnectionOwner.createActor(conn, Props(new RpcClient()), 2000 millis)
      val exec = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

      val f1 = Future {
        for (i <- 0 to 15) {
          try {
            val future = client1.ask(Request(Publish("amq.direct", "my_key", i.toString.getBytes) :: Nil, 1))(1000 millis)
            val result = Await.result(future, 1000 millis).asInstanceOf[Response]
            println("result1 " + new String(result.buffers.head))
            Thread.sleep(300)
          }
          catch {
            case e: Exception => println(e.toString)
          }
        }
      }(exec)
      val f2 = Future {
        for (i <- 0 to 15) {
          try {
            val future = client2.ask(Request(Publish("amq.direct", "my_key", i.toString.getBytes) :: Nil, 1))(1000 millis)
            val result = Await.result(future, 1000 millis).asInstanceOf[Response]
            println("result2 " + new String(result.buffers.head))
            Thread.sleep(300)
          }
          catch {
            case e: Exception => println(e.toString)
          }
        }
      }(exec)
      Await.result(f1, 1 minute)
      Await.result(f2, 1 minute)
      system.stop(conn)
    }
  }

  "RPC Clients and Servers" should {
    "implement 1 request/several responses patterns" in {
      checkConnection
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)), name = "conn")
      Thread.sleep(500)
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      // empty means that a random name will be generated when the queue is declared
      val queue = QueueParameters(name = "", passive = false, exclusive = true)
      // create 2 servers, each using a broker generated private queue and their own processor
      val proc1 = new IProcessor {
        def process(delivery: Delivery) = Some("proc1".getBytes)

        def onFailure(delivery: Delivery, e: Exception) = None
      }
      val server1 = ConnectionOwner.createActor(conn, Props(new RpcServer(queue, exchange, "mykey", proc1)), 2000 millis )
      val proc2 = new IProcessor {
        def process(delivery: Delivery) = Some("proc2".getBytes)

        def onFailure(delivery: Delivery, e: Exception) = None
      }
      val server2 = ConnectionOwner.createActor(conn, Props(new RpcServer(queue, exchange, "mykey", proc2)), 2000 millis )

      val client = ConnectionOwner.createActor(conn, Props(new RpcClient()), 2000 millis)
      Thread.sleep(500)
      val future = client.ask(Request(Publish(exchange.name, "mykey", "yo!".getBytes) :: Nil, 2))(1000 millis)
      val result = Await.result(future, 1000 millis).asInstanceOf[Response]
      assert(result.buffers.length == 2)
      // we're supposed to have received to answers, "proc1" and "proc2"
      val strings = result.buffers.map(new String(_))
      assert(strings.sorted == List("proc1", "proc2"))

      system.stop(conn)
    }
  }
  def checkConnection {
    try {
      val conn = connFactory.newConnection()
      conn.close()
    }
    catch {
      case e : Exception => info("cannot connect to local amqp broker, tests will not be run"); pending
    }
  }
}
