package io.chymyst.jc


import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent._

import scala.concurrent.ExecutionContext
import Core._

/** A pool of execution threads, or another way of running tasks (could use actors or whatever else).
  * Tasks submitted for execution can have Chymyst-specific info (useful for debugging) when scheduled using `runReaction`.
  * The pool can be shut down, in which case all further tasks will be refused.
  *
  * @param name      Name assigned to the thread pool, used for debugging purposes.
  * @param priority  Thread group priority for this pool, such as [[Thread.NORM_PRIORITY]].
  * @param _reporter An instance of [[EmptyReporter]] that will be used to gather performance metrics for each reaction site using this thread pool.
  *                  By default, a [[ConsoleErrorReporter]] is assigned, which only logs run-time errors to the console.
  */
abstract class Pool(val name: String, val priority: Int, private[this] var _reporter: EventReporting) extends AutoCloseable {
  override val toString: String = s"${this.getClass.getSimpleName}:$name"

  private[jc] def startedBlockingCall(selfBlocking: Boolean): Unit

  private[jc] def finishedBlockingCall(selfBlocking: Boolean): Unit

  def parallelism: Int

  /** Create a new task queue. This is used to create the worker task queue and also to create the scheduler task queue.
    *
    * Possible implementations include [[LinkedBlockingQueue]] and [[LinkedTransferQueue]].
    * @return A new instance of a [[BlockingQueue]].
    */
  def createQueue: BlockingQueue[Runnable] = new LinkedTransferQueue[Runnable]()

  /** Run a reaction closure on the thread pool.
    * The reaction closure will be created by [[ReactionSite.reactionClosure]].
    *
    * @param closure A reaction closure to run.
    */
  private[chymyst] def runReaction(name: String, closure: ⇒ Unit): Unit = workerExecutor.execute(new Runnable {
    override def toString: String = name

    override def run(): Unit = closure
  })

  def isInactive: Boolean = workerExecutor.isShutdown || workerExecutor.isTerminated

  override def close(): Unit = shutdownNow()

  def recycleThreadTimeMs: Long = 1000L

  def shutdownWaitTimeMs: Long = 200L

  private val threadGroupName = toString + ",thread_group"

  private val threadNameBase = toString + ",worker_thread:"

  val threadGroup: ThreadGroup = {
    val tg = new ThreadGroup(threadGroupName)
    tg.setMaxPriority(priority)
    tg
  }

  private val schedulerQueue: BlockingQueue[Runnable] = createQueue

  private val schedulerThreadFactory: ThreadFactory = { (r: Runnable) ⇒ new Thread(threadGroup, r, toString + ",scheduler_thread") }

  private[jc] val schedulerExecutor: ThreadPoolExecutor = {
    val executor = new ThreadPoolExecutor(1, 1, recycleThreadTimeMs, TimeUnit.MILLISECONDS, schedulerQueue, schedulerThreadFactory)
    executor.allowCoreThreadTimeOut(true)
    executor
  }

  private[jc] def runScheduler(runnable: Runnable): Unit = schedulerExecutor.execute(runnable)

  private val workerQueue: BlockingQueue[Runnable] = createQueue

  private val workerThreadFactory: ThreadFactory = { (r: Runnable) ⇒ new ChymystThread(r, Pool.this) }

  protected val workerExecutor: ThreadPoolExecutor = {
    val executor = new ThreadPoolExecutor(parallelism, parallelism, recycleThreadTimeMs, TimeUnit.MILLISECONDS, workerQueue, workerThreadFactory)
    executor.allowCoreThreadTimeOut(true)
    executor
  }

  val executionContext: ExecutionContext = ExecutionContext.fromExecutor(workerExecutor)

  private val currentThreadId: AtomicInteger = new AtomicInteger(0)

  private[jc] def nextThreadName: String = threadNameBase + currentThreadId.getAndIncrement().toString

  /** Shut down the thread pool when required. This will interrupt all threads and clear the worker and the scheduler queues.
    *
    * Usually this is not needed in application code. Call this method in a situation when work has to be stopped immediately.
    */
  def shutdownNow(): Unit = new Thread {
    try {
      schedulerExecutor.getQueue.clear()
      schedulerExecutor.shutdown()
      schedulerExecutor.awaitTermination(shutdownWaitTimeMs, TimeUnit.MILLISECONDS)
      workerExecutor.getQueue.clear()
      workerExecutor.shutdown()
      workerExecutor.awaitTermination(shutdownWaitTimeMs, TimeUnit.MILLISECONDS)
    } finally {
      schedulerExecutor.shutdown()
      workerExecutor.shutdownNow()
      workerExecutor.awaitTermination(shutdownWaitTimeMs, TimeUnit.MILLISECONDS)
      workerExecutor.shutdownNow()
      ()
    }
  }.start()

  @inline def reporter: EventReporting = _reporter

  def reporter_=(r: EventReporting): Unit = {
    val reporterChanged = _reporter.asInstanceOf[EventReporting] =!= r
    if (reporterChanged) {
      reporter.reporterUnassigned(this, r)
      _reporter = r
      r.reporterAssigned(this)
    }
  }
}
