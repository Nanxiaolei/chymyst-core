package io.chymyst.jc

import java.util.concurrent.atomic.AtomicReference

import io.chymyst.jc.Core._
import io.chymyst.util.Budu

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.Duration

/** Convenience syntax: provides an `unapply` operation, so that users can write the chemical notation, such as
  * `a(x) + b(y) + ...`, in reaction input patterns.
  * Pattern-matching can be used on molecule values at will, for example:
  * {{{go { case a(MyCaseClass(x, y)) + b(Some(z)) if x > z => ... } }}}
  *
  * The chemical notation should be used only with the left-associative `+` operator grouped to the left.
  * Input patterns with a right-associative grouping of the `+` operator, for example `a(x) + ( b(y) + c(z) )`, are rejected at compile time.
  */
object + {
  def unapply(inputs: ReactionBodyInput): ReactionBodyInput = inputs
}

/** Abstract container for molecule values. This is a common wrapper for values of blocking and non-blocking molecules.
  *
  * @tparam T Type of the molecule value.
  */
private[jc] sealed trait AbsMolValue[T] {
  private[jc] def moleculeValue: T

  /** The hash code of an [[AbsMolValue]] should not depend on anything but the wrapped value (of type `T`).
    * However, extending [[PersistentHashCode]] leads to errors!
    * (See the test "correctly store several molecule copies in a MutableQueueBag" in `ReactionSiteSpec.scala`.)
    * Therefore, we override the `hashCode` directly here, and make it a `lazy val`.
    */
  override lazy val hashCode: Int = moleculeValue.hashCode()

  /** String representation of molecule values will omit printing the `Unit` values but print all other types normally.
    *
    * @return String representation of molecule value of type T. Unit values are printed as empty strings.
    */
  override final def toString: String = moleculeValue match {
    case () => ""
    case v => v.toString
  }

  /** Checks whether the reaction has sent no reply to this molecule, and also that there was no error and no timeout with reply.
    * This check is meaningful only for blocking molecules and only after the reaction body has finished evaluating.
    *
    * @return `true` if the reaction has failed to send a reply to this instance of the blocking molecule.
    *         Will also return `false` if this molecule is not a blocking molecule.
    */
  // This method is in the parent trait only because we would like to check for missing replies faster,
  // without pattern-matching on blocking vs non-blocking molecules.
  private[jc] def reactionSentNoReply: Boolean = false

  private[jc] def fulfillWhenConsumedPromise(): Unit = ()
}

/** Container for the value of a non-blocking molecule.
  *
  * @tparam T The type of the value.
  */
private[jc] final case class MolValue[T](private[jc] val moleculeValue: T) extends AbsMolValue[T] {

  private var whenConsumedPromise: Option[Promise[T]] = None

  override private[jc] def fulfillWhenConsumedPromise(): Unit = {
    whenConsumedPromise.foreach(_.success(moleculeValue))
    whenConsumedPromise = None
  }

  private[jc] def whenConsumed: Future[T] = {
    val newPromise = Promise[T]()
    whenConsumedPromise = Some(newPromise)
    newPromise.future
  }
}

/** Container for the value of a blocking molecule.
  * The `hashCode` of a [[BlockingMolValue]] should depend only on the `hashCode` of the value `v`,
  * and not on the reply value (which is mutable). This is now implemented in the parent trait [[AbsMolValue]].
  *
  * @param replyEmitter The wrapper for the reply value, which will ultimately return a value of type R.
  * @tparam T The type of the value carried by the molecule.
  * @tparam R The type of the reply value.
  */
private[jc] final case class BlockingMolValue[T, R](
  private[jc] val moleculeValue: T,
  private[jc] val replyEmitter: ReplyEmitter[T, R]
) extends AbsMolValue[T] {

  override private[jc] def reactionSentNoReply: Boolean = replyEmitter.noReplyAttemptedYet // `true` if no value, no error, and no timeout

  def isEmpty: Boolean = false

  def get: BlockingMolValue[T, R] = this

  def _1: T = moleculeValue

  def _2: ReplyEmitter[T, R] = replyEmitter.asInstanceOf[ReplyEmitter[T, R]]
}

/** Abstract trait representing a molecule emitter.
  * This trait is not parameterized by type and is used in collections of molecules that do not require knowledge of molecule types.
  * Its only implementations are the (parameterized) classes [[B]]`[T, R]` and [[M]]`[T]`.
  */
sealed trait MolEmitter extends PersistentHashCode {

  /** The name of the molecule. Used only for debugging.
    * This will be assigned automatically if using the [[b]] or [[m]] macros to create a new molecule emitter.
    */
  val name: String

