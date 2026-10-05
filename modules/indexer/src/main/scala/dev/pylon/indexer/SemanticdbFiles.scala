package dev.pylon.indexer

import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}
import java.nio.file.attribute.BasicFileAttributes
import scala.collection.mutable
import scala.meta.internal.semanticdb.{TextDocument, TextDocuments}
import scala.util.Using

/** A SemanticDB document together with the Scala version it was compiled with (from the `scala-x.y.z` target dir). */
final case class LoadedDocument(doc: TextDocument, isScala3: Boolean, semanticdbFile: Path)

object SemanticdbFiles {

  private val skippedDirs = Set(".git", "node_modules", ".bsp", ".metals", ".bloop", ".idea", ".pylon")

  /** Finds every `*.semanticdb` under `root`, skipping meta-builds (`project/`). */
  def find(root: Path): Seq[Path] = {
    val out = mutable.ArrayBuffer.empty[Path]
    Files.walkFileTree(
      root,
      new SimpleFileVisitor[Path] {
        override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = {
          val name = Option(dir.getFileName).map(_.toString).getOrElse("")
          if (dir != root && (skippedDirs(name) || dir == root.resolve("project")))
            FileVisitResult.SKIP_SUBTREE
          else FileVisitResult.CONTINUE
        }
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          if (file.toString.endsWith(".semanticdb") && file.toString.contains("META-INF")) out += file
          FileVisitResult.CONTINUE
        }
      }
    )
    out.sorted.toSeq
  }

  /**
   * Loads all documents whose source file still exists. When the same source was compiled
   * several times (e.g. cross-built), the most recently written SemanticDB wins.
   */
  def load(root: Path): Seq[LoadedDocument] = {
    val byUri = mutable.LinkedHashMap.empty[String, (LoadedDocument, Long)]
    find(root).foreach { file =>
      val modified = Files.getLastModifiedTime(file).toMillis
      val isScala3 = file.toString.contains("/scala-3")
      val docs     = Using.resource(Files.newInputStream(file))(TextDocuments.parseFrom).documents
      docs.filter(d => Files.isRegularFile(root.resolve(d.uri))).foreach { doc =>
        val loaded = LoadedDocument(doc, isScala3, file)
        byUri.get(doc.uri) match {
          case Some((_, seen)) if seen >= modified =>
          case _                                   => byUri(doc.uri) = (loaded, modified)
        }
      }
    }
    byUri.values.map(_._1).toSeq
  }
}
