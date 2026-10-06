package dev.pylon.server

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import dev.pylon.core.GraphStore
import java.io.InputStream
import java.net.{InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.Using
import scala.util.control.NonFatal

/**
 * Local HTTP server: `/api/...` answered by [[Api]], everything else served from the viewer bundle
 * (the `viewer/` classpath directory, or `staticDir` when given, e.g. `viewer/dist` during development).
 *
 * Requests are handled on a single thread because the SQLite connection is not shared safely.
 */
final class PylonServer(store: GraphStore, host: String = "127.0.0.1", port: Int = 7777, staticDir: Option[Path] = None) {

  private val api    = new Api(store)
  private val server = HttpServer.create(new InetSocketAddress(host, port), 0)

  server.createContext("/", (ex: HttpExchange) => handle(ex))

  /** Starts serving and returns the bound port (useful with port 0). */
  def start(): Int = { server.start(); server.getAddress.getPort }

  def stop(): Unit = server.stop(0)

  private def handle(ex: HttpExchange): Unit =
    try {
      val path = ex.getRequestURI.getPath
      if (ex.getRequestMethod != "GET") send(ex, 405, "text/plain", "method not allowed".getBytes)
      else if (path.startsWith("/api/")) {
        val res = api.handle(path, PylonServer.queryParams(ex.getRequestURI.getRawQuery))
        send(ex, res.status, "application/json", ujson.write(res.body).getBytes(StandardCharsets.UTF_8))
      } else serveStatic(ex, path)
    } catch {
      case NonFatal(e) =>
        Console.err.println(s"[pylon] ${ex.getRequestURI}: $e")
        send(ex, 500, "application/json", ujson.write(ujson.Obj("error" -> String.valueOf(e.getMessage))).getBytes(StandardCharsets.UTF_8))
    } finally ex.close()

  private def serveStatic(ex: HttpExchange, rawPath: String): Unit = {
    val rel = rawPath.stripPrefix("/") match {
      case "" => "index.html"
      case p  => p
    }
    // Unknown non-asset paths fall back to the single-page app.
    val resolved = readAsset(rel).map(rel -> _).orElse(if (rel.contains('.')) None else readAsset("index.html").map("index.html" -> _))
    resolved match {
      case Some((name, bytes)) => send(ex, 200, PylonServer.contentType(name), bytes)
      case None if rel == "index.html" =>
        send(ex, 200, "text/html; charset=utf-8", PylonServer.missingViewerPage.getBytes(StandardCharsets.UTF_8))
      case None => send(ex, 404, "text/plain", "not found".getBytes)
    }
  }

  private def readAsset(rel: String): Option[Array[Byte]] =
    if (rel.split('/').contains("..")) None
    else
      staticDir match {
        case Some(dir) =>
          val file = dir.resolve(rel).normalize
          if (file.startsWith(dir.normalize) && Files.isRegularFile(file)) Some(Files.readAllBytes(file)) else None
        case None =>
          Option(getClass.getClassLoader.getResourceAsStream(s"viewer/$rel")).map((in: InputStream) => Using.resource(in)(_.readAllBytes()))
      }

  private def send(ex: HttpExchange, status: Int, contentType: String, body: Array[Byte]): Unit = {
    ex.getResponseHeaders.set("Content-Type", contentType)
    ex.getResponseHeaders.set("Cache-Control", "no-store")
    ex.sendResponseHeaders(status, if (body.isEmpty) -1 else body.length.toLong)
    if (body.nonEmpty) Using.resource(ex.getResponseBody)(_.write(body))
  }
}

object PylonServer {

  def queryParams(raw: String): Map[String, String] =
    Option(raw).toSeq
      .flatMap(_.split('&'))
      .filter(_.nonEmpty)
      .map { kv =>
        val (k, v) = kv.span(_ != '=')
        URLDecoder.decode(k, StandardCharsets.UTF_8) -> URLDecoder.decode(v.drop(1), StandardCharsets.UTF_8)
      }
      .toMap

  def contentType(name: String): String = name.substring(name.lastIndexOf('.') + 1) match {
    case "html"         => "text/html; charset=utf-8"
    case "js" | "mjs"   => "text/javascript; charset=utf-8"
    case "css"          => "text/css; charset=utf-8"
    case "json"         => "application/json"
    case "svg"          => "image/svg+xml"
    case "png"          => "image/png"
    case "woff2"        => "font/woff2"
    case _              => "application/octet-stream"
  }

  val missingViewerPage: String =
    """<!doctype html><meta charset="utf-8"><title>Pylon</title>
      |<body style="font-family: system-ui; max-width: 40rem; margin: 4rem auto; line-height: 1.5">
      |<h1>Pylon viewer not built</h1>
      |<p>The API is running, but the web viewer bundle is missing. Build it with:</p>
      |<pre>npm --prefix viewer ci &amp;&amp; npm --prefix viewer run build</pre>
      |<p>or simply use <code>bin/pylon</code>, which builds it when needed.</p>
      |</body>""".stripMargin
}
