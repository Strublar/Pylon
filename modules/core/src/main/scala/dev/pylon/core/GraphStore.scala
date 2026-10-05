package dev.pylon.core

import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import scala.collection.mutable
import scala.util.Using

/**
 * SQLite-backed call graph covering one or more services.
 *
 * Symbols are keyed by their SemanticDB symbol. A symbol referenced but not defined
 * by any indexed service is stored as external (service = NULL) and upgraded when a
 * service defining it is indexed later.
 */
final class GraphStore private (conn: Connection) extends AutoCloseable {
  import GraphStore._

  createSchema()

  def close(): Unit = conn.close()

  // ---------------------------------------------------------------------------
  // Writes

  /** Replaces everything previously indexed for `graph.service`. */
  def replaceService(graph: ServiceGraph): Unit = transaction {
    deleteService(graph.service)
    exec("INSERT INTO services(name, root) VALUES (?, ?)", graph.service, graph.root)

    batch(
      """INSERT INTO symbols(symbol, kind, name, owner, display, signature, service, file, line, abstract, end_line)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        |ON CONFLICT(symbol) DO UPDATE SET
        |  kind = excluded.kind, name = excluded.name, owner = excluded.owner, display = excluded.display,
        |  signature = excluded.signature, service = excluded.service, file = excluded.file,
        |  line = excluded.line, abstract = excluded.abstract, end_line = excluded.end_line
        |WHERE symbols.service IS NULL""".stripMargin,
      graph.symbols
    )(bindSymbol)
    batch(
      """INSERT OR IGNORE INTO symbols(symbol, kind, name, owner, display, signature, service, file, line, abstract, end_line)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin,
      graph.externals.map(_.copy(service = None, file = None, line = None, endLine = None))
    )(bindSymbol)
    batch("INSERT OR IGNORE INTO extends(child, parent, service) VALUES (?, ?, ?)", graph.extendsEdges) {
      case (st, (child, parent)) => st.setString(1, child); st.setString(2, parent); st.setString(3, graph.service)
    }
    batch("INSERT OR IGNORE INTO overrides(method, overridden, service) VALUES (?, ?, ?)", graph.overrides) {
      case (st, (method, overridden)) =>
        st.setString(1, method); st.setString(2, overridden); st.setString(3, graph.service)
    }
    batch("INSERT INTO calls(caller, callee, file, line, synthetic, service) VALUES (?, ?, ?, ?, ?, ?)", graph.calls) {
      (st, c) =>
        st.setString(1, c.caller); st.setString(2, c.callee); st.setString(3, c.file)
        st.setInt(4, c.line); st.setInt(5, if (c.synthetic) 1 else 0); st.setString(6, graph.service)
    }
  }

  private def deleteService(service: String): Unit = {
    exec("DELETE FROM calls WHERE service = ?", service)
    exec("DELETE FROM extends WHERE service = ?", service)
    exec("DELETE FROM overrides WHERE service = ?", service)
    // Symbols still referenced by other services become external again instead of disappearing.
    exec(
      """UPDATE symbols SET service = NULL, file = NULL, line = NULL, end_line = NULL
        |WHERE service = ? AND symbol IN (SELECT callee FROM calls)""".stripMargin,
      service
    )
    exec("DELETE FROM symbols WHERE service = ?", service)
    exec("DELETE FROM services WHERE name = ?", service)
  }

  // ---------------------------------------------------------------------------
  // Reads

  def services: Seq[(String, String)] =
    query("SELECT name, root FROM services ORDER BY name")(rs => rs.getString(1) -> rs.getString(2))

  def symbol(symbol: String): Option[SymbolNode] =
    query(s"SELECT $symbolColumns FROM symbols s WHERE symbol = ?", symbol)(readSymbol).headOption

  /**
   * Finds symbols matching a user query: a SemanticDB symbol, `Type.method`, `Type#method`,
   * `pkg.Type.method`, or a bare name. Best matches first; project symbols before external ones.
   */
  def find(q: String, limit: Int = 20): Seq[SymbolNode] = {
    val norm   = q.trim.replace('#', '.')
    val parts  = norm.split('.').filter(_.nonEmpty)
    val suffix = parts.takeRight(2).mkString(".")
    val name   = parts.lastOption.getOrElse(norm)
    // Rank: exact symbol, exact display, display matches the last two segments, bare name, substring.
    query(
      s"""SELECT $symbolColumns,
         |  CASE
         |    WHEN symbol = ? THEN 0
         |    WHEN display = ? THEN 1
         |    WHEN display = ? AND (? = '' OR replace(replace(symbol, '/', '.'), '#', '.') LIKE ?) THEN 2
         |    WHEN name = ? THEN 3
         |    ELSE 4
         |  END AS rank
         |FROM symbols s
         |WHERE kind <> 'other' AND (
         |  symbol = ? OR display = ? OR name = ? OR display LIKE ? ESCAPE '\\'
         |)
         |ORDER BY rank, service IS NULL,
         |  CASE kind WHEN 'method' THEN 0 WHEN 'trait' THEN 1 WHEN 'class' THEN 1 WHEN 'object' THEN 1 WHEN 'val' THEN 2 ELSE 3 END,
         |  display
         |LIMIT ?""".stripMargin,
      q.trim,
      norm,
      suffix,
      if (parts.length > 2) parts.dropRight(2).mkString(".") else "",
      s"%${likeEscape(parts.dropRight(2).mkString("."))}%",
      name,
      q.trim,
      norm,
      name,
      s"%${likeEscape(norm)}%",
      limit
    )(readSymbol)
  }

  /** All transitive overriders of `method` (implementations of an abstract method, overrides of a concrete one). */
  def overriders(method: String): Seq[SymbolNode] =
    query(
      s"""WITH RECURSIVE sub(m) AS (
         |  SELECT method FROM overrides WHERE overridden = ?
         |  UNION SELECT o.method FROM overrides o JOIN sub ON o.overridden = sub.m
         |)
         |SELECT $symbolColumns FROM symbols s WHERE symbol IN (SELECT m FROM sub) ORDER BY display""".stripMargin,
      method
    )(readSymbol)

  /** All methods that `method` overrides, transitively. */
  def overridden(method: String): Seq[SymbolNode] =
    query(
      s"""WITH RECURSIVE sup(m) AS (
         |  SELECT overridden FROM overrides WHERE method = ?
         |  UNION SELECT o.overridden FROM overrides o JOIN sup ON o.method = sup.m
         |)
         |SELECT $symbolColumns FROM symbols s WHERE symbol IN (SELECT m FROM sup) ORDER BY display""".stripMargin,
      method
    )(readSymbol)

  /** Concrete methods a call to `method` can dispatch to: the method itself when concrete, plus every concrete overrider. */
  def implementations(method: String): Seq[SymbolNode] = {
    val self = symbol(method).filter(s => !s.isAbstract && !s.isExternal).toSeq
    (self ++ overriders(method).filterNot(_.isAbstract)).distinctBy(_.symbol)
  }

  /** Transitive subtypes of a type. */
  def subtypes(tpe: String): Seq[SymbolNode] =
    query(
      s"""WITH RECURSIVE sub(t) AS (
         |  SELECT child FROM extends WHERE parent = ?
         |  UNION SELECT e.child FROM extends e JOIN sub ON e.parent = sub.t
         |)
         |SELECT $symbolColumns FROM symbols s WHERE symbol IN (SELECT t FROM sub) ORDER BY display""".stripMargin,
      tpe
    )(readSymbol)

  /** Methods and constructors declared by a type. */
  def members(tpe: String): Seq[SymbolNode] =
    query(
      s"""SELECT $symbolColumns FROM symbols s
         |WHERE owner = ? AND kind IN ('method', 'constructor', 'val')
         |ORDER BY kind <> 'constructor', line""".stripMargin,
      tpe
    )(readSymbol)

  /** What `method` calls, one entry per distinct callee, in source order. */
  def callees(method: String): Seq[Callee] = {
    val rows = query(
      s"""SELECT $symbolColumns, c.file AS call_file, c.line AS call_line, c.synthetic
         |FROM calls c JOIN symbols s ON s.symbol = c.callee
         |WHERE c.caller = ?
         |ORDER BY c.file, c.line""".stripMargin,
      method
    )(rs => (readSymbol(rs), rs.getString("call_file"), rs.getInt("call_line"), rs.getInt("synthetic") == 1))
    groupSites(rows.map { case (s, f, l, syn) => (s, (f, l), syn) }).map { case (target, sites, synthetic) =>
      val candidates = if (target.kind == SymbolKind.Method) implementations(target.symbol) else Nil
      Callee(target, sites, synthetic, candidates)
    }
  }

  /**
   * Who calls `method`. With `viaOverrides`, also callers of the methods it overrides,
   * since a call to `ProviderTrait.search` may dispatch to `ProviderA.search`.
   */
  def callers(method: String, viaOverrides: Boolean = true): Seq[Caller] = {
    val targets = Seq(method) ++ (if (viaOverrides) overridden(method).map(_.symbol) else Nil)
    val viaBySymbol = targets.flatMap(t => symbol(t).map(t -> _)).toMap
    val placeholders = targets.map(_ => "?").mkString(", ")
    val rows = query(
      s"""SELECT $symbolColumns, c.callee AS via, c.file AS call_file, c.line AS call_line
         |FROM calls c JOIN symbols s ON s.symbol = c.caller
         |WHERE c.callee IN ($placeholders)
         |ORDER BY s.display, c.file, c.line""".stripMargin,
      targets: _*
    )(rs => (readSymbol(rs), rs.getString("via"), (rs.getString("call_file"), rs.getInt("call_line"))))
    rows
      .groupBy { case (caller, via, _) => (caller.symbol, via) }
      .toSeq
      .flatMap { case (_, group) =>
        val (caller, via, _) = group.head
        viaBySymbol.get(via).map(v => Caller(caller, v, group.map(_._3).distinct))
      }
      .sortBy(c => (c.caller.display, c.via.display))
  }

  /**
   * Paths from roots (methods nobody calls, e.g. `main` or, later, HTTP handlers) down to `method`.
   * Each path is ordered root first; a step's `via` is what the previous step called to reach it.
   */
  def entrypointPaths(method: String, maxDepth: Int = 20, maxPaths: Int = 50): Seq[Seq[PathStep]] = {
    symbol(method).fold(Seq.empty[Seq[PathStep]])(start => walkUp(start, maxDepth, maxPaths))
  }

  private def walkUp(start: SymbolNode, maxDepth: Int, maxPaths: Int): Seq[Seq[PathStep]] = {
    val results = mutable.ArrayBuffer.empty[Seq[PathStep]]
    val callersCache = mutable.Map.empty[String, Seq[Caller]]

    // Walks upwards; `chain` is ordered from `method` towards the root.
    def walk(chain: List[PathStep], onPath: Set[String]): Unit =
      if (results.size < maxPaths) {
        val current = chain.head.node
        val ups = callersCache.getOrElseUpdate(current.symbol, callers(current.symbol)).filterNot(c => onPath(c.caller.symbol))
        if (ups.isEmpty || chain.size > maxDepth) results += chain
        else
          ups.foreach { up =>
            val fixed = chain.head.copy(via = Some(up.via)) :: chain.tail
            walk(PathStep(up.caller, None) :: fixed, onPath + up.caller.symbol)
          }
      }

    walk(List(PathStep(start, None)), Set(start.symbol))
    results.toSeq
  }

  // ---------------------------------------------------------------------------
  // Plumbing

  private def createSchema(): Unit = {
    // The database is a cache of the index: on a schema change it is dropped and must be re-indexed.
    val version = query("PRAGMA user_version")(_.getInt(1)).headOption.getOrElse(0)
    if (version != SchemaVersion) {
      Using.resource(conn.createStatement()) { st =>
        Seq("calls", "overrides", "extends", "symbols", "services").foreach(t => st.execute(s"DROP TABLE IF EXISTS $t"))
        st.execute(s"PRAGMA user_version = $SchemaVersion")
      }
    }
    val ddl = Seq(
      "CREATE TABLE IF NOT EXISTS services(name TEXT PRIMARY KEY, root TEXT NOT NULL)",
      """CREATE TABLE IF NOT EXISTS symbols(
        |  symbol TEXT PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL, owner TEXT NOT NULL,
        |  display TEXT NOT NULL, signature TEXT NOT NULL, service TEXT, file TEXT, line INTEGER,
        |  abstract INTEGER NOT NULL, end_line INTEGER)""".stripMargin,
      "CREATE TABLE IF NOT EXISTS extends(child TEXT, parent TEXT, service TEXT, PRIMARY KEY(child, parent))",
      "CREATE TABLE IF NOT EXISTS overrides(method TEXT, overridden TEXT, service TEXT, PRIMARY KEY(method, overridden))",
      """CREATE TABLE IF NOT EXISTS calls(
        |  caller TEXT NOT NULL, callee TEXT NOT NULL, file TEXT NOT NULL, line INTEGER NOT NULL,
        |  synthetic INTEGER NOT NULL, service TEXT NOT NULL)""".stripMargin,
      "CREATE INDEX IF NOT EXISTS calls_caller ON calls(caller)",
      "CREATE INDEX IF NOT EXISTS calls_callee ON calls(callee)",
      "CREATE INDEX IF NOT EXISTS overrides_overridden ON overrides(overridden)",
      "CREATE INDEX IF NOT EXISTS extends_parent ON extends(parent)",
      "CREATE INDEX IF NOT EXISTS symbols_display ON symbols(display)",
      "CREATE INDEX IF NOT EXISTS symbols_name ON symbols(name)",
      "CREATE INDEX IF NOT EXISTS symbols_owner ON symbols(owner)"
    )
    Using.resource(conn.createStatement())(st => ddl.foreach(st.execute))
  }

  private def transaction[A](body: => A): A = {
    conn.setAutoCommit(false)
    try { val a = body; conn.commit(); a }
    catch { case e: Throwable => conn.rollback(); throw e }
    finally conn.setAutoCommit(true)
  }

  private def bind(st: PreparedStatement, args: Seq[Any]): Unit =
    args.zipWithIndex.foreach {
      case (null, i)       => st.setNull(i + 1, java.sql.Types.NULL)
      case (s: String, i)  => st.setString(i + 1, s)
      case (n: Int, i)     => st.setInt(i + 1, n)
      case (b: Boolean, i) => st.setInt(i + 1, if (b) 1 else 0)
      case (other, i)      => st.setObject(i + 1, other)
    }

  private def exec(sql: String, args: Any*): Unit =
    Using.resource(conn.prepareStatement(sql)) { st => bind(st, args); st.executeUpdate() }

  private def query[A](sql: String, args: Any*)(read: ResultSet => A): Seq[A] =
    Using.resource(conn.prepareStatement(sql)) { st =>
      bind(st, args)
      Using.resource(st.executeQuery()) { rs =>
        val out = Seq.newBuilder[A]
        while (rs.next()) out += read(rs)
        out.result()
      }
    }

  private def batch[A](sql: String, rows: Seq[A])(set: (PreparedStatement, A) => Unit): Unit =
    Using.resource(conn.prepareStatement(sql)) { st =>
      rows.foreach { row => set(st, row); st.addBatch() }
      st.executeBatch()
    }
}

object GraphStore {

  /** Bump when the schema changes; older databases are dropped and need re-indexing. */
  val SchemaVersion = 2

  def open(path: Path): GraphStore = {
    Option(path.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
    new GraphStore(DriverManager.getConnection(s"jdbc:sqlite:${path.toAbsolutePath}"))
  }

  def inMemory(): GraphStore = new GraphStore(DriverManager.getConnection("jdbc:sqlite::memory:"))

  /** Columns read by [[readSymbol]]; queries alias the symbols table as `s`. */
  private val symbolColumns =
    "s.symbol, s.kind, s.name, s.owner, s.display, s.signature, s.service, s.file, s.line, s.abstract, s.end_line"

  private def readSymbol(rs: ResultSet): SymbolNode = {
    def optInt(col: String): Option[Int] = { val v = rs.getInt(col); if (rs.wasNull()) None else Some(v) }
    SymbolNode(
      symbol = rs.getString("symbol"),
      kind = SymbolKind.fromId(rs.getString("kind")),
      name = rs.getString("name"),
      owner = rs.getString("owner"),
      display = rs.getString("display"),
      signature = rs.getString("signature"),
      service = Option(rs.getString("service")),
      file = Option(rs.getString("file")),
      line = optInt("line"),
      isAbstract = rs.getInt("abstract") == 1,
      endLine = optInt("end_line")
    )
  }

  private def bindSymbol(st: PreparedStatement, s: SymbolNode): Unit = {
    st.setString(1, s.symbol)
    st.setString(2, s.kind.id)
    st.setString(3, s.name)
    st.setString(4, s.owner)
    st.setString(5, s.display)
    st.setString(6, s.signature)
    s.service.fold(st.setNull(7, java.sql.Types.VARCHAR))(st.setString(7, _))
    s.file.fold(st.setNull(8, java.sql.Types.VARCHAR))(st.setString(8, _))
    s.line.fold(st.setNull(9, java.sql.Types.INTEGER))(st.setInt(9, _))
    st.setInt(10, if (s.isAbstract) 1 else 0)
    s.endLine.fold(st.setNull(11, java.sql.Types.INTEGER))(st.setInt(11, _))
  }

  /** Groups call rows by target, keeping first-seen order. */
  private def groupSites(rows: Seq[(SymbolNode, (String, Int), Boolean)]): Seq[(SymbolNode, Seq[(String, Int)], Boolean)] = {
    val order = mutable.LinkedHashMap.empty[String, (SymbolNode, mutable.ArrayBuffer[(String, Int)], Boolean)]
    rows.foreach { case (s, site, synthetic) =>
      order.get(s.symbol) match {
        case Some((node, sites, syn)) =>
          if (!sites.contains(site)) sites += site
          order(s.symbol) = (node, sites, syn && synthetic)
        case None => order(s.symbol) = (s, mutable.ArrayBuffer(site), synthetic)
      }
    }
    order.values.map { case (n, sites, syn) => (n, sites.toSeq, syn) }.toSeq
  }

  private def likeEscape(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
