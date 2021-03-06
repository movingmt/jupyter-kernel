package jupyter.kernel
package client

import java.io.File
import scala.collection.mutable.ListBuffer
import argonaut._, Argonaut._
import stream.zmq.ZMQKernel

class KernelSpecs {
  private val _kernelsLock = new AnyRef
  private var _kernels = Map.empty[String, (KernelInfo, Kernel)]
  private var _defaultKernel = Option.empty[String]

  def add(id: String, info: KernelInfo, kernel: Kernel): Unit = _kernelsLock.synchronized {
    if (_kernels.isEmpty && _defaultKernel.isEmpty)
      _defaultKernel = Some(id)

    _kernels += id -> (info, kernel)
  }

  def setDefault(id: String): Unit = _kernelsLock.synchronized {
    _defaultKernel = Some(id)
  }

  def default: Option[String] = _kernelsLock.synchronized {
    _defaultKernel
      .orElse(Some(_kernels).filter(_.size == 1).map(_.head._1))
      .orElse(_kernels.map(_._1).filter(_ startsWith "scala").toList.sorted.lastOption)
      .orElse(_kernels.map(_._1).filter(_ startsWith "spark").toList.sorted.lastOption)
      .orElse(_kernels.map(_._1).toList.sorted.headOption)
  }

  def kernel(id: String): Option[Kernel] = _kernelsLock.synchronized {
    _kernels.get(id).map(_._2)
  }

  def kernelInfo(id: String): Option[KernelInfo] = _kernelsLock.synchronized {
    _kernels.get(id).map(_._1)
  }

  def kernels: Map[String, Kernel] = _kernelsLock.synchronized {
    _kernels.map{case (k, (_, v)) => k -> v }
  }

  def kernelsWithInfo: Map[String, (KernelInfo, Kernel)] = _kernelsLock.synchronized {
    _kernels
  }

  private def isWindows: Boolean =
    Option(System.getProperty("os.name")).exists(_ startsWith "Windows")

  def kernelSpecDirectories(): List[File] = {
    val kernelSpecDirs = new ListBuffer[File]

    val homeDirOption = Option(System getProperty "user.home").filterNot(_.isEmpty).orElse(sys.env.get("HOME").filterNot(_.isEmpty))
    homeDirOption match {
      case None =>
        Console.err println s"Cannot get user home dir, set one in the HOME environment variable"
      case Some(homeDir) =>
        kernelSpecDirs += (new File(homeDir) /: List(".ipython", "kernels"))(new File(_, _))
    }

    if (isWindows) {
      // IPython 3 doc (http://ipython.org/ipython-doc/3/development/kernels.html#kernelspecs)
      // says %PROGRAMDATA% instead of APPDATA here
      for (appData <- Option(System.getenv("APPDATA")))
        kernelSpecDirs += (new File(appData) /: List("jupyter", "kernels"))(new File(_, _))
    } else {
      kernelSpecDirs += new File("/usr/share/jupyter/kernels")
      kernelSpecDirs += new File("/usr/local/share/jupyter/kernels")
    }

    kernelSpecDirs.filter(_.isDirectory).result()
  }

  private val iPythonConnectionDir =
    (new File(System.getProperty("user.home")) /: Seq(".ipython", "profile_default", "security"))(new File(_, _))

  def loadFromKernelSpecs(): Unit = {
    var kernels = Map.empty[String, (KernelInfo, Kernel)]

    for {
      dir <- kernelSpecDirectories()
      kernelSpecDir <- Option(dir.listFiles()).getOrElse(Array.empty[File]) if kernelSpecDir.isDirectory
      // Priority is given to the first directory of a given kernel id here
      id = kernelSpecDir.getName if !kernels.contains(id)
      kernelJsonFile <- Some(new File(kernelSpecDir, "kernel.json")).filter(_.exists())
      kernelJson <- scala.io.Source.fromFile(kernelJsonFile).mkString.parseOption
      c = kernelJson.hcursor
      argv <- c.--\("argv").as[List[String]].toOption
      name <- c.--\("display_name").as[String].toOption
    }
      kernels += id -> (
        KernelInfo(
          name,
          c.--\("language").as[String].toOption getOrElse ""
        ),
        ZMQKernel(id, argv, iPythonConnectionDir)
      )

    // Erasing the previously defined kernels with the same ids here
    _kernels ++= kernels
  }
}
