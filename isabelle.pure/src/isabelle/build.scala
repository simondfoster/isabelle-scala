/*  Title:      Pure/System/build.scala
    Author:     Makarius

Build and manage Isabelle sessions.
*/

package isabelle


import java.io.{BufferedInputStream, FileInputStream,
  BufferedReader, InputStreamReader, IOException}
import java.util.zip.GZIPInputStream

import scala.collection.mutable
import scala.annotation.tailrec


object Build
{
  /** session information **/

  object Session
  {
    /* Info */

    sealed case class Info(
      groups: List[String],
      dir: Path,
      parent: Option[String],
      description: String,
      options: Options,
      theories: List[(Options, List[Path])],
      files: List[Path],
      entry_digest: SHA1.Digest)


    /* Queue */

    object Queue
    {
      val empty: Queue = new Queue()
    }

    final class Queue private(graph: Graph[String, Info] = Graph.string)
      extends PartialFunction[String, Info]
    {
      def apply(name: String): Info = graph.get_node(name)
      def isDefinedAt(name: String): Boolean = graph.defined(name)

      def is_inner(name: String): Boolean = !graph.is_maximal(name)

      def is_empty: Boolean = graph.is_empty

      def + (name: String, info: Info): Queue =
        new Queue(
          try { graph.new_node(name, info).add_deps_acyclic(name, info.parent.toList) }
          catch {
            case _: Graph.Duplicate[_] => error("Duplicate session: " + quote(name))
            case exn: Graph.Cycles[_] =>
              error(cat_lines(exn.cycles.map(cycle =>
                "Cyclic session dependency of " +
                  cycle.map(c => quote(c.toString)).mkString(" via "))))
          })

      def - (name: String): Queue = new Queue(graph.del_node(name))

      def required(all_sessions: Boolean, session_groups: List[String], sessions: List[String])
        : (List[String], Queue) =
      {
        sessions.filterNot(isDefinedAt(_)) match {
          case Nil =>
          case bad => error("Undefined session(s): " + commas_quote(bad))
        }

        val selected =
        {
          if (all_sessions) graph.keys.toList
          else {
            val sel_group = session_groups.toSet
            val sel = sessions.toSet
              graph.keys.toList.filter(name =>
                sel(name) || apply(name).groups.exists(sel_group)).toList
          }
        }
        val descendants = graph.all_succs(selected)
        val queue1 = new Queue(graph.restrict(graph.all_preds(selected).toSet))
        (descendants, queue1)
      }

      def dequeue(skip: String => Boolean): Option[(String, Info)] =
      {
        val it = graph.entries.dropWhile(
          { case (name, (_, (deps, _))) => !deps.isEmpty || skip(name) })
        if (it.hasNext) { val (name, (info, _)) = it.next; Some((name, info)) }
        else None
      }

      def topological_order: List[(String, Info)] =
        graph.topological_order.map(name => (name, apply(name)))
    }
  }


  /* parsing */

  private case class Session_Entry(
    base_name: String,
    this_name: Boolean,
    groups: List[String],
    path: Option[String],
    parent: Option[String],
    description: String,
    options: List[Options.Spec],
    theories: List[(List[Options.Spec], List[String])],
    files: List[String])

  private object Parser extends Parse.Parser
  {
    val SESSION = "session"
    val IN = "in"
    val DESCRIPTION = "description"
    val OPTIONS = "options"
    val THEORIES = "theories"
    val FILES = "files"

    val syntax =
      Outer_Syntax.empty + "!" + "(" + ")" + "+" + "," + "=" + "[" + "]" +
        SESSION + IN + DESCRIPTION + OPTIONS + THEORIES + FILES

