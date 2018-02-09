// See LICENSE for license details.

package chisel3.testers2

import scala.collection.mutable
import java.util.concurrent.Semaphore

import chisel3._

trait AbstractTesterThread

/** Common interface definition for tester backends
  */
trait BackendInterface {
  /** Writes a value to a writable wire.
    * Throws an exception if write is not writable.
    */
  def poke(signal: Data, value: BigInt): Unit

  /** Returns the current combinational value (after any previous pokes have taken effect, and
    * logic has propagated) on a wire.
    */
  def peek(signal: Data): BigInt
  /** Returns the value at the beginning of the current cycle (before any pokes have taken effect) on a wire.
    */
  def stalePeek(signal: Data): BigInt

  def check(signal: Data, value: BigInt): Unit
  def staleCheck(signal: Data, value: BigInt): Unit

  /** Advances the target clock by one cycle.
    */
  def step(signal: Clock, cycles: Int): Unit

  def fork(runnable: => Unit): AbstractTesterThread

  def join(thread: AbstractTesterThread)
}

/** Backend associated with a particular circuit, and can run tests
  */
trait BackendInstance[T <: Module] extends BackendInterface {
  /** Runs of tests are wrapped in this, for any special setup/teardown that needs to happen.
    * Takes the test function, which takes the module used as the testing interface.
    * TesterContext setup is done externally.
    *
    * Internal API
    */
  def run(testFn: T => Unit): Unit
}

/** Common utility functions for backends implementing concurrency by threading.
  * The backend must invoke concurrency functions as appropriate, eg during step() calls
  */
trait ThreadedBackend {
  protected class TesterThread(runnable: => Unit, isMainThread: Boolean) extends AbstractTesterThread {
    val waiting = new Semaphore(0)
    val thread = new Thread(new Runnable {
      def run() {
        waiting.acquire()
        runnable
        if (!isMainThread) {
          threadFinished(TesterThread.this)
        } // otherwise main thread falls off the edge without running the next thread
        // TODO: should main thread at least finish its cycle?
      }
    })
  }

  val threadMap = mutable.HashMap[Thread, TesterThread]()
  val threadOrder = mutable.ArrayBuffer[TesterThread]()
  val waitingThreads = mutable.ArrayBuffer[TesterThread]()

  /** Invokes the thread scheduler, which unblocks the next thread to be run
    * (and may also step simulator time).
    */
  protected def scheduler()

  protected def threadFinished(thread: TesterThread) {
    threadMap -= thread.thread
    threadOrder -= thread
    scheduler()
    // TODO: join notification
  }

  def fork(runnable: => Unit, isMainThread: Boolean): TesterThread = {
    val newThread = new TesterThread(runnable, isMainThread)
    threadMap += ((newThread.thread, newThread))
    threadOrder += newThread
    waitingThreads += newThread
    newThread.thread.start()
    newThread
  }

  def fork(runnable: => Unit): TesterThread = fork(runnable, false)

  def join(thread: AbstractTesterThread) = ???
}


/** Interface into the testing environment, like ScalaTest
  */
trait TestEnvInterface {
  def testerFail(msg: String): Unit
  def testerExpect(expected: Any, actual: Any, signal: String, msg: Option[String]): Unit
}
