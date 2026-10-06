package web.pekko

import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route

class SearchRoutes(service: SearchService):
  def adminRoutes: Route =
    path("reindex") {
      post {
        complete(service.reindex())
      }
    }

  val routes: Route =
    pathPrefix("api") {
      concat(
        path("search") {
          get {
            parameter("q") { q =>
              complete(service.search(q).mkString(","))
            }
          }
        },
        path("items" / LongNumber) { id =>
          get {
            complete(service.find(id).getOrElse("missing"))
          } ~
            delete {
              complete(service.delete(id))
            }
        },
        pathPrefix("admin") {
          adminRoutes
        }
      )
    }
