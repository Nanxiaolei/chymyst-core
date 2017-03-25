package io.chymyst.jc

import Core._
import io.chymyst.jc.CrossMoleculeSorting.Coll

import scala.{Symbol => ScalaSymbol}
import scala.collection.mutable

/** Represents compile-time information about the pattern matching for values carried by input molecules.
  * Possibilities:
  * `a(_)` is represented by [[WildcardInput]]
  * `a(x)` is represented by [[SimpleVarInput]] with value `SimpleVar(v = 'x, cond = None)`
  * `a(x) if x > 0` is represented by [[SimpleVarInput]] with value `SimpleVar(v = 'x, cond = Some({ case x : Int if x > 0 => }))`
  * `a(Some(1))` is represented by [[ConstInputPattern]] with value `SimpleConst(v = Some(1))`
  * `a( (x, Some((y,z)))) ) if x > y` is represented by [[OtherInputPattern]] with value
  * {{{OtherInputPattern(matcher = { case (x, Some((y,z)))) if x > y => }, vars = List('x, 'y, 'z), isIrrefutable = false)}}}
  */
sealed trait InputPatternType {
  val isConstantValue: Boolean = false

  /** Detect whether the input pattern is irrefutable and will always match any given value.
    * An irrefutable pattern does not constrain the input value but merely puts variables on the value or on its parts.
    *
    * Examples of irrefutable patterns are `a(_)`, `a(x)`, and `a( z@(x,y,_) )`, where `a(...)` is a molecule with a suitable value type.
    * Examples of refutable patterns are `a(1)`, `a(Some(x))`, `a( (_, None) )`.
    *
    * @return `true` if the pattern is irrefutable, `false` otherwise.
    */
  def isIrrefutable: Boolean = false
}

case object WildcardInput extends InputPatternType {
  override def isIrrefutable: Boolean = true
}

final case class SimpleVarInput(v: ScalaSymbol, cond: Option[PartialFunction[Any, Unit]]) extends InputPatternType {
  override def isIrrefutable: Boolean = cond.isEmpty
}

/** Represents molecules that have constant pattern matchers, such as `a(1)`.
  * Constant pattern matchers are either literal values (`Int`, `String`, `Symbol`, etc.) or special values such as `None`, `Nil`, `()`,
  * as well as `Some`, `Left`, `Right`, `List`, and tuples of constant matchers.
  *
  * @param v Value of the constant. This is nominally of type `Any` but actually is of the molecule's value type `T`.
  */
final case class ConstInputPattern(v: Any) extends InputPatternType {
  override val isConstantValue: Boolean = true
}

/** Represents a general pattern that is neither a wildcard nor a single variable nor a constant.
  * Examples of such patterns are `a(Some(x))` and `a( (x, _, 2, List(a, b)) )`.
  *
  * A pattern is recognized to be _irrefutable_ when it is a tuple where all places are either simple variables or wildcards.
  * For example, `a( z@(x, y, _) )` is an irrefutable pattern for a 3-tuple type.
  * On the other hand, `a( (x, _, Some(_) ) )` is not irrefutable because it fails to match `a( (_, _, None) )`.
  * Another recognized case of irrefutable patterns is a single case class that extends a sealed trait.
  *
  * @param matcher     Partial function that applies to the argument when the pattern matches.
  * @param vars        List of symbols representing the variables used in the pattern, in the left-to-right order.
  * @param irrefutable `true` if the pattern will always match the argument of the correct type, `false` otherwise.
  */
final case class OtherInputPattern(matcher: PartialFunction[Any, Unit], vars: List[ScalaSymbol], irrefutable: Boolean) extends InputPatternType {
  override def isIrrefutable: Boolean = irrefutable
}

/** Represents the value pattern of an emitted output molecule.
  * We distinguish only constant value patterns and all other patterns.
  */
sealed trait OutputPatternType {
  val specificity: Int

  def merge(other: OutputPatternType)(equals: (Any, Any) => Boolean = (a, b) => a === b): OutputPatternType = OtherOutputPattern
}

final case class ConstOutputPattern(v: Any) extends OutputPatternType {
  override def merge(other: OutputPatternType)(equals: (Any, Any) => Boolean = (a, b) => a === b): OutputPatternType =
    other match {
      case ConstOutputPattern(c) if equals(c, v) =>
        this
      case _ =>
        OtherOutputPattern
    }

  override val specificity = 0
}

case object OtherOutputPattern extends OutputPatternType {
  override val specificity = 1
}

/** Describe the code environment within which an output molecule is being emitted.
  * Possible environments are [[ChooserBlock]] describing an `if` or `match` expression with clauses,
  * and a function call [[FuncBlock]].
  *
  * For example, `if (x>0) a(x) else b(x)` is a chooser block environment with 2 clauses,
  * while `(1 to 10).foreach(a)` is a function block environment
  * and `(x) => a(x)` is a [[FuncLambda]] environment.
  */
