package com.lightbend.modelserver

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Route
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.kafka.scaladsl.Consumer
import akka.stream.{ActorMaterializer, SourceShape}
import akka.stream.scaladsl.{GraphDSL, Sink, Source}
import akka.util.Timeout

import scala.concurrent.duration._
import com.lightbend.configuration.kafka.ApplicationKafkaParameters
import com.lightbend.configuration.kafka.ApplicationKafkaParameters.{DATA_GROUP, LOCAL_KAFKA_BROKER, MODELS_GROUP}
import com.lightbend.model.winerecord.WineRecord
import com.lightbend.modelserver.kafka.EmbeddedSingleNodeKafkaCluster
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import akka.http.scaladsl.Http
import com.lightbend.modelserver.modelServer.ReadableModelStateStore
import com.lightbend.modelserver.queriablestate.QueriesAkkaHttpResource
import com.lightbend.modelserver.support.scala.{DataReader, ModelToServe}

/**
  * Created by boris on 7/21/17.
  */
object AkkaModelServer {

  implicit val system = ActorSystem("ModelServing")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  val dataConsumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
    .withBootstrapServers(LOCAL_KAFKA_BROKER)
    .withGroupId(DATA_GROUP)
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")

  val modelConsumerSettings = ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
    .withBootstrapServers(LOCAL_KAFKA_BROKER)
    .withGroupId(MODELS_GROUP)
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")

  def main(args: Array[String]): Unit = {


    import ApplicationKafkaParameters._

    // Create embedded Kafka and topics
//    EmbeddedSingleNodeKafkaCluster.start()
//    EmbeddedSingleNodeKafkaCluster.createTopic(DATA_TOPIC)
//    EmbeddedSingleNodeKafkaCluster.createTopic(MODELS_TOPIC)

    val modelStream: Source[ModelToServe, Consumer.Control] =
      Consumer.atMostOnceSource(modelConsumerSettings, Subscriptions.topics(MODELS_TOPIC))
        .map(record => ModelToServe.fromByteArray(record.value())).filter(_.isSuccess).map(_.get)

    val dataStream: Source[WineRecord, Consumer.Control] =
      Consumer.atMostOnceSource(dataConsumerSettings, Subscriptions.topics(DATA_TOPIC))
        .map(record => DataReader.fromByteArray(record.value())).filter(_.isSuccess).map(_.get)

    val model = new ModelStage()

    def keepModelMaterializedValue[M1, M2, M3](m1: M1, m2: M2, m3: M3): M3 = m3

    val modelPredictions : Source[Option[Double], ReadableModelStateStore] = Source.fromGraph(
      GraphDSL.create(dataStream, modelStream, model)(keepModelMaterializedValue) {
        implicit builder => (d, m, w) =>
          import GraphDSL.Implicits._

          // wire together the input streams with the model stage (2 in, 1 out)
          /*
                            dataStream --> |       |
                                           | model | -> predictions
                            modelStream -> |       |
          */

          d ~> w.dataRecordIn
          m ~> w.modelRecordIn
          SourceShape(w.scoringResultOut)
      }
    )


  val materializedReadableModelStateStore: ReadableModelStateStore =
      modelPredictions
        .map(println(_))
        .to(Sink.ignore) // we do not read the results directly
        .run() // we run the stream, materializing the stage's StateStore

    startRest(materializedReadableModelStateStore)
  }

  def startRest(service : ReadableModelStateStore) : Unit = {

    implicit val timeout = Timeout(10 seconds)
    val host = "localhost"
    val port = 5000
    val routes: Route = QueriesAkkaHttpResource.storeRoutes(service)

    Http().bindAndHandle(routes, host, port) map
      { binding => println(s"REST interface bound to ${binding.localAddress}") } recover { case ex =>
      println(s"REST interface could not bind to $host:$port", ex.getMessage)
    }
  }
}