    val session_entry: Parser[Session_Entry] =
    {
      val session_name = atom("session name", _.is_name)

      val option =
        name ~ opt(keyword("=") ~! name ^^ { case _ ~ x => x }) ^^ { case x ~ y => (x, y) }
      val options = keyword("[") ~> repsep(option, keyword(",")) <~ keyword("]")

      val theories =
        keyword(THEORIES) ~! ((options | success(Nil)) ~ rep1(theory_name)) ^^
          { case _ ~ (x ~ y) => (x, y) }

      ((keyword(SESSION) ~! session_name) ^^ { case _ ~ x => x }) ~
        (keyword("!") ^^^ true | success(false)) ~
        (keyword("(") ~! (rep1(name) <~ keyword(")")) ^^ { case _ ~ x => x } | success(Nil)) ~
        (opt(keyword(IN) ~! path ^^ { case _ ~ x => x })) ~
        (keyword("=") ~> opt(session_name <~ keyword("+"))) ~
        (keyword(DESCRIPTION) ~! text ^^ { case _ ~ x => x } | success("")) ~
        (keyword(OPTIONS) ~! options ^^ { case _ ~ x => x } | success(Nil)) ~
        rep(theories) ~
        (keyword(FILES) ~! rep1(path) ^^ { case _ ~ x => x } | success(Nil)) ^^
          { case a ~ b ~ c ~ d ~ e ~ f ~ g ~ h ~ i => Session_Entry(a, b, c, d, e, f, g, h, i) }
    }

    def parse_entries(root: Path): List[Session_Entry] =
    {
      val toks = syntax.scan(File.read(root))
      parse_all(rep(session_entry), Token.reader(toks, root.implode)) match {
        case Success(result, _) => result
        case bad => error(bad.toString)
      }
    }
  }


  /* find sessions */

  private val ROOT = Path.explode("ROOT")
  private val SESSIONS = Path.explode("etc/sessions")

  private def is_pure(name: String): Boolean = name == "RAW" || name == "Pure"

  private def sessions_root(options: Options, dir: Path, root: Path, queue: Session.Queue)
    : Session.Queue =
  {
    (queue /: Parser.parse_entries(root))((queue1, entry) =>
      try {
        if (entry.base_name == "") error("Bad session name")

        val full_name =
          if (is_pure(entry.base_name)) {
            if (entry.parent.isDefined) error("Illegal parent session")
            else entry.base_name
          }
          else
            entry.parent match {
              case Some(parent_name) if queue1.isDefinedAt(parent_name) =>
                val full_name =
                  if (entry.this_name) entry.base_name
                  else parent_name + "-" + entry.base_name
                full_name
              case _ => error("Bad parent session")
            }

        val path =
          entry.path match {
            case Some(p) => Path.explode(p)
            case None => Path.basic(entry.base_name)
          }

        val session_options = options ++ entry.options

        val theories =
          entry.theories.map({ case (opts, thys) =>
            (session_options ++ opts, thys.map(Path.explode(_))) })
        val files = entry.files.map(Path.explode(_))
        val entry_digest =
          SHA1.digest((full_name, entry.parent, entry.options, entry.theories).toString)

        val info =
          Session.Info(entry.groups, dir + path, entry.parent, entry.description,
            session_options, theories, files, entry_digest)

        queue1 + (full_name, info)
      }
      catch {
        case ERROR(msg) =>
          error(msg + "\nThe error(s) above occurred in session entry " +
            quote(entry.base_name) + Position.str_of(root.position))
      })
  }

  private def sessions_dir(options: Options, strict: Boolean, dir: Path, queue: Session.Queue)
    : Session.Queue =
  {
    val root = dir + ROOT
    if (root.is_file) sessions_root(options, dir, root, queue)
    else if (strict) error("Bad session root file: " + root.toString)
    else queue
  }

  private def sessions_catalog(options: Options, dir: Path, catalog: Path, queue: Session.Queue)
    : Session.Queue =
  {
    val dirs =
      split_lines(File.read(catalog)).filterNot(line => line == "" || line.startsWith("#"))
    (queue /: dirs)((queue1, dir1) =>
      try {
        val dir2 = dir + Path.explode(dir1)
        if (dir2.is_dir) sessions_dir(options, true, dir2, queue1)
        else error("Bad session directory: " + dir2.toString)
      }
      catch {
        case ERROR(msg) =>
          error(msg + "\nThe error(s) above occurred in session catalog " + catalog.toString)
      })
  }

  def find_sessions(options: Options, more_dirs: List[Path]): Session.Queue =
  {
    var queue = Session.Queue.empty

    for (dir <- Isabelle_System.components()) {
      queue = sessions_dir(options, false, dir, queue)

      val catalog = dir + SESSIONS
      if (catalog.is_file) queue = sessions_catalog(options, dir, catalog, queue)
    }

    for (dir <- more_dirs) queue = sessions_dir(options, true, dir, queue)

    queue
  }