sealed trait OutputEnvironment {
  /** This is to `true` if the output environment is guaranteed to emit the molecule at least once.
    * This is `false` for most environments.
    */
  val atLeastOne: Boolean = false

  /** Each output environment is identified by an integer Id number.
    *
    * @return The Id number of the output environment.
    */
  def id: Int

  val linear: Boolean = false
  val shrinkable: Boolean = false
}

/** Describes a molecule emitted in a chooser clause, that is, in an `if-then-else` or `match-case` construct.
  *
  * @param id     Id of the chooser construct.
  * @param clause Zero-based index of the clause.
  * @param total  Total number of clauses in the chooser constructs (2 for `if-then-else`, 2 or greater for `match-case`).
  */
final case class ChooserBlock(id: Int, clause: Int, total: Int) extends OutputEnvironment {
  override val atLeastOne: Boolean = total === 1
  override val linear: Boolean = true
  override val shrinkable: Boolean = true
}

/** Describes a molecule emitted under a function call.
  *
  * @param id   Id of the function call construct.
  * @param name Fully qualified name of the function call, for example `"scala.collection.TraversableLike.map"`.
  */
final case class FuncBlock(id: Int, name: String) extends OutputEnvironment

/** Describes a molecule emitted under an anonymous function.
  *
  * @param id Id of the anonymous function construct.
  */
final case class FuncLambda(id: Int) extends OutputEnvironment

/** Describes an output environment that is guaranteed to emit the molecule at least once. This is currently used only in a do-while construct.
  *
  * @param id   Id of the output environment construct.
  * @param name Name of the construct: one of `"do while"`, `"condition of while"`, or `"condition of do while"`.
  */
final case class AtLeastOneEmitted(id: Int, name: String) extends OutputEnvironment {
  override val atLeastOne: Boolean = true
  override val shrinkable: Boolean = true
}

private[jc] object OutputEnvironment {
  private[jc] type OutputItem[T] = (T, OutputPatternType, List[OutputEnvironment])
  private[jc] type OutputList[T] = List[OutputItem[T]]

  private[jc] def shrink[T](outputs: OutputList[T], equals: (Any, Any) => Boolean = (a, b) => a === b): OutputList[T] = {
    outputs.foldLeft[(OutputList[T], OutputList[T])]((Nil, outputs)) { (accOutputs, outputInfo) =>
      val (outputList, remaining) = accOutputs
      if (remaining contains outputInfo) {
        // TODO: make this algorithm general. Support all possible environments.
        // In the `remaining`, find all other molecules of the same sort that could help us shrink `outputInfo`.
        // Remove those molecules from `remaining`. Merge our flag with their flags.
        // When we are done merging (can't shrink any more), add the new molecule info to `outputList`.

        val newRemaining = remaining difff List(outputInfo)
        // Is this molecule already assured? If so, skip it and continue.
        val isAssured = outputInfo._3.forall(_.atLeastOne)
        // Is this molecule shrinkable? If not, we have to skip it and continue.
        val isShrinkable = outputInfo._3.forall(_.shrinkable)
        if (isAssured || !isShrinkable) {
          (outputList :+ outputInfo, newRemaining)
        } else {
          // First approximation to the full shrinkage algorithm: Only process one level of `ChooserBlock`.
          outputInfo._3.headOption match {
            case Some(ChooserBlock(id, clause, total)) if outputInfo._3.size === 1 =>
              // Find all other output molecules with the same id of ChooserBlock. Include this molecule too (so we use `remaining` here, instead of `newRemaining`).
              val others = remaining.filter { case (t, _, envs) =>
                t === outputInfo._1 &&
                  envs.headOption.exists(_.id === id) &&
                  envs.size === 1
              }.sortBy { case (_, outPattern, _) => outPattern.merge(outputInfo._2)(equals).specificity }
              // This will sort first by clause and then all other molecules that match us if they contain a constant, and then all others.
              // The molecules in this list are now sorted. We expect to find `total` molecules.
              // Go through the list in sorted order, removing molecules that have clause number 0, ..., total-1.
              val res = (0 until total).flatFoldLeft[(OutputList[T], OutputPatternType)]((others, outputInfo._2)) { (acc, newClause) =>
                val (othersRemaining, newFlag) = acc
                val found = othersRemaining.find {
                  case (t, _, List(ChooserBlock(_, `newClause`, `total`))) =>
                    true
                  case _ =>
                    false
                }
                // If `found` == None, we stop. Otherwise, we remove it from `othersRemaining` and merge the obtained flag into `newFlag`.
                found map {
                  case item@(t, outputPattern, _) => (othersRemaining difff List(item), newFlag.merge(outputPattern)(equals))
                }
              }
              res match {
                case None =>
                  (outputList :+ outputInfo, newRemaining)
                case Some((newRemainingOut, newFlag)) => // New output info contains an empty env since we shrank everything.
                  (outputList :+ ((outputInfo._1, newFlag, List())), newRemainingOut)
              }
            case _ =>
              (outputList :+ outputInfo, newRemaining)
          }
        }
      } else // This molecule has been used already while shrinking others, so we just skip it and go on.
        accOutputs
    }._1
  }
}

