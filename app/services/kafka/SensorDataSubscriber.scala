package services.kafka

import java.time.{Instant, ZoneOffset, ZonedDateTime}
import java.util.concurrent.Executors

import com.google.inject.AbstractModule
import com.iofficecorp.appinfoclient.ApplicationInfoClient
import com.iofficecorp.metamorphosis.IofficeKafka
import com.iofficecorp.metamorphosis.IofficeKafka.IofficeStreams._
import com.iofficecorp.metamorphosis.IofficeKafka.{brokers, streamsConfiguration}
import com.iofficecorp.metamorphosis.models.{SensorChangesKeyAvro, SensorChangesValueAvro}
import com.lightbend.kafka.scala.streams.ImplicitConversions._
import com.lightbend.kafka.scala.streams.StreamsBuilderS
import javax.inject.Inject
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.streams.KafkaStreams.State
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler
import org.apache.kafka.streams.processor.StateRestoreListener
import org.apache.kafka.streams.{KafkaStreams, StreamsConfig}
import play.api.libs.ws.WSClient
import play.api.{Configuration, Logger}
import services.cosmos.CosmosUpdater

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}


class SensorDataSubscriber @Inject()(conf: Configuration,
                                     appInfoClient: ApplicationInfoClient,
                                     ws: WSClient
                               ) {

  implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  var resetStreamSetup = false
  var logCount = -1
  val log = Logger(this.getClass.getName)

  val isEnvironmentVarsConfigured: Boolean = conf
    .getOptional[String]("kafka.brokers")
    .isDefined &&
    conf
      .getOptional[String]("kafka.schemaRegistryUrl")
      .isDefined

  val isSubscriberConfOn: Boolean = conf
    .getOptional[String]("kafka.runSubscriber")
    .getOrElse("true")
    .equals("true")

  val subscribedTopicName: String = conf
    .getOptional[String]("kafka.changesetTopicName")
    .getOrElse("sensor-changes")

  val getTaskId: String = conf
    .getOptional[String]("kafka.subscriberName")
    .getOrElse("sensor-changes-subscriber")

  val postToCosmos = conf.getOptional[String]("cosmos.masterKey").isDefined &&
    conf.getOptional[String]("cosmos.serviceEndpoint").isDefined

  var cosmosUpdater: Option[CosmosUpdater] = None

  def startSubscriber(brokers: String, taskId: String): KafkaStreams = StartDataSubscriberStream(readSensorEvent, IofficeKafka.brokers, taskId, subscribedTopicName)

  def logSensorProccessing(sensorData: SensorChangesValueAvro, appCode: String): Unit =
    log.warn(s"endDate=${ZonedDateTime.ofInstant(Instant.ofEpochMilli(sensorData.getChangedate), ZoneOffset.UTC)}. now=${Instant.now().toString}. appcode=$appCode")

  def readSensorEvent(key: String, value: SensorChangesValueAvro, retryCount: Int = 0): Unit = {

    if (postToCosmos) {
      // dont create this class unless 'postToCosmos' is true to ensure we have the conf val cosmos.masterKey
      cosmosUpdater.getOrElse({
        cosmosUpdater = Option(new CosmosUpdater(appInfoClient, conf, ws))
        cosmosUpdater.get
      }).saveSensor(value, key)
    }

    logTheThing(value, key)
  }

  object StartDataSubscriberStream {

    def apply(processSensor: (String, SensorChangesValueAvro, Int) => Unit,
              brokers: String = brokers,
              instanceId: String,
              subscribedTopicName: String): KafkaStreams = {
      val streamsConf = streamsConfiguration(instanceId, brokers)
      streamsConf.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, "30000")
      streamsConf.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
      streamsConf.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
      streamsConf.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG, classOf[LogAndContinueExceptionHandler])

      val builder = new StreamsBuilderS()

      builder
        .stream[SensorChangesKeyAvro, SensorChangesValueAvro](subscribedTopicName)
        .foreach((key: SensorChangesKeyAvro, value: SensorChangesValueAvro) => {
          processSensor(key.getAppCode, value, 0)
          ()
        })

      buildStream(builder, streamsConf)
    }
  }

  def startSensorSubscriberStream(): Unit = if (!resetStreamSetup) {
    resetStreamSetup = true
    if (isEnvironmentVarsConfigured && isSubscriberConfOn) {
      val stream = startSubscriber(IofficeKafka.brokers, getTaskId)
      stream.setGlobalStateRestoreListener(new RestoreListener)
      stream.setStateListener(new StateListener)
      stream.setUncaughtExceptionHandler(new ExceptionHandler)
      if (stream.state != State.RUNNING) {
        Try({
          stream.cleanUp
          stream.start
        }) match {
          case Success(_) => ()
          case Failure(ex) =>
            log.error(s"the start failed: ${ex.getMessage}")
            Try(stream.close) match {
              case Success(_) => ()
              case Failure(ex2) => {
                log.error(s"The stop failed ${ex2.getMessage}")
              }
            }
        }
      }
      sys.addShutdownHook({
        stream.close()
      })
      ()
    } else {
      log.error(s"It looks like you really want to start the topic stream here but the env vars don't exist!")
    }
  }

  class ExceptionHandler extends Thread.UncaughtExceptionHandler {
    val log = Logger(this.getClass.getName)
    var stream: KafkaStreams = _

    override def uncaughtException(t: Thread, e: Throwable): Unit = {
      log.error(s"Uncaught Exception! Thread: ${t.toString}.  ${e.toString}")
      resetStreamSetup = false
      startSensorSubscriberStream
    }
  }

  // These two listener were added for logging purposes
  class RestoreListener extends StateRestoreListener {
    val log = Logger(this.getClass.getName)

    override def onRestoreStart(topicPartition: TopicPartition, storeName: String, startingOffset: Long, endingOffset: Long): Unit = {
      log.warn(s"onRestoreStart Called!: TopicPartition: ${topicPartition.toString}. storeName: $storeName. startingOffset: $startingOffset. endingOffset:$endingOffset")
    }

    override def onBatchRestored(topicPartition: TopicPartition, storeName: String, batchEndOffset: Long, numRestored: Long): Unit = {
      log.debug(s"onBatchRestored-TopicPartition: ${topicPartition.toString}. storeName: $storeName. batchEndOffset: $batchEndOffset. numRestored:$numRestored")
    }

    override def onRestoreEnd(topicPartition: TopicPartition, storeName: String, totalRestored: Long): Unit = {
      log.warn(s"onRestoreEnd Called!: TopicPartition: ${topicPartition.toString}. storeName: $storeName. totalRestored: $totalRestored.")
    }
  }

  class StateListener extends KafkaStreams.StateListener {
    val log = Logger(this.getClass.getName)
    override def onChange(newState: State, oldState: State): Unit = {
      log.warn(s"StateListener onChange Called!: newState: ${newState.toString}. oldState: ${oldState.toString} ")
    }
  }

  def logTheThing(sensorData: SensorChangesValueAvro, appCode: String) = {
    // avoid logging every time. slows down whole thing and it starts to lag farther and farther behind.
    if (logCount > 100 || logCount == -1) {
      logCount = 0
      logSensorProccessing(sensorData, appCode)
    }
    logCount = logCount + 1
  }

}

class DataSubscriber @Inject()(subscriber: SensorDataSubscriber) {
  subscriber.startSensorSubscriberStream()
}

class SensorDataSubscriberModule extends AbstractModule {
  override def configure() = {
    bind(classOf[DataSubscriber]).asEagerSingleton()
  }
}