  /** Check whether the molecule has been automatically pipelined. */
  @inline def isPipelined: Boolean = valIsPipelined

  /** The type symbol corresponding to the value type of the molecule.
    * For instance, a molcule emitter defined as `val f = b[Int, String]` has type symbol `'Int`.
    *
    * @return A Scala [[Symbol]] representing the molecule value type, such as `'Unit`, `'Int` etc.
    */
  @inline def typeSymbol: Symbol = valTypeSymbol

  /** Global site-wide index that numbers all molecules bound to a given reaction site. */
  @inline private[jc] def siteIndex: MolSiteIndex = siteIndexValue

  /** This is called by a [[ReactionSite]] only once, for each molecule emitter when it first becomes bound to that reaction site.
    *
    * @param rs        Reaction site to which the molecule is now bound.
    * @param siteIndex Zero-based index of the input molecule at that reaction site.
    */
  private[jc] def setReactionSiteInfo(rs: ReactionSite, siteIndex: MolSiteIndex, typeSymbol: Symbol, pipelined: Boolean, selfBlocking: Option[Pool]): Unit = {
    hasReactionSite = true
    siteIndexValue = siteIndex
    valTypeSymbol = typeSymbol
    valIsPipelined = pipelined
    valSelfBlockingPool = selfBlocking
    reactionSiteValue = rs
  }

  /** Check whether the molecule is already bound to a reaction site.
    * Note that molecules can be emitted only if they are bound.
    *
    * @return `true` if already bound, `false` otherwise.
    */
  final def isBound: Boolean = hasReactionSite

  /** Check whether this molecule is already bound to a reaction site that's different from the given reaction site.
    *
    * @param rs A reaction site.
    * @return `None` if the molecule is not bound to any reaction site, or if it is bound to `rs`.
    *         Otherwise the molecule is already bound to a reaction site different from `rs`, so return
    *         the string representation of that reaction site as a non-empty option.
    */
  final private[jc] def isBoundToAnotherReactionSite(rs: ReactionSite): Option[String] =
    if (isBound && !(reactionSite.id === rs.id))
      Some(reactionSite.toString)
    else
      None

  // All these variables will be assigned exactly once and will never change thereafter. It's not clear how best to enforce this in Scala.
  private var valIsPipelined: Boolean = false

  private var reactionSiteValue: ReactionSite = _

  @inline protected def reactionSite: ReactionSite = reactionSiteValue

  private var valTypeSymbol: Symbol = _

  protected var valSelfBlockingPool: Option[Pool] = None

  private var siteIndexValue: MolSiteIndex = MolSiteIndex(-1)

  private var hasReactionSite: Boolean = false

  /** The list of reactions that can consume this molecule.
    *
    * Will be empty if the molecule emitter is not yet bound to any reaction site.
    * This value is used only for static analysis.
    */
  private[jc] lazy val consumingReactions: Array[Reaction] = {
    if (hasReactionSite)
      reactionSite.consumingReactions(siteIndex)
    else Array[Reaction]()
  }

  /** The set of all reactions that *potentially* emit this molecule as output.
    * Some of these reactions may evaluate a run-time condition to decide whether to emit the molecule; so emission is not guaranteed.
    *
    * Note that these reactions may be defined in any reaction sites, not necessarily at the site to which this molecule is bound.
    * The set of these reactions may change at run time if new reaction sites are written that output this molecule.
    *
    * This is used only during static analysis. This cannot be made a `lazy val` since static analysis can proceed before all emitting reactions are known.
    * Static analysis may be incomplete if that happens; but we can do little about this, since reaction sites are activated at run time.
    *
    * @return Empty set if the molecule is not yet bound to any reaction site.
    */
  final private[jc] def emittingReactions: Set[Reaction] = emittingReactionsSet.toSet

  private val emittingReactionsSet: mutable.Set[Reaction] = mutable.Set()

  // This is called by the reaction site only during the initial setup.
  // Each reaction site will add emitting reactions to all molecules it emits, including molecules bound to other reaction sites.
  // Once all reaction sites are activated, the set of emitting reactions for this molecule will never change.
  final private[jc] def addEmittingReaction(r: Reaction): Unit = {
    emittingReactionsSet += r
    ()
  }

  @inline final protected[jc] def ensureReactionSite[T](x: => T): T = {
    if (hasReactionSite)
      x
    else throw new ExceptionNoReactionSite(s"Molecule $this is not bound to any reaction site")
  }