  /** build **/

  private def echo(msg: String) { java.lang.System.out.println(msg) }
  private def sleep(): Unit = Thread.sleep(500)


  /* source dependencies */

  sealed case class Node(
    loaded_theories: Set[String],
    sources: List[(Path, SHA1.Digest)])

  sealed case class Deps(deps: Map[String, Node])
  {
    def is_empty: Boolean = deps.isEmpty
    def sources(name: String): List[SHA1.Digest] = deps(name).sources.map(_._2)
  }

  def dependencies(verbose: Boolean, queue: Session.Queue): Deps =
    Deps((Map.empty[String, Node] /: queue.topological_order)(
      { case (deps, (name, info)) =>
          val preloaded =
            info.parent match {
              case None => Set.empty[String]
              case Some(parent) => deps(parent).loaded_theories
            }
          val thy_info = new Thy_Info(new Thy_Load(preloaded))

          if (verbose) {
            val groups =
              if (info.groups.isEmpty) ""
              else info.groups.mkString(" (", " ", ")")
            echo("Checking " + name + groups + " ...")
          }

          val thy_deps =
            thy_info.dependencies(
              info.theories.map(_._2).flatten.
                map(thy => Document.Node.Name(info.dir + Thy_Load.thy_path(thy))))

          val loaded_theories = preloaded ++ thy_deps.map(_._1.theory)

          val all_files =
            thy_deps.map({ case (n, h) =>
              val thy = Path.explode(n.node).expand
              val uses =
                h match {
                  case Exn.Res(d) =>
                    d.uses.map(p => (Path.explode(n.dir) + Path.explode(p._1)).expand)
                  case _ => Nil
                }
              thy :: uses
            }).flatten ::: info.files.map(file => info.dir + file)
          val sources =
            try { all_files.map(p => (p, SHA1.digest(p))) }
            catch {
              case ERROR(msg) =>
                error(msg + "\nThe error(s) above occurred in session " + quote(name))
            }

          deps + (name -> Node(loaded_theories, sources))
      }))


  /* jobs */

  private class Job(dir: Path, env: Map[String, String], script: String, args: String,
    val parent_heap: String, output: Path, do_output: Boolean)
  {
    private val args_file = File.tmp_file("args")
    private val env1 = env + ("ARGS_FILE" -> Isabelle_System.posix_path(args_file.getPath))
    File.write(args_file, args)

    private val (thread, result) =
      Simple_Thread.future("build") { Isabelle_System.bash_env(dir.file, env1, script) }

    def terminate: Unit = thread.interrupt
    def is_finished: Boolean = result.is_finished
    def join: (String, String, Int) = { val res = result.join; args_file.delete; res }
    def output_path: Option[Path] = if (do_output) Some(output) else None
  }

  private def start_job(name: String, info: Session.Info, parent_heap: String,
    output: Path, do_output: Boolean, options: Options, verbose: Boolean, browser_info: Path): Job =
  {
    // global browser info dir
    if (options.bool("browser_info") && !(browser_info + Path.explode("index.html")).is_file)
    {
      browser_info.file.mkdirs()
      File.copy(Path.explode("~~/lib/logo/isabelle.gif"),
        browser_info + Path.explode("isabelle.gif"))
      File.write(browser_info + Path.explode("index.html"),
        File.read(Path.explode("~~/lib/html/library_index_header.template")) +
        File.read(Path.explode("~~/lib/html/library_index_content.template")) +
        File.read(Path.explode("~~/lib/html/library_index_footer.template")))
    }

    val parent = info.parent.getOrElse("")

    val env =
      Map("INPUT" -> parent, "TARGET" -> name, "OUTPUT" -> Isabelle_System.standard_path(output))
    val script =
      if (is_pure(name)) {
        if (do_output) "./build " + name + " \"$OUTPUT\""
        else """ rm -f "$OUTPUT"; ./build """ + name
      }
      else {
        """
        . "$ISABELLE_HOME/lib/scripts/timestart.bash"
        """ +
          (if (do_output)
            """
            "$ISABELLE_PROCESS" -e "Build.build \"$ARGS_FILE\";" -q -w "$INPUT" "$OUTPUT"
            """
          else
            """
            rm -f "$OUTPUT"; "$ISABELLE_PROCESS" -e "Build.build \"$ARGS_FILE\";" -r -q "$INPUT"
            """) +
        """
        RC="$?"

        . "$ISABELLE_HOME/lib/scripts/timestop.bash"

        if [ "$RC" -eq 0 ]; then
          echo "Finished $TARGET ($TIMES_REPORT)" >&2
        fi

        exit "$RC"
        """
      }
    val args_xml =
    {
      import XML.Encode._
          pair(bool, pair(Options.encode, pair(bool, pair(Path.encode, pair(string,
            pair(string, list(pair(Options.encode, list(Path.encode)))))))))(
          (do_output, (options, (verbose, (browser_info, (parent,
            (name, info.theories)))))))
    }
    new Job(info.dir, env, script, YXML.string_of_body(args_xml), parent_heap, output, do_output)
  }


