package web.ziohttp

import zio.*
import zio.http.*

class SearchApi(service: SearchService):
  val routes: Routes[Any, Response] = Routes(
    Method.GET / "search" / string("q") -> handler { (q: String, req: Request) =>
      Response.text(service.search(q).mkString(","))
    },
    Method.GET / "items" / long("id") -> handler { (id: Long, req: Request) =>
      Response.text(service.find(id).getOrElse("missing"))
    },
    Method.DELETE / "items" / long("id") -> handler { (id: Long, req: Request) =>
      Response.text(service.delete(id))
    }
  )