  /** List all molecules (with their values) currently present at the reaction site to which this molecule emitter is bound.
    * This method is time-consuming and intended only for debugging, and should not be called within reactions.
    * If called from a reaction thread, it will return an empty string.
    */
  final def logSoup: String = ensureReactionSite {
    if (isChymystThread)
      "<logSoup is disabled on reaction threads!>"
    else reactionSite.printBag
  }

  def isBlocking: Boolean = false

  /** This is a `def` because we will only know whether this molecule is static after this molecule is bound to a reaction site, at run time.
    * The value `false` will be overridden by the [[M]] class (only non-blocking molecules can be static).
    */
  def isStatic: Boolean = false

  /** Prints a molecule's displayed name and a `/B` suffix for blocking molecules.
    *
    * @return A molecule's displayed name as string.
    */
  override final val toString: MolString = MolString((if (name.isEmpty) "<no name>" else name) + (if (isBlocking) "/B" else ""))

  // This is `Any` because we need to call this on a `MolEmitter`, which does not have a type parameter.
  // We could avoid this using a type downcast.
  private var whenEmittedPromise: Option[Promise[Any]] = None

  protected def whenEmittedFuture: Future[Any] = {
    val newPromise = Promise[Any]()
    whenEmittedPromise = Some(newPromise)
    newPromise.future
  }

  private[jc] def fulfillWhenEmittedPromise(t: Any): Unit = {
    whenEmittedPromise.foreach(_.success(t))
    whenEmittedPromise = None
  }

  private var whenScheduledPromise: Option[Promise[String]] = None

  protected def whenScheduledFuture: Future[String] = {
    val newPromise = Promise[String]()
    whenScheduledPromise = Some(newPromise)
    newPromise.future
  }

  private[jc] def succeedWhenScheduledPromise(molName: String): Unit = {
    whenScheduledPromise.foreach(_.success(molName))
    whenScheduledPromise = None
  }

  private final val noReactionScheduledException = new Exception(s"$this.whenScheduled() failed because no reaction could be scheduled (this is not an error)")

  private[jc] def failWhenScheduledPromise(): Unit = {
    whenScheduledPromise.foreach(_.failure(noReactionScheduledException))
    whenScheduledPromise = None
  }
}

/** Non-blocking molecule class. Instance is mutable until the molecule is bound to a reaction site and until all reactions involving this molecule are declared.
  *
  * @param name Name of the molecule, used for debugging only.
  * @tparam T Type of the value carried by the molecule.
  */
final class M[T](val name: String) extends (T => Unit) with MolEmitter {

  def unapply(arg: ReactionBodyInput): Wrap[T] = {
    val v = arg.inputs(arg.index).asInstanceOf[MolValue[T]].moleculeValue
    Wrap(v)
  }

  /** Emit a non-blocking molecule.
    *
    * Note that static molecules can be emitted only by a reaction that consumed them, and not by other code.
    *
    * @param v Value to be put onto the emitted molecule.
    */
  def apply(v: T): Unit = ensureReactionSite {
    if (isStatic)
      throw new ExceptionEmittingStaticMol(s"Error: static molecule $this($v) cannot be emitted non-statically")
    else applyStatic(v)
  }

  def apply()(implicit arg: TypeMustBeUnit[T]): Unit = (apply(arg.getUnit): @inline)

  def applyStatic(v: T): Unit = reactionSite.emit(this, MolValue(v))

  def applyStatic()(implicit arg: TypeMustBeUnit[T]): Unit = (applyStatic(arg.getUnit): @inline)

  /** Volatile reader for a molecule.
    * The molecule must be declared as static.
    *
    * @return The value carried by the static molecule when it was last emitted. Will throw exception if the static molecule has not yet been emitted.
    */
  def volatileValue: T = if (isBound) {
    if (isStatic)
      volatileValueRef.get
    else throw new Exception(s"In $reactionSite: volatile reader requested for non-static molecule ($this)")
  }
  else throw new Exception(s"Molecule $name is not bound to any reaction site, cannot read volatile value")

  private[jc] def assignStaticMolVolatileValue(molValue: AbsMolValue[_]) =
    volatileValueRef.set(molValue.asInstanceOf[MolValue[T]].moleculeValue)

  private val volatileValueRef: AtomicReference[T] = new AtomicReference[T]()

  override lazy val isStatic: Boolean = reactionSite.staticMolDeclared.contains(this)

  override private[jc] def setReactionSiteInfo(rs: ReactionSite, index: MolSiteIndex, valType: Symbol, pipelined: Boolean, selfBlocking: Option[Pool]) = {
    super.setReactionSiteInfo(rs, index, valType, pipelined, selfBlocking)
  }

