package expander.akka

import java.net.InetAddress
import java.security.MessageDigest

import akka.actor.ActorSystem
import akka.http.scaladsl.coding.Gzip
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.CacheDirectives.{ `max-age`, `must-revalidate` }
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ Directive1, Route, RouteResult }
import akka.stream.Materializer
import akka.util.ByteString
import com.typesafe.config.Config
import expander.core.{ Expander, PathRequest }
import play.api.libs.json._
import akka.http.scaladsl.util.FastFuture.EnhancedFuture
import expander.resolve.ExpanderResolve

import scala.concurrent.Future

class ExpanderFilter(conf: ExpanderFilterConfig)(implicit system: ActorSystem, mat: Materializer) {
  private def hash(str: String) = MessageDigest.getInstance("MD5").digest(str.getBytes).map("%02x" format _).mkString

  private def header(_name: String, _value: String): HttpHeader = new CustomHeader {
    override def value() = _value

    override def name() = _name

    override def renderInResponses() = false

    override def renderInRequests() = true
  }

  val isPassHeader: HttpHeader ⇒ Boolean = h ⇒ conf.forwardHeaders.exists(h.is)

  val extractExpandingHeaders: Directive1[Seq[HttpHeader]] =
    (extractClientIP.map(_.toOption) | provide(Option.empty[InetAddress])).flatMap { clientIp ⇒
      extractUri.flatMap { uri ⇒
        val expandingHeaders: Seq[HttpHeader] =
          clientIp.map(ip ⇒ `X-Forwarded-For`(RemoteAddress(ip))).toSeq :+
            header("X-Expanding-Uri", uri.toString)

        provide(expandingHeaders)
      }
    }

  def apply(route: Route): Route = {
    import conf._

    parameter(Expander.Key.?) {
      case None ⇒
        route

      case Some(expandRequest) ⇒
        val reqs = PathRequest.parse(expandRequest)
        if (reqs.isEmpty) {
          route
        } else {
          extractRequestContext { reqCtx ⇒
            extractExecutionContext { implicit ectx ⇒
              extractExpandingHeaders { expandingHeaders ⇒

                mapRouteResultFuture {
                  _.flatMap {
                    case RouteResult.Complete(resp) if resp.entity.contentType == ContentTypes.`application/json` ⇒

                      val headers = reqCtx.request.headers.filter(isPassHeader)
                      implicit lazy val expandContext = expandContextProvider(headers ++ expandingHeaders)(ectx)

                      resp.entity.dataBytes.runFold(ByteString(""))(_ ++ _).fast
                        .flatMap {
                          case bs if resp.header[`Content-Encoding`].exists(_.encodings.contains(HttpEncodings.gzip)) ⇒
                            Gzip.decode(bs)
                          case bs ⇒
                            Future.successful(bs)
                        }
                        .map(_.utf8String).map{ s ⇒
                          Json.parse(s)
                        }.flatMap(Expander(_, reqs: _*)).flatMap { json ⇒
                          val jsonString = Json.stringify(json)

                          val completeJson = complete(
                            resp.copy(
                              headers = resp.headers.filterNot(h ⇒
                                h.is("etag") ||
                                  h.is("last-modified") ||
                                  h.is("content-encoding")),
                              entity = HttpEntity.Strict(ContentTypes.`application/json`, ByteString(jsonString))
                            )
                          )

                          if (conditionalEnabled) {
                            (get {
                              conditional(EntityTag(hash(jsonString))) {
                                mapResponseHeaders(_ :+ `Cache-Control`(`max-age`(0), `must-revalidate`)) {
                                  completeJson
                                }
                              }
                            } ~ completeJson) (reqCtx)
                          } else completeJson(reqCtx)

                        }

                    case f ⇒
                      Future.successful(f)
                  }
                }(route)

              }

            }

          }

        }
    }
  }
}

object ExpanderFilter {

  def build(config: Config, httpResolve: ExpanderResolve)(implicit system: ActorSystem, mat: Materializer): ExpanderFilter =
    apply(ExpanderFilterConfig.build(config, httpResolve))

  def apply(conf: ExpanderFilterConfig)(implicit system: ActorSystem, mat: Materializer) =
    new ExpanderFilter(conf)
}