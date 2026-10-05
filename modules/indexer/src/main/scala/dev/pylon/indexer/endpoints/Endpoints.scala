package dev.pylon.indexer.endpoints

import java.nio.file.Path
import scala.meta.internal.semanticdb.SymbolInformation
import scala.util.control.NonFatal

/** Runs every endpoint adapter over a service and resolves mounts into full paths. */
object Endpoints {

  def find(
      service: String,
      root: Path,
      files: Seq[ParsedFile],
      symtab: Map[String, SymbolInformation],
      warn: String => Unit
  ): Seq[Endpoint] = {
    def safely(name: String, uri: String)(f: => Found): Found =
      try f
      catch {
        case NonFatal(e) =>
          warn(s"[pylon] $name adapter failed on $uri: $e")
          Found()
      }

    val perFile = files.foldLeft(Found()) { (acc, f) =>
      acc ++ safely("http4s", f.uri)(Http4s.scan(f)) ++ safely("zio-http", f.uri)(ZioHttp.scan(f)) ++
        safely("akka/pekko-http", f.uri)(AkkaHttp.scan(f))
    }
    val found = perFile ++ safely("tapir", "all files")(Tapir.scan(files)) ++ safely("play", "conf/*routes")(PlayRoutes.scan(root, symtab, warn))

    val holders  = found.routes.flatMap(_.holder).toSet ++ found.mounts.map(_.container)
    val implicitMounts = Mounts.implicitMounts(files, holders)
    Mounts.resolve(service, found.routes, found.mounts ++ implicitMounts)
  }
}