  /** Define the next emission event. The resulting `Future` will resolve once, at the next time this molecule is emitted.
    *
    * @return `Future[T]` holding the value of type `T` that will be carried by the emitted molecule.
    */
  def whenEmitted: Future[T] = whenEmittedFuture.asInstanceOf[Future[T]]

  /** Emit a molecule with value `v`, and define the corresponding consumption event.
    * The resulting `Future` will resolve once, when some reaction consumes the molecule value just emitted now.
    *
    * @param v Value of the molecule, to be emitted now.
    * @return `Future[T]` holding the value of type `T` that is consumed by reaction.
    */
  def emitUntilConsumed(v: T): Future[T] =
    if (isChymystThread)
      Promise[T]().failure(exceptionDisallowedWhenConsumed).future
    else ensureReactionSite {
      if (isStatic)
        throw new ExceptionEmittingStaticMol(s"Error: static molecule $this($v) cannot be emitted non-statically")
      else {
        val mv = MolValue(v)
        val fut = mv.whenConsumed
        reactionSite.emit(this, mv)
        fut
      }
    }

  def emitUntilConsumed()(implicit arg: TypeMustBeUnit[T]): Future[T] = (emitUntilConsumed(arg.getUnit): @inline)

  private val exceptionDisallowedWhenConsumed = new Exception(s"emitUntilConsumed() is disallowed on reaction threads (molecule: $this)")

  /** Define the scheduler decision event for reactions consuming this molecule.
    * The resulting `Future` will resolve successfully when some reaction could be found that consumes some copy of this molecule,
    * and will fail if no reaction consuming this molecule can start at the next time scheduling decisions are made.
    *
    * Note that the scheduler may be looking for reactions consuming another emitted molecule and, as a result, schedule a
    * reaction consuming a copy of `this` molecule.
    * In this case, the returned `Future` will also resolve successfully.
    *
    * The resolved `String` value of the `Future` shows the name of the molecule for which the scheduler decision was made.
    * This does not necessarily coincide with the molecule on which `whenScheduled()` is called.
    *
    * @return `Future[String]` that either succeeds, with the name of the molecule, or fails.
    */
  def whenScheduled: Future[String] =
    if (isChymystThread)
      Promise[String]().failure(exceptionDisallowedwhenScheduled).future
    else whenScheduledFuture

  private val exceptionDisallowedwhenScheduled = new Exception(s"whenScheduled() is disallowed on reaction threads (molecule: $this)")
}

/** Reply emitter for blocking molecules. This is a mutable class that holds the reply value and monitors time-out status.
  *
  * @tparam T Type of the value that the molecule carries.
  * @tparam R Type of the reply value.
  */
private[jc] final class ReplyEmitter[T, R](useFuture: Boolean) extends (R => Boolean) {
  @inline private[jc] val reply = Budu[R](useFuture)

  /** Check whether this reply emitter has been already used to send a reply.
    * This check does not depend on whether the process that was waiting for the reply timed out or not.
    *
    * @return `true` if the reply emitter has not yet been used, `false` otherwise.
    */
  def noReplyAttemptedYet: Boolean = reply.isEmpty

  /** Perform a reply action for a blocking molecule with a check of the timeout status.
    *
    * This is called by a reaction that consumed the blocking molecule.
    * The reply value will be received by the process that emitted the blocking molecule, and will unblock that process.
    * The reply value will not be received if the emitting process timed out on the blocking call, or if the reply was
    * already made.
    * If a reply was already made then the call to `apply()` will be ignored.
    * However, static analysis prohibits reactions that reply more than once or do not have any code that sends a reply.
    *
    * For each blocking molecule consumed by a reaction, exactly one reply action should be performed within the reaction body.
    * If a timeout occurred after the reaction body started evaluating but before the reply action was performed, the reply value will not be actually sent anywhere.
    * This method will return `false` in that case. Otherwise it will return `true`.
    *
    * @param r Value to reply with.
    * @return Unit value, regardless of whether the reply succeeded before timeout.
    */
  def apply(r: R): Boolean = reply.is(r)

  /** Same but for molecules with type `R = Unit`. */
  def apply()(implicit arg: TypeMustBeUnit[R]): Boolean = (apply(arg.getUnit): @inline)
}

/** Blocking molecule class. Instance is mutable until the molecule is bound to a reaction site and until all reactions involving this molecule are declared.
  *
  * @param name Name of the molecule, used for debugging only.
  * @tparam T Type of the value carried by the molecule.
  * @tparam R Type of the value replied to the caller via the "reply" action.
  */