/** Indicates whether a reaction has a guard condition.
  *
  */
sealed trait GuardPresenceFlag {
  /** Calls a reaction's static guard to check whether the reaction is permitted to start, before examining any molecule values.
    *
    * A static guard is a reaction guard that does not depend on molecule values.
    * For example, `go { case a(x) if n > 0 && x < n => ...}` contains a static guard `n > 0` and a non-static guard `x < n`.
    * A static guard could depend on mutable global values, such as `n`, and so it is evaluated each time.
    *
    * Note that the static guard could be evaluated even if the reaction site does not have enough input molecules for the reaction to start.
    * Avoid putting side effects into the static guard!
    *
    * @return `true` if the reaction's static guard returns `true` or is absent.
    *         `false` if the reaction has a static guard, and if the guard returns `false`.
    */
  def staticGuardHolds(): Boolean = true

  /** Checks whether the reaction has no cross-molecule guard conditions, that is,
    * conditions that cannot be factorized as conjunctions of conditions that each constrain individual molecules.
    *
    * For example, `go { case a(x) + b(y) if x > y => }` has a cross-molecule guard condition,
    * whereas `go { case a(x) + b(y) if x > 1 && y < 2 => }` has no cross-molecule guard conditions because its guard condition
    * can be split into a conjunction of guard conditions that each constrain the value of one molecule.
    *
    * @return `true` if the reaction has no guard condition, or if it has guard conditions that can be split between molecules;
    *         `false` if the reaction has at least one cross-molecule guard condition.
    */
  val noCrossGuards: Boolean = true
}

/** Indicates whether guard conditions are required for this reaction to start.
  *
  * The guard is parsed into a flat conjunction of guard clauses, which are then analyzed for cross-dependencies between molecules.
  *
  * For example, consider the reaction
  * {{{ go { case a(x) + b(y) + c(z) if x > n && y > 0 && y > z && n > 1 => ...} }}}
  * Here `n` is an integer value defined outside the reaction.
  *
  * The conditions for starting this reaction is that `a(x)` has value `x` such that `x > n`; that `b(y)` has value `y` such that `y > 0`;
  * that `c(z)` has value `z` such that `y > z`; and finally that `n > 1`, independently of any molecule values.
  * The condition `n > 1` is a static guard. The condition `x > n` restricts only the molecule `a(x)` and therefore can be moved out of the guard
  * into the per-molecule conditional inside [[InputMoleculeInfo]] for that molecule. Similarly, the condition `y > 0` can be moved out of the guard.
  * However, the condition `y > z` relates two different molecule values; it is a cross-molecule guard.
  *
  * Any guard condition given by the reaction code will be converted to the Conjunctive Normal Form, and split into a static guard,
  * a set of per-molecule conditionals, and a set of cross-molecule guards.
  *
  * @param staticGuard The conjunction of all the clauses of the guard that are independent of pattern variables. This closure can be called in order to determine whether the reaction should even be considered to start, regardless of the presence of molecules. In this example, the value of `staticGuard` will be `Some(() => n > 1)`.
  * @param crossGuards A list of values of type [[CrossMoleculeGuard]], each representing one cross-molecule clauses of the guard. The partial function `Any => Unit` should be called with the arguments representing the tuples of pattern variables from each molecule used by the cross guard.
  *                    In the present example, the value of `crossGuards` will be an array with the single element
  *                    {{{ CrossMoleculeGuard(indices = Array(1, 2), List((List('y, 'z), { case List(y: Int, z: Int) if y > z => () }))) }}}
  */
final case class GuardPresent(staticGuard: Option[() => Boolean], crossGuards: Array[CrossMoleculeGuard]) extends GuardPresenceFlag {
  override def staticGuardHolds(): Boolean = staticGuard.forall(guardFunction => guardFunction())

  override val noCrossGuards: Boolean = staticGuard.isEmpty && crossGuards.isEmpty

  override val toString: String =
    s"GuardPresent(${staticGuard.map(_ => "")}, [${crossGuards.map(_.toString).mkString("; ")}])"
}

/** Indicates that a guard was initially present but has been simplified, or it was absent but some molecules have nontrivial pattern matchers (not a wildcard and not a simple variable).
  * In any case, no cross-molecule guard conditions need to be checked for this reaction to start.
  */
case object GuardAbsent extends GuardPresenceFlag

/** Indicates that a guard was initially absent and, in addition, all molecules have trivial matchers - this reaction can start with any molecule values. */
case object AllMatchersAreTrivial extends GuardPresenceFlag

