package web.akka

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route

class SearchRoutes(service: SearchService) {
  val routes: Route =
    (get & path("health")) {
      complete("ok")
    } ~
      path("search" / Segment) { q =>
        get {
          complete(service.search(q).mkString(","))
        }
      }
}
