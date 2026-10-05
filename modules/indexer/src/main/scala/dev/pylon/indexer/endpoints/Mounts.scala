package dev.pylon.indexer.endpoints

import scala.meta._

/** An endpoint with its full path, ready to become a graph node. */
final case class Endpoint(symbol: String, verb: String, path: String, route: Route) {
  def display: String = s"$verb $path"
}

object Mounts {

  /** Bound on the number of distinct prefixes a holder can get (routes mounted in many places). */
  val MaxPrefixes = 16

  def endpointSymbol(service: String, framework: String, verb: String, path: String): String =
    s"pylon:endpoint/$service/$framework/$verb $path"

  def render(segments: Seq[String]): String = segments.mkString("/", "/", "")

  /**
   * Gives every route its full path(s). A holder's prefixes come from its explicit mounts,
   * or from its implicit ones when it has none; containers are resolved recursively (cycle-safe).
   */
  def resolve(service: String, routes: Seq[Route], mounts: Seq[Mount]): Seq[Endpoint] = {
    val byMounted = mounts.groupBy(_.mounted)

    def prefixes(holder: String, visiting: Set[String]): Seq[Seq[String]] = {
      val all       = byMounted.getOrElse(holder, Nil).filter(m => m.container != holder && !visiting(m.container))
      val explicit  = all.filter(_.explicit)
      val effective = if (explicit.nonEmpty) explicit else all
      if (effective.isEmpty) Seq(Nil)
      else
        effective
          .flatMap(m => prefixes(m.container, visiting + m.container).map(_ ++ m.prefix))
          .distinct
          .take(MaxPrefixes)
    }

    routes.flatMap { r =>
      val ps = r.holder.fold(Seq(Seq.empty[String]))(h => prefixes(h, Set(h)))
      ps.map { p =>
        val path = render(p ++ r.segments)
        Endpoint(endpointSymbol(service, r.framework, r.verb, path), r.verb, path, r)
      }
    }.distinctBy(e => (e.symbol, e.route.file, e.route.scope, e.route.targets))
  }

  /**
   * Implicit mounts: every reference to a route holder from another definition (`a.routes <+> b.routes`,
   * `toRoutes(List(searchLogic, ...))`) passes that definition's prefixes on to the holder.
   */
  def implicitMounts(files: Seq[ParsedFile], holders: Set[String]): Seq[Mount] =
    files.flatMap { f =>
      f.tree.collect {
        case n: Term.Name if f.symbols(n).exists(holders) =>
          val target = f.symbols(n).find(holders).get
          f.holderOf(n).filter(_ != target).map(container => Mount(container, Nil, target, explicit = false))
      }.flatten
    }.distinct
}