/** Represents the structure of the cross-molecule guard condition for a reaction.
  * A cross-molecule guard constrains values of several molecules at once.
  *
  * @param indices Integer indices of affected molecules in the reaction input.
  * @param symbols Symbols of variables used by the guard condition.
  * @param cond    Partial function that applies to its argument, of type `List[Any]`, if the cross-molecule guard evaluates to `true` on these values.
  *                The arguments of the partial function must correspond to the values of the affected molecules, in the order of the reaction input.
  */
final case class CrossMoleculeGuard(indices: Array[Int], symbols: Array[ScalaSymbol], cond: PartialFunction[List[Any], Unit]) {
  override val toString: String = s"CrossMoleculeGuard([${indices.mkString(",")}], [${symbols.mkString(",")}])"
}

/** Compile-time information about an input molecule pattern in a certain reaction where the molecule is consumed.
  *
  * @param molecule The molecule emitter value that represents the input molecule.
  * @param index    Zero-based index of this molecule in the input list of the reaction.
  * @param flag     A value of type [[InputPatternType]] that describes the value pattern: wildcard, constant match, etc.
  * @param sha1     Hash sum of the input pattern's source code (desugared Scala representation).
  * @param valType  String representation of the type `T` of the molecule's value, e.g. for [[M]]`[T]` or [[B]]`[T, R]`.
  */
final case class InputMoleculeInfo(molecule: Molecule, index: Int, flag: InputPatternType, sha1: String, valType: ScalaSymbol) {
  val isConstantValue: Boolean = flag.isConstantValue

  private[jc] def admitsValue(molValue: AbsMolValue[_]): Boolean = flag match {
    case WildcardInput | SimpleVarInput(_, None) =>
      true
    case SimpleVarInput(v, Some(cond)) =>
      cond.isDefinedAt(molValue.moleculeValue)
    case ConstInputPattern(v) =>
      v === molValue.moleculeValue
    case OtherInputPattern(_, _, true) =>
      true
    case OtherInputPattern(matcher, _, _) =>
      matcher.isDefinedAt(molValue.moleculeValue)
  }

  private[jc] val isSimpleType: Boolean = simpleTypes contains valType

  /** Determine whether this input molecule pattern is weaker than another pattern.
    * Pattern a(xxx) is weaker than b(yyy) if a==b and if anything matched by yyy will also be matched by xxx.
    *
    * @param info The input molecule info for another input molecule.
    * @return Some(true) if we can surely determine that this matcher is weaker than another;
    *         Some(false) if we can surely determine that this matcher is not weaker than another;
    *         None if we cannot determine anything because information is insufficient.
    */
  private[jc] def matcherIsWeakerThan(info: InputMoleculeInfo): Option[Boolean] = {
    if (molecule =!= info.molecule)
      Some(false)
    else flag match {
      case WildcardInput |
           SimpleVarInput(_, None) |
           OtherInputPattern(_, _, true) =>
        Some(true)
      case SimpleVarInput(_, Some(matcher1)) =>
        info.flag match {
          case ConstInputPattern(c) =>
            Some(matcher1.isDefinedAt(c))
          case SimpleVarInput(_, Some(_)) |
               OtherInputPattern(_, _, false) =>
            None // Cannot reliably determine a weaker matcher.
          case _ =>
            Some(false)
        }
      case OtherInputPattern(matcher1, _, false) =>
        info.flag match {
          case ConstInputPattern(c) =>
            Some(matcher1.isDefinedAt(c))
          case OtherInputPattern(_, _, false) =>
            if (sha1 === info.sha1) Some(true)
            else None // We can reliably determine identical matchers.
          case _ =>
            Some(false) // Here we can reliably determine that this matcher is not weaker.
        }
      case ConstInputPattern(c) =>
        Some(info.flag match {
          case ConstInputPattern(c1) =>
            c === c1
          case _ =>
            false
        })
    }
  }

  private[jc] def matcherIsWeakerThanOutput(info: OutputMoleculeInfo): Option[Boolean] = {
    if (molecule =!= info.molecule) Some(false)
    else flag match {
      case WildcardInput |
           SimpleVarInput(_, None) |
           OtherInputPattern(_, _, true) =>
        Some(true)
      case SimpleVarInput(_, Some(matcher1)) =>
        info.flag match {
          case ConstOutputPattern(c) =>
            Some(matcher1.isDefinedAt(c))
          case _ =>
            None // Here we can't reliably determine whether this matcher is weaker.
        }
      case OtherInputPattern(matcher1, _, false) =>
        info.flag match {
          case ConstOutputPattern(c) =>
            Some(matcher1.isDefinedAt(c))
          case _ =>
            None // Here we can't reliably determine whether this matcher is weaker.
        }
      case ConstInputPattern(c) => info.flag match {
        case ConstOutputPattern(`c`) =>
          Some(true)
        case ConstOutputPattern(_) =>
          Some(false) // definitely not the same constant
        case _ =>
          None // Otherwise, it could be this constant but we can't determine.
      }
    }
  }

  // Here "similar" means either it's definitely weaker or it could be weaker (but it is definitely not stronger).
  private[jc] def matcherIsSimilarToOutput(info: OutputMoleculeInfo): Option[Boolean] = {
    if (molecule =!= info.molecule)
      Some(false)
    else flag match {
      case WildcardInput |
           SimpleVarInput(_, None) |
           OtherInputPattern(_, _, true) =>
        Some(true)
      case SimpleVarInput(_, Some(matcher1)) =>
        info.flag match {
          case ConstOutputPattern(c) =>
            Some(matcher1.isDefinedAt(c))
          case _ =>
            Some(true) // Here we can't reliably determine whether this matcher is weaker, but it's similar (i.e. could be weaker).
        }
      case OtherInputPattern(matcher1, _, false) =>
        info.flag match {
          case ConstOutputPattern(c) =>
            Some(matcher1.isDefinedAt(c))
          case _ =>
            Some(true) // Here we can't reliably determine whether this matcher is weaker, but it's similar (i.e. could be weaker).
        }
      case ConstInputPattern(c) =>
        Some(info.flag match {
          case ConstOutputPattern(`c`) =>
            true
          case ConstOutputPattern(_) =>
            false // definitely not the same constant
          case _ =>
            true // Otherwise, it could be this constant.
        })
    }
  }

  override val toString: String = {
    val printedPattern = flag match {
      case WildcardInput =>
        "_"
      case SimpleVarInput(v, None) =>
        v.name
      case SimpleVarInput(v, Some(_)) =>
        s"${v.name} if ?"
      //      case ConstInputPattern(()) => ""  // This case was eliminated by converting constants of Unit type to Wildcard input flag.
      case ConstInputPattern(c) =>
        c.toString
      case OtherInputPattern(_, vars, isIrrefutable) =>
        s"${if (isIrrefutable) "" else "?"}${vars.map(_.name).mkString(",")}"
    }

    s"$molecule($printedPattern)"
  }

}