  /* log files and corresponding heaps */

  private val LOG = Path.explode("log")
  private def log(name: String): Path = LOG + Path.basic(name)
  private def log_gz(name: String): Path = log(name).ext("gz")

  private def sources_stamp(digests: List[SHA1.Digest]): String =
    digests.map(_.toString).sorted.mkString("sources: ", " ", "")

  private val no_heap: String = "heap: -"

  private def heap_stamp(heap: Option[Path]): String =
  {
    "heap: " +
      (heap match {
        case Some(path) =>
          val file = path.file
          if (file.isFile) file.length.toString + " " + file.lastModified.toString
          else "-"
        case None => "-"
      })
  }

  private def read_stamps(path: Path): Option[(String, String, String)] =
    if (path.is_file) {
      val stream = new GZIPInputStream (new BufferedInputStream(new FileInputStream(path.file)))
      val reader = new BufferedReader(new InputStreamReader(stream, Standard_System.charset))
      val (s, h1, h2) =
        try { (reader.readLine, reader.readLine, reader.readLine) }
        finally { reader.close }
      if (s != null && s.startsWith("sources: ") &&
          h1 != null && h1.startsWith("heap: ") &&
          h2 != null && h2.startsWith("heap: ")) Some((s, h1, h2))
      else None
    }
    else None


  /* build */

