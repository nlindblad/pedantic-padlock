package info.lindblad.pedanticpadlock.services

import akka.actor.ActorSystem
import akka.util.Timeout
import info.lindblad.pedanticpadlock.model.{AwaitingResult, SslLabsReport}
import info.lindblad.pedanticpadlock.util.Logging
import org.json4s.JsonAST.{JArray, JString}
import org.json4s.native.JsonMethods._
import org.json4s.{JValue, _}
import spray.client.pipelining._
import spray.http._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

trait SslLabsApi {
  def interpretReportJson(analysis: JValue): Option[SslLabsReport]
  def analyse(awaitingResult: AwaitingResult, currentTime: Long, lastTimePolled: Long, pollInterval: Long): Either[AwaitingResult, SslLabsReport]
}

trait SslLabsApiClient {

  private implicit val system = ActorSystem("ssllabs-api-client")
  private implicit val dispatcher = system.dispatcher

  def retrieveAnalysis(apiUrl: String, host: String): Future[JValue] = {
    for {
      analysisJson <- sendWithUserAgent(HttpRequest(HttpMethods.GET, Uri(s"${apiUrl}analyze?host=${host}&publish=off&fromCache=on&maxAge=4&all=on&ignoreMismatch=off")))
    } yield parse(analysisJson)
  }

  val sendWithUserAgent: HttpRequest => Future[String] =
    (addHeader("User-Agent", s"PedanticPadlock")
      ~> sendReceive
      ~> unmarshal[String])
}

class SslLabsService(apiBaseUrl: String = "https://api.ssllabs.com/api/", apiVersion: String = "2") extends SslLabsApi with SslLabsApiClient with Logging {

  lazy val apiUrl = s"${apiBaseUrl}v${apiVersion}/"

  implicit val formats = DefaultFormats

  implicit val timeout = Timeout(300 seconds)

  def getLowestGrade(grades: JArray): String = {
    grades.children.map(grade => grade.extract[String]).sorted.reverse.head
  }

  def interpretReportJson(analysis: JValue): Option[SslLabsReport] = {
    (analysis \\ "status").extract[String] match {
      case "READY" => {
        val grade = (analysis \ "endpoints" \ "grade") match {
          case gradeJson: JString => gradeJson.extract[String]
          case gradeJson: JArray => getLowestGrade(gradeJson)
          case _ => "F"
        }
        Some(SslLabsReport(grade, true, false, false, 2048, "SomeAlgo"))
      }
      case _ => None
    }
  }

  def shouldPoll(currentTime: Long, lastTimePolled: Long, pollInterval: Long): Boolean = (currentTime - lastTimePolled) >= pollInterval

  def analyse(awaitingResult: AwaitingResult, currentTime: Long, lastTimePolled: Long, pollInterval: Long): Either[AwaitingResult, SslLabsReport] = {
    shouldPoll(currentTime, lastTimePolled, pollInterval) match {
      case true => {
        logger.info(s"Polling Qualsys report for ${awaitingResult.domainName}")
        interpretReportJson(Await.result(retrieveAnalysis(apiUrl, awaitingResult.domainName), timeout.duration)) match {
          case Some(report) => {
            logger.info(s"Got ready report for ${awaitingResult.domainName}")
            Right(report)
          }
          case _ => {
            logger.info(s"Report for ${awaitingResult.domainName} not ready yet")
            Left(new AwaitingResult(domainName = awaitingResult.domainName, scanReport = awaitingResult.scanReport, lastTimePolled = currentTime, timesPolled = awaitingResult.timesPolled + 1))
          }
        }
      }
      case false => {
        Left(awaitingResult)
      }
    }
  }
}

object SslLabsService {
  def apply() = new SslLabsService()
}