/** Compile-time information about an output molecule pattern in a reaction.
  * This class is immutable.
  *
  * @param molecule     The molecule emitter value that represents the output molecule.
  * @param flag         Type of the output pattern: either a constant value or other value.
  * @param environments The code environment in which this output molecule was emitted.
  */
final case class OutputMoleculeInfo(molecule: Molecule, flag: OutputPatternType, environments: List[OutputEnvironment]) {
  val atLeastOnce: Boolean = environments.forall(_.atLeastOne)

  override val toString: String = {
    val printedPattern = flag match {
      case ConstOutputPattern(()) =>
        ""
      case ConstOutputPattern(c) =>
        c.toString
      case OtherOutputPattern =>
        "?"
    }

    s"$molecule($printedPattern)"
  }
}

/** Represents information carried by every Chymyst thread that runs a reaction.
  *
  * @param statics        List of static molecules that this reaction consumes.
  * @param reactionString String representation of the reaction, used for error messages.
  */
final class ChymystThreadInfo(
  statics: Set[Molecule] = Set(),
  reactionString: String = "<no reaction>"
) {
  override val toString: String = reactionString

  private[jc] val maybeEmit: Molecule => Boolean = {
    val allowedToEmit: mutable.Set[Molecule] = mutable.Set() ++ statics

    { m: Molecule => allowedToEmit.remove(m) }
  }

  private[jc] def couldEmit(m: Molecule): Boolean = statics.contains(m)
}

