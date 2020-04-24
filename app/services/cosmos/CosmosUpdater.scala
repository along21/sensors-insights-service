package services.cosmos

import java.time.{Instant, ZoneOffset, ZonedDateTime}
import java.util.concurrent.Executors

import com.iofficecorp.appinfoclient.ApplicationInfoClient
import com.iofficecorp.metamorphosis.models.{SensorChangesValueAvro, SensorEventsValueAvro}
import com.microsoft.azure.documentdb.{ConnectionPolicy, ConsistencyLevel, Document, DocumentClient}
import javax.inject.Inject
import models.SensorDataEvent._
import models.{SensorDataChange, SensorDataEvent, SensorRoomLink}
import org.json.JSONObject
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.{Configuration, Logger}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class CosmosUpdater @Inject()(appInfoClient: ApplicationInfoClient,
                              conf: Configuration,
                              ws: WSClient) {

  val serviceEndpoint = conf
    .getOptional[String]("cosmos.serviceEndpoint")
    .orNull

  val documentClient = conf
    .getOptional[String]("cosmos.masterKey")
    .map(masterKey => new DocumentClient(
      serviceEndpoint,
      masterKey,
      ConnectionPolicy.GetDefault(),
      ConsistencyLevel.Session))
    .orNull

  val log = Logger(this.getClass.getName)

  private val collectionId = "SensorDataChange"

  def saveSensor(sensorData: SensorChangesValueAvro, appcode: String) = {
    implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
    log.warn(s"saving sensor in cosmos. ${sensorData.getSensorid}. appcode: $appcode")
    val sensorFuture = Future {

      log.warn(s"event future started. sensor: ${sensorData.getSensorid}, appCode: $appcode")
      val currentDate = ZonedDateTime.ofInstant(Instant.ofEpochMilli(sensorData.getChangedate), ZoneOffset.UTC)
      getDocument(sensorData.getSensorid, currentDate.getDayOfMonth, currentDate.getMonthValue, currentDate.getYear, appcode) match {
        case Some(document) => updateDocument(sensorData.getSensorid, sensorData.getValue, appcode, currentDate.toString, document, false)
        case _ => createNewDocument(sensorData.getSensorid, sensorData.getValue, sensorData.getType, appcode, currentDate)
     }
    }

    sensorFuture.onComplete({
      case Success(_) => log.warn(s"saved change event to cosmos. ${sensorData.getSensorid}. appcode: $appcode")
      case Failure(ex) => log.error(s"Error saving change event to cosmos. ${sensorData.getSensorid}. appcode: $appcode", ex)
    })
  }

  private def updateDocument(sensorId: String, value: Int, appcode: String, currentDate: String, document: Document, lastForDay: Boolean) = {
    log.warn(s"adding new event for sensor: $sensorId, appCode: $appcode")
    //parse previous dates and fill in the end date for the last event. and create start of new event
    val lastEvents = Json.parse(document.getCollection("events").toString)
      .as[Seq[SensorDataEvent]]
      .map(event => event.readDateEnd match {
        case Some(_) => event
        case _ => new SensorDataEvent(event.readDateStart, Option(currentDate), event.value)
      })
    // if it's the last event for the day, simply filling in it's end date is enough
    // otherwise, add the start date for the next event.
    val currentEvents = if (lastForDay)
      lastEvents
    else
      lastEvents :+ new SensorDataEvent(Option(currentDate.toString), None, value)

    // replace old event json list with new list in the document
    val jsonString = Json.toJson[Seq[SensorDataEvent]](currentEvents).toString()
    val jsonObject = new JSONObject(s"""{"events":$jsonString}""")

    document.set("events", jsonObject.getJSONArray("events"))
    CosmosUtil.replaceDocument(document, documentClient)
    log.debug(s"new event added for sensor: $sensorId, appCode: $appcode date:$currentDate")
    lastEvents.last
  }

  private val sensorServiceUrl = sys.env
    .get("MESOS_URL")
    .orElse(Some("http://mesosdev.az.corp.iofficecorp.com/service/nginx/dev"))
    .map(url => s"$url/sensors/v1/current-room")
    .get


  private def createNewDocument(sensorId: String, value: Int, sensorType: String, appcode: String, currentDate: ZonedDateTime) = {

    log.warn(s"creating new document for sensor: $sensorId, appCode: $appcode")
    // first request the current roomId for sensor
    implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
    ws
      .url(sensorServiceUrl)
      .withQueryStringParameters(("sensorId", sensorId))
      .withHeaders("x-application-code" -> appcode)
      .execute()
      .map(response => (response.json \ "response").as[List[SensorRoomLink]])
      .map(roomLink => roomLink.head.roomId)
      .onComplete({
        case Success(roomId) => createDocumentWithRoomId(roomId, sensorId, value, sensorType, appcode, currentDate)
        case Failure(ex) => {
          log.error(s"Error getting roomId for sensorId. $sensorId. appcode: $appcode. Creating document with no roomId", ex)
          createDocumentWithRoomId(-1, sensorId, value, sensorType, appcode, currentDate)
        }
      })
  }

  def createDocumentWithRoomId(roomId: Int, sensorId: String, value: Int, sensorType: String, appcode: String, currentDate: ZonedDateTime) = {

    // first update yesterday's document with this end date for it's last event
    // and get yesterday's last event to use as our new first event
    val yesterdaysLastSensor = getLastDocumentWithSensors(sensorId, appcode, currentDate) match {
      case Some(document) => updateDocument(sensorId, value, appcode, currentDate.toString, document, true)
      case _ => SensorDataEvent(None, Option(currentDate.toString), 0) // no data within the last month. Assuming last state was zero
    }

    val futureSensorData = new SensorDataEvent(Option(currentDate.toString), None, value)
    val sensorDataChange: SensorDataChange = new SensorDataChange(sensorId,
      currentDate.getMonthValue,
      currentDate.getDayOfMonth,
      currentDate.getYear,
      sensorType,
      roomId,
      currentDate.toLocalDate.toString,
      Seq(yesterdaysLastSensor, futureSensorData)
    )

    CosmosUtil.createDocument(sensorDataChange, appcode, documentClient, collectionId)
    log.warn(s"new document created for sensor: $sensorId, appCode: $appcode")
  }

  val maxDaysChecked = 30
  private def getLastDocumentWithSensors(sensorId: String, appCode: String, currentDate: ZonedDateTime, daysChecked: Int = 0): Option[Document] = {
    daysChecked > maxDaysChecked match {
      case true => None
      case false => {
        val yesterday = currentDate.minusDays(1)
        getDocument(sensorId, yesterday.getDayOfMonth, yesterday.getMonthValue, yesterday.getYear, appCode) match {
          case Some(document) => {
            Json.parse(document.getCollection("events").toString)
              .as[Seq[SensorDataEvent]]
              .isEmpty match {
              case false => Option(document)
              case true => getLastDocumentWithSensors(sensorId, appCode, yesterday, daysChecked + 1)
            }
          }
          case _ => getLastDocumentWithSensors(sensorId, appCode, yesterday, daysChecked + 1)
        }
      }
    }
  }

  private def getDocument(sensorId: String, day: Int, month: Int, year: Int, appcode: String) = Option(CosmosUtil.getDocumentBySensorAndDay(sensorId, day, month, year, appcode, documentClient, collectionId))

  val slickOverrideSettings = Map(
    "db.maxConnections" -> 25,
    "db.numThreads" -> 25,
    "db.minConnections" -> 1,
    "db.connectionTimeout" -> "30s")
}
