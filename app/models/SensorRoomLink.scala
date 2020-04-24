package models

import play.api.libs.json._

case class SensorRoomLink(id: Int,
                          roomId: Int,
                          sensorUid: String,
                          startDate: String
                         )

object SensorRoomLink {
  implicit val jsonReads: Reads[SensorRoomLink] = Json.reads[SensorRoomLink]
  implicit val jsonWrites: Writes[SensorRoomLink] = Json.writes[SensorRoomLink]
}