// This class is immutable.
final class ReactionInfo(
  private[jc] val inputs: Array[InputMoleculeInfo],
  private[jc] val outputs: Array[OutputMoleculeInfo],
  private[jc] val shrunkOutputs: Array[OutputMoleculeInfo],
  private[jc] val guardPresence: GuardPresenceFlag,
  private[jc] val sha1: String
) {
  // This should be lazy because molecule.isStatic is known late.
  private[jc] lazy val staticMols: Set[Molecule] = inputs.map(_.molecule).filter(_.isStatic).toSet

  // Optimization: avoid pattern-match every time we need to find cross-molecule guards.
  private[jc] val crossGuards: Array[CrossMoleculeGuard] = guardPresence match {
    case GuardPresent(_, guards) =>
      guards
    case _ =>
      Array[CrossMoleculeGuard]()
  }

  // TODO: write a test that fixes this functionality?
  /** This array is either empty or contains several arrays, each of length at least 2. */
  private val repeatedCrossConstrainedMolecules: Array[Array[InputMoleculeInfo]] = {
    inputs
      .groupBy(_.molecule)
      .filter(_._2.length >= 2)
      .values
      .filter { repeatedInput ⇒
        crossGuards.exists { guard ⇒
          (guard.indices intersect repeatedInput.map(_.index)).nonEmpty
        } ||
          repeatedInput.exists { info ⇒ !info.flag.isIrrefutable }
      }
      .toArray
  }

  /** "Cross-conditionals" are repeated input molecules, such that one of them has a conditional or participates in a cross-molecule guard.
    * This value holds the set of input indices for all such molecules, for quick access.
    */
  private[jc] val crossConditionalsForRepeatedMols: Set[Int] = repeatedCrossConstrainedMolecules
    .flatMap(_.map(_.index))
    .toSet

  /** The array of sets of cross-molecule dependency groups. Each molecule is represented by its input index.
    * The cross-molecule dependency groups include both the molecules that are constrained by cross-molecule guards and also
    * repeated molecules whose copies participate in a cross-molecule guard or a per-molecule conditional.
    */
  private val allCrossGroups: Array[Set[Int]] = crossGuards.map(_.indices.toSet) ++
    repeatedCrossConstrainedMolecules.map(_.map(_.index).toSet)

  /** The first integer is the number of cross-conditionals in which the molecule participates. The second is `true` when the molecule has its own conditional. */
  private val moleculeWeights: Array[(Int, Boolean)] =
    inputs.map(info ⇒ (-allCrossGroups.map(_ intersect Set(info.index)).map(_.size).sum, info.flag.isIrrefutable))

  /** The input molecule indices for all molecules that have no cross-dependencies and also no cross-conditionals.
    * Note that cross-conditionals are created by a repeated input molecule that has a conditional or participates in a cross-molecule guard.
    * A repeated input molecule is independent when all its repeated instances in the input list have irrefutable matchers and do not participate in any cross-molecule guards.
    * A non-repeated input molecule is independent when it does not participate in any cross-molecule guards (but it may have a conditional matcher).
    * An exception to these rules is a molecule with a constant matcher: this molecule is always independent.
    */
  private[jc] val independentInputMolecules = {
    val moleculesWithoutCrossConditionals = inputs.map(_.index)
      .filter(index ⇒
        !crossConditionalsForRepeatedMols.contains(index) && crossGuards.forall {
          case CrossMoleculeGuard(indices, _, _) ⇒
            !indices.contains(index)
        })
    val moleculesWithConstantValues = inputs.filter(_.isConstantValue).map(_.index)
    (moleculesWithoutCrossConditionals ++ moleculesWithConstantValues).toSet
  }

  /** The sequence of [[SearchDSL]] instructions for selecting the molecule values under cross-molecule constraints.
    * Independent molecules are not included in this DSL program; their values are to be selected separately.
    *
    * This [[SearchDSL]] program is already optimized by including the constraint guards as early as possible.
    */
  private[jc] val searchDSLProgram = CrossMoleculeSorting.getDSLProgram(
    crossGuards.map(_.indices.toSet),
    repeatedCrossConstrainedMolecules.map(_.map(_.index).toSet),
    moleculeWeights
  )

  // The input pattern sequence is pre-sorted by descending strength of constraint -- for pretty-printing as well as for use in static analysis.
  private[jc] val inputsSortedByConstraintStrength: List[InputMoleculeInfo] = {
    inputs.sortBy { case InputMoleculeInfo(mol, _, flag, sha, _) =>
      // Wildcard and SimpleVar without a conditional are sorted together; more specific matchers will precede less specific matchers.
      val patternPrecedence = flag match {
        case WildcardInput |
             SimpleVarInput(_, None) |
             OtherInputPattern(_, _, true) =>
          3
        case OtherInputPattern(_, _, false) |
             SimpleVarInput(_, Some(_)) =>
          2
        case ConstInputPattern(_) =>
          1
      }

      val molValueString = flag match {
        case ConstInputPattern(v) =>
          v.toString
        case SimpleVarInput(v, _) =>
          v.name
        case _ =>
          ""
      }
      (mol.toString, patternPrecedence, molValueString, sha)
    }.toList
  }

  /** [[inputsSortedIndependentIrrefutableGrouped]] is the list of input indices of only the input molecules that
    * have irrefutable matchers, grouped by site-wide index. (These molecules are automatically independent.)
    * If a molecule is repeated, it will be represented as a tuple `(sitewide index, Array[input index])`.
    *
    * [[inputsSortedIndependentConditional]] is the list of [[InputMoleculeInfo]] values for all independent molecules whose matchers are not irrefutable (including repeated molecules).
    *
    * Both these lists must be lazy because `molecule.index` is known late.
    */
  private[jc] lazy val (
    inputsSortedIndependentIrrefutableGrouped,
    inputsSortedIndependentConditional
    ) = {
    val (inputsSortedIrrefutable, inputsSortedConditional) = inputsSortedByConstraintStrength.partition(_.flag.isIrrefutable)
    val inputsSortedIrrefutableGrouped =
      inputsSortedIrrefutable
        .filter(info ⇒ independentInputMolecules contains info.index)
        .orderedMapGroupBy(_.molecule.index, _.index)
        .map { case (i, is) ⇒ (i, is.toArray) }
        .toArray
    (inputsSortedIrrefutableGrouped, inputsSortedConditional.filter(info ⇒ independentInputMolecules contains info.index))
  }

  /* Not sure if this is still useful.
    private def moleculeDependencies(index: Int): Array[Int] = info.guardPresence match {
      case GuardPresent(_, _, crossGuards) =>
        crossGuards.flatMap { case CrossMoleculeGuard(indices, _, _) =>
          if (indices.contains(index))
            indices
          else Array[Int]()
        }.distinct
      case _ => Array[Int]()
    }
  */

  override val toString: String = {
    val inputsInfo = inputsSortedByConstraintStrength.map(_.toString).mkString(" + ")
    val guardInfo = guardPresence match {
      case _
        if guardPresence.noCrossGuards =>
        ""
      case GuardPresent(Some(_), Array()) =>
        " if(?)" // There is a static guard but no cross-molecule guards.
      case GuardPresent(_, guards) =>
        val crossGuardsInfo = guards.flatMap(_.symbols).map(_.name).distinct.mkString(",")
        s" if($crossGuardsInfo)"
    }
    val outputsInfo = outputs.map(_.toString).mkString(" + ")
    s"$inputsInfo$guardInfo → $outputsInfo"
  }
}