  def build(
    all_sessions: Boolean = false,
    build_heap: Boolean = false,
    clean_build: Boolean = false,
    more_dirs: List[Path] = Nil,
    session_groups: List[String] = Nil,
    max_jobs: Int = 1,
    no_build: Boolean = false,
    build_options: List[String] = Nil,
    system_mode: Boolean = false,
    verbose: Boolean = false,
    sessions: List[String] = Nil): Int =
  {
    val options = (Options.init() /: build_options)(_.define_simple(_))
    val (descendants, queue) =
      find_sessions(options, more_dirs).required(all_sessions, session_groups, sessions)
    val deps = dependencies(verbose, queue)

    def make_stamp(name: String): String =
      sources_stamp(queue(name).entry_digest :: deps.sources(name))

    val (input_dirs, output_dir, browser_info) =
      if (system_mode) {
        val output_dir = Path.explode("~~/heaps/$ML_IDENTIFIER")
        (List(output_dir), output_dir, Path.explode("~~/browser_info"))
      }
      else {
        val output_dir = Path.explode("$ISABELLE_OUTPUT")
        (output_dir :: Isabelle_System.find_logics_dirs(), output_dir,
         Path.explode("$ISABELLE_BROWSER_INFO"))
      }

    // prepare log dir
    (output_dir + LOG).file.mkdirs()

    // optional cleanup
    if (clean_build) {
      for (name <- descendants) {
        val files =
          List(Path.basic(name), log(name), log_gz(name)).map(output_dir + _).filter(_.is_file)
        if (!files.isEmpty) echo("Cleaning " + name + " ...")
        if (!files.forall(p => p.file.delete)) echo(name + " FAILED to delete")
      }
    }

    // scheduler loop
    case class Result(current: Boolean, heap: String, rc: Int)

    @tailrec def loop(
      pending: Session.Queue,
      running: Map[String, Job],
      results: Map[String, Result]): Map[String, Result] =
    {
      if (pending.is_empty) results
      else
        running.find({ case (_, job) => job.is_finished }) match {
          case Some((name, job)) =>
            // finish job

            val (out, err, rc) = job.join
            echo(Library.trim_line(err))

            val heap =
              if (rc == 0) {
                (output_dir + log(name)).file.delete

                val sources = make_stamp(name)
                val heap = heap_stamp(job.output_path)
                File.write_gzip(output_dir + log_gz(name),
                  sources + "\n" + job.parent_heap + "\n" + heap + "\n" + out)

                heap
              }
              else {
                (output_dir + Path.basic(name)).file.delete
                (output_dir + log_gz(name)).file.delete

                File.write(output_dir + log(name), out)
                echo(name + " FAILED")
                echo("(see also " + (output_dir + log(name)).file.toString + ")")
                val lines = split_lines(out)
                val tail = lines.drop(lines.length - 20 max 0)
                echo("\n" + cat_lines(tail))

                no_heap
              }
            loop(pending - name, running - name, results + (name -> Result(false, heap, rc)))

          case None if (running.size < (max_jobs max 1)) =>
            // check/start next job
            pending.dequeue(running.isDefinedAt(_)) match {
              case Some((name, info)) =>
                val parent_result =
                  info.parent match {
                    case None => Result(true, no_heap, 0)
                    case Some(parent) => results(parent)
                  }
                val output = output_dir + Path.basic(name)
                val do_output = build_heap || queue.is_inner(name)

                val (current, heap) =
                {
                  input_dirs.find(dir => (dir + log_gz(name)).is_file) match {
                    case Some(dir) =>
                      read_stamps(dir + log_gz(name)) match {
                        case Some((s, h1, h2)) =>
                          val heap = heap_stamp(Some(dir + Path.basic(name)))
                          (s == make_stamp(name) && h1 == parent_result.heap && h2 == heap &&
                            !(do_output && heap == no_heap), heap)
                        case None => (false, no_heap)
                      }
                    case None => (false, no_heap)
                  }
                }
                val all_current = current && parent_result.current

                if (all_current)
                  loop(pending - name, running, results + (name -> Result(true, heap, 0)))
                else if (no_build)
                  loop(pending - name, running, results + (name -> Result(false, heap, 1)))
                else if (parent_result.rc == 0) {
                  echo((if (do_output) "Building " else "Running ") + name + " ...")
                  val job =
                    start_job(name, info, parent_result.heap, output, do_output,
                      info.options, verbose, browser_info)
                  loop(pending, running + (name -> job), results)
                }
                else {
                  echo(name + " CANCELLED")
                  loop(pending - name, running, results + (name -> Result(false, heap, 1)))
                }
              case None => sleep(); loop(pending, running, results)
            }
          case None => sleep(); loop(pending, running, results)
        }
    }

    val results =
      if (deps.is_empty) {
        echo("### Nothing to build")
        Map.empty
      }
      else loop(queue, Map.empty, Map.empty)

    val rc = (0 /: results)({ case (rc1, (_, res)) => rc1 max res.rc })
    if (rc != 0 && (verbose || !no_build)) {
      val unfinished =
        (for ((name, res) <- results.iterator if res.rc != 0) yield name).toList.sorted
      echo("Unfinished session(s): " + commas(unfinished))
    }
    rc
  }


  /* command line entry point */

  def main(args: Array[String])
  {
    Command_Line.tool {
      args.toList match {
        case
          Properties.Value.Boolean(all_sessions) ::
          Properties.Value.Boolean(build_heap) ::
          Properties.Value.Boolean(clean_build) ::
          Properties.Value.Int(max_jobs) ::
          Properties.Value.Boolean(no_build) ::
          Properties.Value.Boolean(system_mode) ::
          Properties.Value.Boolean(verbose) ::
          Command_Line.Chunks(more_dirs, session_groups, build_options, sessions) =>
            build(all_sessions, build_heap, clean_build, more_dirs.map(Path.explode),
              session_groups, max_jobs, no_build, build_options, system_mode, verbose, sessions)
        case _ => error("Bad arguments:\n" + cat_lines(args))
      }
    }
  }
}