final class B[T, R](val name: String) extends (T => R) with MolEmitter {
  override def isBlocking = true

  /** Emit a blocking molecule and receive a value when the reply action is performed, unless a timeout is reached.
    *
    * @param duration Timeout in any time interval.
    * @param v        Value to be put onto the emitted molecule.
    * @return Non-empty option if the reply was received; None on timeout.
    */
  def timeout(v: T)(duration: Duration): Option[R] =
    reactionSite.emitAndAwaitReplyWithTimeout(duration, this, v)

  /** Same but for molecules with type `T == Unit`; enables shorter syntax `b.timeout()(1.second)`. */
  def timeout()(duration: Duration)(implicit arg: TypeMustBeUnit[T]): Option[R] = (timeout(arg.getUnit)(duration): @inline)

  /** Perform the unapply matching and return a named extractor on success.
    * The extractor will always succeed, yielding the molecule value held by a [[BlockingMolValue]].
    *
    * @param arg The input molecule list and the index into that list, indicating which molecule value we need.
    * @return An instance of [[BlockingMolValue]] that plays the role of its own extractor.
    */
  def unapply(arg: ReactionBodyInput): BlockingMolValue[T, R] = {
    arg.inputs(arg.index).asInstanceOf[BlockingMolValue[T, R]]
  }

  /** Emit a blocking molecule and receive a value when the reply action is performed.
    *
    * @param v Value to be put onto the emitted molecule.
    * @return The "reply" value.
    */
  def apply(v: T): R = ensureReactionSite {
    reactionSite.emitAndAwaitReply(this, v)
  }

  /** This enables the short syntax `b()` instead of `b(())`, and will only work when `T == Unit`. */
  def apply()(implicit arg: TypeMustBeUnit[T]): R = (apply(arg.getUnit): @inline)

  /** Emit a blocking molecule and return a [[Future]]`[R]` that completes when the reply is sent.
    * Here, `R` is the type of the reply value.
    *
    * @param v The value of type `T` carried by the newly emitted blocking molecule.
    * @return A [[Future]]`[R]` value that represents the future reply of type `R`.
    */
  def futureReply(v: T): Future[R] = ensureReactionSite {
    reactionSite.emitAndGetFutureReply(this, v)
  }

  /** This enables the short syntax `b.futureReply()` instead of `b.futureReply(())`, and will only work when `T == Unit`. */
  def futureReply()(implicit arg: TypeMustBeUnit[T]): Future[R] = (futureReply(arg.getUnit): @inline)

  /** Returns `true` if the molecule belongs to a reaction running on a fixed pool that also */
  private[jc] def isSelfBlocking: Boolean = valSelfBlockingPool.exists { pool ⇒
    Thread.currentThread match {
      case t: ChymystThread ⇒
        t.pool === pool
      case _ ⇒ false
    }
  }

  def whenEmitted: Future[T] = whenEmittedFuture.asInstanceOf[Future[T]]
}

/** Mix this trait into your class to make the has code persistent after the first time it's computed.
  *
  */
sealed trait PersistentHashCode {
  private lazy val hashCodeValue: Int = super.hashCode()

  override def hashCode(): Int = hashCodeValue
}

/** Wrapper for `unapply()`. According to https://github.com/scala/scala/pull/2848 the `unapply()` function can return any
  * type that directly contains methods `isEmpty: Boolean` and `get: T` where `T` can be either a tuple type with extractors _1, _2 etc.,
  * or another type.
  * (This is the "named extractor API".)
  *
  * This wrapper is for wrapping a value that is unconditionally returned by `unapply()`, as molecule extractors must do.
  * Since that value is of an unknown type `T`, we can't add the named extractor API on top of that type. So we must use this wrapper.
  *
  * @param x Molecule value wrapped and to be returned by `unapply()`.
  * @tparam T Type of the molecule value.
  */
final case class Wrap[T](x: T) extends AnyVal {
  def isEmpty: Boolean = false

  def get: T = x
}

/** This type is used as argument for [[ReactionBody]], and can serve as its own extractor because it implements the named extractors API.
  * The methods `isEmpty`, `get`, `_1`, `_2` are needed to implement the named extractor API.
  *
  * @param index  Index into the [[InputMoleculeList]] array that indicates the molecule value for the current molecule.
  * @param inputs An [[InputMoleculeList]] array.
  */
private[jc] final case class ReactionBodyInput(index: Int, inputs: InputMoleculeList) {
  def isEmpty: Boolean = false

  def get: ReactionBodyInput = this

  def _1: ReactionBodyInput = this.copy(index = this.index - 1)

  def _2: ReactionBodyInput = this
}