/** Represents a reaction body. This class is immutable.
  *
  * @param body       Partial function of type `InputMoleculeList => Any`
  * @param threadPool Thread pool on which this reaction will be scheduled. (By default, the common pool is used.)
  * @param retry      Whether the reaction should be run again when an exception occurs in its body. Default is false.
  */
final case class Reaction(
  private[jc] val info: ReactionInfo,
  private[jc] val body: ReactionBody,
  threadPool: Option[Pool],
  private[jc] val retry: Boolean
) {
  private[jc] def newChymystThreadInfo = new ChymystThreadInfo(info.staticMols, info.toString)

  /** Convenience method to specify thread pools per reaction.
    *
    * Example: go { case a(x) => ... } onThreads threadPool24
    *
    * @param newThreadPool A custom thread pool on which this reaction will be scheduled.
    * @return New reaction value with the thread pool set.
    */
  def onThreads(newThreadPool: Pool): Reaction = copy(threadPool = Some(newThreadPool))

  /** Convenience method to specify the "retry" option for a reaction.
    *
    * @return New reaction value with the "retry" flag set.
    */
  def withRetry: Reaction = copy(retry = true)

  /** Convenience method to specify the "no retry" option for a reaction.
    * (This option is the default.)
    *
    * @return New reaction value with the "retry" flag unset.
    */
  def noRetry: Reaction = copy(retry = false)

  // Optimization: this is used often.
  private[jc] val inputMoleculesSortedAlphabetically: Seq[Molecule] =
    info.inputs
      .map(_.molecule)
      .sortBy(_.toString)

  // Optimization: this is used often.
  private[jc] val inputMoleculesSet: Set[Molecule] = inputMoleculesSortedAlphabetically.toSet

  /** The final SearchDSL program for finding values of molecules affected by cross-molecule constraints.
    *
    * This computation optimizes `info.searchDSLProgram` by removing molecules that have constant value matchers.
    *
    */
  private val searchDSLProgram: Coll[SearchDSL] = info.searchDSLProgram
    .filter {
      case ChooseMol(i) => !info.inputs(i).isConstantValue
      case _ => true
    }

  // This must be lazy because molecule indices are not known at `Reaction` construction time.
  private[jc] lazy val moleculeIndexRequiredCounts: Map[Int, Int] =
    inputMoleculesSet.map { mol ⇒ (mol.index, inputMoleculesSortedAlphabetically.count(_ === mol)) }(scala.collection.breakOut)

  /** Convenience method for debugging.
    *
    * @return String representation of input molecules of the reaction.
    */
  override val toString: String = {
    val suffix = if (retry)
      "/R"
    else ""
    s"${inputMoleculesSortedAlphabetically.map(_.toString).mkString(" + ")} → ...$suffix"
  }

  /** Find a set of input molecule values for this reaction. */
  private[jc] def findInputMolecules(moleculesPresent: MoleculeBagArray): Option[(Reaction, InputMoleculeList)] = {
    // A simpler, non-flatMap algorithm for the case when there are no cross-dependencies of molecule values.
    // For each single (non-repeated) input molecule, select a molecule value that satisfies the conditional.
    // For each group of repeated input molecules of the same sort, check whether the bag contains enough molecule values.
    // Begin checking with molecules that have more stringent constraints (and thus, are not repeated).

    // This array will be mutated in place as we search for molecule values.
    val foundValues = new Array[AbsMolValue[_]](info.inputs.length)

    val foundResult: Boolean =
    // `foundResult` will be `true` (and then `foundValues` has the molecule values) or `false` (we found no values that match).

    // First, consider independent molecules with conditionals. If we fail to find their values, `foundResult` will be `false`.
      info.inputsSortedIndependentConditional.forall { inputInfo ⇒
        val molBag = moleculesPresent(inputInfo.molecule.index)
        val newValueOpt =
          if (inputInfo.molecule.isPipelined)
            molBag.takeOne.filter(inputInfo.admitsValue) // For pipelined molecules, we take the first one; if condition fails, we treat that case as if no molecule is available.
          // It is probably useless to try optimizing the selection of a constant value, because 1) values are wrapped and 2) values that are not "simple types" are most likely to be stored in a queue-based molecule bag rather than in a hash map-based molecule bag.
          else
            molBag.find(inputInfo.admitsValue)

        newValueOpt.foreach { newMolValue ⇒
          foundValues(inputInfo.index) = newMolValue
        }
        newValueOpt.nonEmpty
      } && {
        // Here we don't need to check any conditions because we already know that the molecule counts are sufficient for all molecules.
        // However, it is important to assign these molecule values here before we embark on the SearchDSL program for cross-molecule groups,
        // because the SearchDSL program does not include molecules with constant value matchers, so they have to be assigned now.
        info.inputsSortedIndependentIrrefutableGrouped
          .foreach { case (siteMolIndex, infos) ⇒
            val molValues = moleculesPresent(siteMolIndex).takeAny(moleculeIndexRequiredCounts(siteMolIndex))
            infos.indices.foreach { idx ⇒ foundValues(infos(idx)) = molValues(idx) }
          }
        // If we have no cross-conditionals, we do not need to use the SearchDSL sequence and we are finished.
        if (info.crossGuards.isEmpty && info.crossConditionalsForRepeatedMols.isEmpty)
          true
        else {
          // Map from site-wide molecule index to the multiset of values that have been selected for repeated copies of this molecule.
          // This is used only for selecting repeated input molecules.
          type MolVals = Map[Int, List[AbsMolValue[_]]]

          val initStream = Stream[MolVals](Map())

          val found: Option[Stream[MolVals]] = searchDSLProgram
            // The `flatFoldLeft` accumulates the value `repeatedMolValues`, representing the stream of value maps for repeated input molecules (only).
            // This is used to build a "skipping iterator" over molecule values that correctly handles repeated input molecules.

            // This is a "flat fold" because should be able to stop early even though we can't examine the stream value.
            .flatFoldLeft[Stream[MolVals]](initStream) { (repeatedMolValuesStream, searchDslCommand) ⇒
            // We need to return Option[Stream[MolVals]].
            searchDslCommand match {
              case ChooseMol(i) ⇒
                // Note that this molecule cannot be pipelined since it is part of a cross-molecule constraint.
                val inputInfo = info.inputs(i)

                Some(// The stream contains repetitions of the immutable values `repeatedVals` of type `MolVals`, which represents the value map for repeated input molecules.
                  // If there are no repeated input molecules, this will be an empty map.
                  // However, each item in the stream will mutate `foundValues` in place, so that we always have the last chosen molecule values.
                  // The search DSL program is guaranteed to check cross-molecule conditions only for molecules whose values we already chose.
                  repeatedMolValuesStream.flatMap { repeatedVals ⇒
                    val siteMolIndex = inputInfo.molecule.index
                    if (info.crossConditionalsForRepeatedMols contains i) {
                      val prevValMap = repeatedVals.getOrElse(siteMolIndex, List[AbsMolValue[_]]())
                      moleculesPresent(siteMolIndex)
                        // TODO: move this to the skipping interface, restore Seq[T] as its argument
                        .allValuesSkipping(new MutableMultiset[AbsMolValue[_]]().add(prevValMap))
                        .filter(inputInfo.admitsValue)

                        .map { v ⇒
                          foundValues(i) = v
                          repeatedVals.updated(siteMolIndex, v :: prevValMap)
                        }
                    } else {
                      // This is not a repeated molecule.
                      moleculesPresent(siteMolIndex)
                        .allValues
                        .filter(inputInfo.admitsValue)
                        .map { v ⇒
                          foundValues(i) = v
                          repeatedVals
                        }
                    }
                  }
                )

              case ConstrainGuard(i) ⇒
                val guard = info.crossGuards(i)
                Some(repeatedMolValuesStream.filter { _ ⇒
                  guard.cond.isDefinedAt(guard.indices.map(i ⇒ foundValues(i).moleculeValue).toList)
                })

              case CloseGroup ⇒
                // If the stream is empty, we will return `None` here and terminate the "flat fold".
                repeatedMolValuesStream.headOption.map(_ ⇒ initStream)
            }
          }
          found.nonEmpty
        }
      }
    if (foundResult)
      Some((this, foundValues))
    else
      None
  }

}
