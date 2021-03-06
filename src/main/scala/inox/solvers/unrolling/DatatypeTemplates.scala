/* Copyright 2009-2018 EPFL, Lausanne */

package inox
package solvers
package unrolling

import utils._

import scala.collection.mutable.{Map => MutableMap, Set => MutableSet}

/** Performs incremental ADT unfolding and enables support for ADT invariants as well
  * as support for first-class functions within ADTs.
  *
  * ADT unfolding is also used to discover fist-class functions over which a given
  * lambda will close (closures of the resulting "closure"). These are necessary to
  * support equality between input first-class functions and lambdas defined within
  * the current expression as there must exist a total order on the closure
  * definitions to avoid impossible closure creation deadlocks.
  *
  * @see [[LambdaTemplates]] for more discussions about input first-class functions
  *                          and the total ordering of closures.
  */
trait DatatypeTemplates { self: Templates =>
  import context._
  import program._
  import program.trees._
  import program.symbols._

  import datatypesManager._

  type Functions = Set[(Encoded, Encoded, FunctionType, Encoded)]

  /** Represents the kind of datatype a given template is associated to. */
  sealed abstract class TypeInfo {
    def getType: Type = this match {
      case ADTInfo(sort) => ADTType(sort.id, sort.tps)
      case SetInfo(base) => SetType(base)
      case BagInfo(base) => BagType(base)
      case MapInfo(from, to) => MapType(from, to)
    }
  }

  case class ADTInfo(sort: TypedADTSort) extends TypeInfo
  case class SetInfo(base: Type) extends TypeInfo
  case class BagInfo(base: Type) extends TypeInfo
  case class MapInfo(from: Type, to: Type) extends TypeInfo
 
  /** Represents the kind of instantiator (@see [[TypesTemplate]]) a given
    * template info is associated to. */
  sealed abstract class TemplateInstantiator {
    def substitute(substituter: Encoded => Encoded): TemplateInstantiator = this match {
      case Datatype(result) => Datatype(substituter(result))
      case Capture(encoded, tpe) => Capture(substituter(encoded), tpe)
    }
  }

  case class Datatype(result: Encoded) extends TemplateInstantiator
  case class Capture(encoded: Encoded, tpe: FunctionType) extends TemplateInstantiator

  /** Represents a type unfolding of a free variable (or input) in the unfolding procedure */
  case class TemplateTypeInfo(info: TypeInfo, arg: Encoded, instantiator: TemplateInstantiator) {
    override def toString: String =
      info + "(" + asString(arg) + ")" + (instantiator match {
        case Capture(f, tpe) => " in " + asString(f)
        case _ => ""
      })

    def substitute(substituter: Encoded => Encoded): TemplateTypeInfo = copy(
      arg = substituter(arg),
      instantiator = instantiator.substitute(substituter)
    )
  }

  /** Sets up the relevant unfolding procedures for symbols that are free in the input expression */
  def registerSymbol(start: Encoded, sym: Encoded, tpe: Type): (Encoded, Clauses) = {
    if (DatatypeTemplate.unroll(tpe)) {
      val result = results.cached(tpe)(encodeSymbol(Variable.fresh("result", BooleanType(), true)))
      val clauses = DatatypeTemplate(tpe).instantiate(start, result, sym)
      (result, clauses)
    } else {
      (trueT, Seq.empty)
    }
  }

  /** Sets up the relevant unfolding procedure for closure ordering */
  def registerClosure(start: Encoded, container: (Encoded, FunctionType), arg: (Encoded, Type)): Clauses = {
    CaptureTemplate(arg._2, container._2).instantiate(start, container._1, arg._1)
  }

  /** Base trait for datatype unfolding template generators.
    *
    * This trait provides a useful interface for building datatype unfolding
    * templates. The interesting override points are:
    *
    * - [[unroll]]:
    *   determines whether a given type requires unfolding.
    *   @see [[ADTUnrolling.unroll]]
    *   @see [[FunctionUnrolling.unroll]]
    *   @see [[FlatUnrolling.unroll]]
    *   @see [[CachedUnrolling.unroll]]
    *
    * - [[Builder.rec]]:
    *   can be overriden to provide finer controll of what clauses should be generated during
    *   template construction. The [[Builder]] class itself can't be overriden, so one must be
    *   careful to use the overriding class when construction a template! We use a [[Builder]]
    *   class here so that the [[Builder.rec]] method can refer to the current [[Builder]]
    *   state while still providing an override point.
    *   @see [[DatatypeTemplate.Builder.rec]]
    *   @see [[CaptureTemplate.Builder.rec]]
    *
    * @see [[DatatypeTemplate$]]
    * @see [[CaptureTemplate$]]
    */
  sealed protected trait TemplateGenerator {
    /** Determines whether a given [[ast.Types.Type Type]] needs to be considered during ADT unfolding. */
    def unroll(tpe: Type): Boolean

    /** Stateful template generating trait. Provides the [[rec]] override point so that
      * subclasses of [[TemplateGenerator]] can override [[rec]] while still using
      * stateful clause generation.
      */
    protected trait Builder {
      val tpe: Type

      val v = Variable.fresh("x", tpe, true)
      val pathVar = Variable.fresh("b", BooleanType(), true)
      val result = Variable.fresh("result", BooleanType(), true)
      val (idT, pathVarT, resultT) = (encodeSymbol(v), encodeSymbol(pathVar), encodeSymbol(result))

      private var exprVars = Map[Variable, Encoded]()
      @inline protected def storeExpr(v: Variable): Unit = {
        exprVars += v -> encodeSymbol(v)
      }

      private var condVars = Map[Variable, Encoded]()
      private var condTree = Map[Variable, Set[Variable]](pathVar -> Set.empty).withDefaultValue(Set.empty)
      @inline protected def storeCond(pathVar: Variable, v: Variable): Unit = {
        condVars += v -> encodeSymbol(v)
        condTree += pathVar -> (condTree(pathVar) + v)
      }

      private var guardedExprs = Map[Variable, Seq[Expr]]()
      @inline protected def storeGuarded(pathVar: Variable, expr: Expr): Unit = {
        val prev = guardedExprs.getOrElse(pathVar, Nil)
        guardedExprs += pathVar -> (expr +: prev)
      }

      private var equations = Seq[Expr]()
      @inline protected def iff(e1: Expr, e2: Expr): Unit = equations :+= Equals(e1, e2)

      private var tpes = Map[Variable, Set[(Variable, TypeInfo, Expr)]]()
      protected def storeType(pathVar: Variable, info: TypeInfo, arg: Expr): Variable = {
        val typeCall: Variable = Variable.fresh("tp", BooleanType(), true)
        storeExpr(typeCall)

        tpes += pathVar -> (tpes.getOrElse(pathVar, Set.empty) + ((typeCall, info, arg)))
        typeCall
      }

      protected case class RecursionState(
        recurseAdt: Boolean, // visit adt children/fields
        recurseMap: Boolean, // unroll map definition
        recurseSet: Boolean, // unroll set definition
        recurseBag: Boolean  // unroll bag definition
      )

      /** Generates the clauses and other bookkeeping relevant to a type unfolding template.
        * Subtypes of [[Builder]] can override this method to change clause generation. */
      protected def rec(pathVar: Variable, expr: Expr, state: RecursionState): Expr = expr.getType match {
        case tpe if !unroll(tpe) => BooleanLiteral(true) // nothing to do here!

        case adt: ADTType =>
          val sort = adt.getSort

          if (sort.definition.isInductive && !state.recurseAdt) {
            storeType(pathVar, ADTInfo(sort), expr)
          } else {
            val newExpr = Variable.fresh("e", BooleanType(), true)

            val stored = for (tcons <- sort.constructors) yield {
              val newBool: Variable = Variable.fresh("b", BooleanType(), true)
              val recProp = andJoin(for (vd <- tcons.fields) yield {
                rec(newBool, ADTSelector(expr, vd.id), state.copy(recurseAdt = false))
              })

              if (recProp != BooleanLiteral(true)) {
                storeCond(pathVar, newBool)
                iff(and(pathVar, isCons(expr, tcons.id)), newBool)
                storeGuarded(newBool, Equals(newExpr, recProp))
                true
              } else {
                false
              }
            }

            if (stored.foldLeft(false)(_ || _)) {
              storeExpr(newExpr)
              newExpr
            } else {
              BooleanLiteral(true)
            }
          }

        case TupleType(tpes) =>
          andJoin(for ((_, idx) <- tpes.zipWithIndex) yield {
            rec(pathVar, TupleSelect(expr, idx + 1), state)
          })

        case MapType(from, to) =>
          val newBool: Variable = Variable.fresh("b", BooleanType(), true)
          storeCond(pathVar, newBool)

          val dfltExpr: Variable = Variable.fresh("dlft", to, true)
          storeExpr(dfltExpr)

          iff(and(pathVar, Not(Equals(expr, FiniteMap(Seq.empty, dfltExpr, from, to)))), newBool)

          and(rec(pathVar, dfltExpr, state), if (!state.recurseMap) {
            storeType(newBool, MapInfo(from, to), expr)
          } else {
            val keyExpr: Variable = Variable.fresh("key", from, true)
            val valExpr: Variable = Variable.fresh("val", to, true)
            val restExpr: Variable = Variable.fresh("rest", MapType(from, to), true)
            storeExpr(keyExpr)
            storeExpr(valExpr)
            storeExpr(restExpr)

            storeGuarded(newBool, Equals(expr, MapUpdated(restExpr, keyExpr, valExpr)))
            and(
              rec(newBool, restExpr, state.copy(recurseMap = false)),
              rec(newBool, keyExpr, state),
              rec(newBool, valExpr, state)
            )
          })

        case SetType(base) =>
          val newBool: Variable = Variable.fresh("b", BooleanType(), true)
          storeCond(pathVar, newBool)

          iff(and(pathVar, Not(Equals(expr, FiniteSet(Seq.empty, base)))), newBool)

          if (!state.recurseSet) {
            storeType(newBool, SetInfo(base), expr)
          } else {
            val elemExpr: Variable = Variable.fresh("elem", base, true)
            val restExpr: Variable = Variable.fresh("rest", SetType(base), true)
            storeExpr(elemExpr)
            storeExpr(restExpr)

            storeGuarded(newBool, Equals(expr, SetUnion(FiniteSet(Seq(elemExpr), base), restExpr)))

            and(
              rec(newBool, restExpr, state.copy(recurseSet = false)),
              rec(newBool, elemExpr, state)
            )
          }

        case BagType(base) =>
          val newBool: Variable = Variable.fresh("b", BooleanType(), true)
          storeCond(pathVar, newBool)

          iff(and(pathVar, Not(Equals(expr, FiniteBag(Seq.empty, base)))), newBool)

          if (!state.recurseBag) {
            storeType(pathVar, BagInfo(base), expr)
          } else {
            val elemExpr: Variable = Variable.fresh("elem", base, true)
            val multExpr: Variable = Variable.fresh("mult", IntegerType(), true)
            val restExpr: Variable = Variable.fresh("rest", BagType(base), true)
            storeExpr(elemExpr)
            storeExpr(multExpr)
            storeExpr(restExpr)

            storeGuarded(newBool, Equals(expr, BagUnion(FiniteBag(Seq(elemExpr -> multExpr), base), restExpr)))
            storeGuarded(newBool, GreaterThan(multExpr, IntegerLiteral(0)))

            and(
              rec(newBool, restExpr, state.copy(recurseBag = false)),
              rec(newBool, elemExpr, state)
            )
          }

        case _ => throw FatalError("Unexpected unrollable")
      }

      protected def encodingSubst: Map[Variable, Encoded] =
        exprVars ++ condVars + (v -> idT) + (pathVar -> pathVarT) + (result -> resultT)

      /* Calls [[rec]] and finalizes the bookkeeping collection before returning everything
       * necessary to a template creation. */
      lazy val (encoder, conds, exprs, tree, clauses, calls, types) = {
        val res = rec(pathVar, v, RecursionState(true, true, true, true))
        storeGuarded(pathVar, Equals(result, res))

        val encoder: Expr => Encoded = mkEncoder(encodingSubst)

        var clauses: Clauses = Seq.empty
        var calls: CallBlockers  = Map.empty

        for ((b, es) <- guardedExprs) {
          var callInfos : Set[Call] = Set.empty

          for (e <- es) {
            callInfos ++= exprOps.collect[FunctionInvocation] {
              case fi: FunctionInvocation => Set(fi)
              case _ => Set.empty
            } (e).map { case FunctionInvocation(id, tps, args) =>
              Call(getFunction(id, tps), args.map(arg => Left(encoder(arg))))
            }

            clauses :+= encoder(Implies(b, e))
          }

          if (callInfos.nonEmpty) calls += encoder(b) -> callInfos
        }

        clauses ++= equations.map(encoder)

        val encodedTypes: Map[Encoded, Set[(Encoded, TypeInfo, Encoded)]] = tpes.map { case (b, tps) =>
          encoder(b) -> tps.map { case (v, info, expr) => (encoder(v), info, encoder(expr)) }
        }

        (encoder, condVars, exprVars, condTree, clauses, calls, encodedTypes)
      }
    }
  }

  /** Extends [[TemplateGenerator]] with functionalities for checking whether
    * ADTs need to be unrolled.
    *
    * Note that the actual ADT unrolling takes place in [[TemplateGenerator.Builder.rec]].
    */
  protected trait ADTUnrolling extends TemplateGenerator {
    private val checking: MutableSet[TypedADTSort] = MutableSet.empty

    /** We recursively visit the ADT and its fields here to check whether we need to unroll. */
    abstract override def unroll(tpe: Type): Boolean = tpe match {
      case adt: ADTType => adt.getSort match {
        case sort if checking(sort) => false
        case sort =>
          checking += sort
          sort.constructors.exists(c => c.fieldsTypes.exists(unroll))
      }

      case _ => super.unroll(tpe)
    }
  }

  /** Extends [[TemplateGenerator]] with functionalities to accumulate the set
    * of functions contained within a datastructure.
    */
  protected trait FunctionUnrolling extends TemplateGenerator {

    /** The definition of [[unroll]] makes sure ALL functions are discovered. */
    def unroll(tpe: Type): Boolean = tpe match {
      case (_: FunctionType) | (_: BagType) | (_: SetType) => true

      case NAryType(tpes, _) => tpes.exists(unroll)
    }

    /** The [[TemplateGenerator.Builder]] trait is extended to accumulate functions
      * during the clause generation. */
    protected trait Builder extends super.Builder {
      private var functions = Map[Variable, Set[(Variable, Expr)]]()
      protected def storeFunction(pathVar: Variable, expr: Expr): Variable = {
        val funCall: Variable = Variable.fresh("fun", BooleanType(), true)
        storeExpr(funCall)

        functions += pathVar -> (functions.getOrElse(pathVar, Set.empty) + ((funCall, expr)))
        funCall
      }

      override protected def rec(pathVar: Variable, expr: Expr, state: RecursionState): Expr = expr.getType match {
        case _: FunctionType => storeFunction(pathVar, expr)
        case _ => super.rec(pathVar, expr, state)
      }

      lazy val funs: Functions = {
        val enc = encoder // forces super to call rec()
        (for ((b, fs) <- functions; bp = enc(b); (v, expr) <- fs) yield {
          (bp, enc(v), expr.getType.asInstanceOf[FunctionType], enc(expr))
        }).toSet
      }
    }
  }

  /** Template generator that ensures [[unroll]] call results are cached. */
  protected trait CachedUnrolling extends TemplateGenerator {
    private val unrollCache: MutableMap[Type, Boolean] = MutableMap.empty

    /** Determines whether a given [[ast.Types.Type Type]] needs to be considered during ADT unfolding.
      * 
      * This function DOES NOT correspond to an override point (hence the `final` modifier).
      * One should look at [[unrollType]] to change the behavior of [[unroll]].
      */
    abstract override final def unroll(tpe: Type): Boolean = unrollCache.getOrElseUpdate(tpe, {
      unrollType(tpe) || super.unroll(tpe)
    })

    /** Override point to determine whether a given type should be unfolded.
      *
      * This methods shouldn't be recursive as the ADT traversal already takes place
      * within the [[unroll]] method.
      *
      * By default, returns `false`.
      */
    protected def unrollType(tpe: Type): Boolean = false
  }

  /** Template generator that generates clauses for ADT invariant assertion. */
  protected trait InvariantGenerator
    extends TemplateGenerator
       with ADTUnrolling
       with CachedUnrolling {

    private val tpSyms: MutableMap[TypeParameter, (Variable, Encoded)] = MutableMap.empty

    def satisfactionAssumptions = tpSyms.values.toSeq.map(_._2)

    /** ADT unfolding is required when the ADT type has an ADT invariant.
      *
      * Note that clause generation in [[Builder.rec]] MUST correspond to the types
      * that require unfolding as defined here.
      */
    override protected def unrollType(tpe: Type): Boolean = tpe match {
      case adt: ADTType => adt.getSort.hasInvariant
      case tp: TypeParameter => true
      case _ => false
    }

    /** Clause generation is specialized to handle ADT constructor types that require
      * type guards as well as ADT invariants. */
    protected trait Builder extends super.Builder {
      private val tpSubst: MutableMap[Variable, Encoded] = MutableMap.empty
      protected def storeTypeParameter(tp: TypeParameter): Expr = {
        val (v, e) = tpSyms.getOrElseUpdate(tp, {
          val v = Variable.fresh("tp_is_empty", BooleanType(), true)
          v -> encodeSymbol(v)
        })
        tpSubst(v) = e
        v
      }

      override protected def rec(pathVar: Variable, expr: Expr, state: RecursionState): Expr = expr.getType match {
        case adt: ADTType =>
          and(
            adt.getSort.invariant.map(_.applied(Seq(expr))).getOrElse(BooleanLiteral(true)),
            super.rec(pathVar, expr, state)
          )

        case tp: TypeParameter => storeTypeParameter(tp)

        case _ => super.rec(pathVar, expr, state)
      }

      override protected def encodingSubst: Map[Variable, Encoded] =
        super.encodingSubst ++ tpSubst
    }
  }

  /** Base type for datatype unfolding templates. */
  trait TypesTemplate extends Template {
    val contents: TemplateContents
    val types: Map[Encoded, Set[TemplateTypeInfo]]

    override def instantiate(substMap: Map[Encoded, Arg]): Clauses = {
      val substituter = mkSubstituter(substMap.mapValues(_.encoded))

      for ((b, tps) <- types; bp = substituter(b); tp <- tps) {
        val stp = tp.substitute(substituter)
        val gen = nextGeneration(currentGeneration)
        val notBp = mkNot(bp)

        typeInfos.get(bp) match {
          case Some((exGen, origGen, _, exTps)) =>
            val minGen = gen min exGen
            typeInfos += bp -> (minGen, origGen, notBp, exTps + stp)
          case None =>
            typeInfos += bp -> (gen, gen, notBp, Set(stp))
        }
      }

      super.instantiate(substMap)
    }
  }

  /** Template used to unfold free symbols in the input expression. */
  class DatatypeTemplate private[DatatypeTemplates] (
    val contents: TemplateContents,
    val types: Map[Encoded, Set[TemplateTypeInfo]],
    val functions: Functions) extends TypesTemplate {

    def instantiate(blocker: Encoded, result: Encoded, arg: Encoded): Clauses = {
      instantiate(blocker, Seq(Left(result), Left(arg)))
    }

    override def instantiate(substMap: Map[Encoded, Arg]): Clauses = {
      val substituter = mkSubstituter(substMap.mapValues(_.encoded))

      val clauses: Clauses = (for ((b,res,tpe,f) <- functions) yield {
        registerFunction(substituter(b), substituter(res), tpe, substituter(f))
      }).flatten.toSeq

      clauses ++ super.instantiate(substMap)
    }
  }

  /** Generator for [[DatatypeTemplate]] instances. */
  object DatatypeTemplate extends FunctionUnrolling with InvariantGenerator {
    private val cache: MutableMap[Type, DatatypeTemplate] = MutableMap.empty

    def apply(dtpe: Type): DatatypeTemplate = cache.getOrElseUpdate(dtpe, {
      object b extends {
        val tpe = dtpe
      } with super[FunctionUnrolling].Builder
        with super[InvariantGenerator].Builder

      val typeBlockers: TypeBlockers = b.types.map { case (blocker, tps) =>
        blocker -> tps.map { case (res, info, arg) => TemplateTypeInfo(info, arg, Datatype(res)) }
      }

      new DatatypeTemplate(TemplateContents(
        b.pathVar -> b.pathVarT, Seq(b.result -> b.resultT, b.v -> b.idT),
        b.conds, b.exprs, Map.empty, b.tree, b.clauses, b.calls,
        Map.empty, Map.empty, Map.empty, Seq.empty, Seq.empty, Map.empty), typeBlockers, b.funs)
    })
  }

  /** Template used to unfold ADT closures that may contain functions. */
  class CaptureTemplate private[DatatypeTemplates](
    val contents: TemplateContents,
    val types: Map[Encoded, Set[TemplateTypeInfo]],
    val functions: Set[Encoded]) extends TypesTemplate {

    val Seq((_, container), _) = contents.arguments

    def instantiate(blocker: Encoded, container: Encoded, arg: Encoded): Clauses = {
      instantiate(blocker, Seq(Left(container), Left(arg)))
    }

    override def instantiate(substMap: Map[Encoded, Arg]): Clauses = {
      val substituter = mkSubstituter(substMap.mapValues(_.encoded))

      val sc = substituter(container)
      val sfuns = functions.map(substituter)

      lessOrder += sc -> (lessOrder(sc) ++ sfuns)

      super.instantiate(substMap)
    }
  }

  /** Template generator for [[CaptureTemplate]] instances. */
  object CaptureTemplate
    extends FunctionUnrolling
       with ADTUnrolling
       with CachedUnrolling {

    private val tmplCache: MutableMap[Type, (
      (Variable, Encoded),
      (Variable, Encoded),
      Map[Variable, Encoded],
      Map[Variable, Encoded],
      Map[Variable, Set[Variable]],
      Clauses,
      Map[Encoded, Set[(Encoded, TypeInfo, Encoded)]],
      Functions
    )] = MutableMap.empty

    private val cache: MutableMap[(Type, FunctionType), CaptureTemplate] = MutableMap.empty
    private val ordCache: MutableMap[FunctionType, Encoded => Encoded] = MutableMap.empty

    private val lessThan: (Encoded, Encoded) => Encoded = {
      val l = Variable.fresh("left", IntegerType())
      val r = Variable.fresh("right", IntegerType())
      val (lT, rT) = (encodeSymbol(l), encodeSymbol(r))

      val encoded = mkEncoder(Map(l -> lT, r -> rT))(LessThan(l, r))
      (nl: Encoded, nr: Encoded) => mkSubstituter(Map(lT -> nl, rT -> nr))(encoded)
    }

    private def order(tpe: FunctionType): Encoded => Encoded = ordCache.getOrElseUpdate(tpe, {
      val a = Variable.fresh("arg", tpe)
      val o = Variable.fresh("order", FunctionType(Seq(tpe), IntegerType()), true)
      val (aT, oT) = (encodeSymbol(a), encodeSymbol(o))
      val encoded = mkEncoder(Map(a -> aT, o -> oT))(Application(o, Seq(a)))
      (na: Encoded) => mkSubstituter(Map(aT -> na))(encoded)
    })

    def apply(dtpe: Type, containerType: FunctionType): CaptureTemplate = cache.getOrElseUpdate(dtpe -> containerType, {
      val (ps, ids, condVars, exprVars, condTree, clauses, types, funs) = tmplCache.getOrElseUpdate(dtpe, {
        object b extends { val tpe = dtpe } with super[FunctionUnrolling].Builder with super[ADTUnrolling].Builder {
          override val resultT = trueT
        }
        assert(b.calls.isEmpty, "Captured function templates shouldn't have any calls: " + b.calls)

        // Capture templates must always be true, so we can substitute the result value by `true`
        // immediately and ignore the result value in later instantiations.
        val substituter = mkSubstituter(Map(b.resultT -> trueT))
        val substClauses = b.clauses.map(substituter)

        (b.pathVar -> b.pathVarT, b.v -> b.idT, b.conds, b.exprs, b.tree, substClauses, b.types, b.funs)
      })

      val container = Variable.fresh("container", containerType, true)
      val containerT = encodeSymbol(container)

      val typeBlockers: TypeBlockers = types.map { case (blocker, tps) =>
        blocker -> tps.map { case (_, info, arg) => TemplateTypeInfo(info, arg, Capture(containerT, containerType)) }
      }

      val funClauses = funs.map { case (blocker, r, _, _) => mkImplies(blocker, r) }
      val orderClauses = funs.map { case (blocker, _, tpe, f) =>
        mkImplies(blocker, lessThan(order(tpe)(f), order(containerType)(containerT)))
      }

      new CaptureTemplate(TemplateContents(
        ps, Seq(container -> containerT, ids),
        condVars, exprVars, Map.empty, condTree, clauses ++ funClauses ++ orderClauses,
        Map.empty, Map.empty, Map.empty, Map.empty, Seq.empty, Seq.empty, Map.empty
      ), typeBlockers, funs.map(_._4))
    })
  }

  /** Extends [[TemplateGenerator]] with functionalities for checking whether
    * tuples need to be unrolled. This trait will typically be mixed in with
    * the [[ADTUnrolling]] trait. */
  trait FlatUnrolling extends TemplateGenerator {
    def unroll(tpe: Type): Boolean = tpe match {
      case TupleType(tps) => tps.exists(unroll)
      case _ => false
    }
  }

  private[unrolling] object datatypesManager extends Manager {
    private[DatatypeTemplates] val typeInfos = new IncrementalMap[Encoded, (Int, Int, Encoded, Set[TemplateTypeInfo])]
    private[DatatypeTemplates] val lessOrder = new IncrementalMap[Encoded, Set[Encoded]].withDefaultValue(Set.empty)
    private[DatatypeTemplates] val results = new IncrementalMap[Type, Encoded]

    def canBeEqual(f1: Encoded, f2: Encoded): Boolean = {
      def transitiveLess(l: Encoded, r: Encoded): Boolean = {
        val fs = fixpoint((fs: Set[Encoded]) => fs ++ fs.flatMap(lessOrder))(lessOrder(l))
        fs(r)
      }

      !transitiveLess(f1, f2) && !transitiveLess(f2, f1)
    }

    val incrementals: Seq[IncrementalState] = Seq(typeInfos, lessOrder, results)

    def unrollGeneration: Option[Int] =
      if (typeInfos.isEmpty) None
      else Some(typeInfos.values.map(_._1).min)

    def satisfactionAssumptions: Seq[Encoded] =
      typeInfos.map(_._2._3).toSeq ++ DatatypeTemplate.satisfactionAssumptions

    def refutationAssumptions: Seq[Encoded] = Seq.empty

    def promoteBlocker(b: Encoded): Boolean = {
      if (typeInfos contains b) {
        val (_, origGen, notB, tps) = typeInfos(b)
        typeInfos += b -> (currentGeneration, origGen, notB, tps)
        true
      } else {
        false
      }
    }

    def unroll: Clauses = if (typeInfos.isEmpty) Seq.empty else {
      val newClauses = new scala.collection.mutable.ListBuffer[Encoded]

      val typeBlockers = typeInfos.filter(_._2._1 <= currentGeneration).toSeq.map(_._1)
      val newTypeInfos = typeBlockers.flatMap(id => typeInfos.get(id).map(id -> _))
      typeInfos --= typeBlockers

      for ((blocker, (gen, _, _, tps)) <- newTypeInfos; TemplateTypeInfo(info, arg, inst) <- tps) inst match {
        case Datatype(result) =>
          val template = DatatypeTemplate(info.getType)
          newClauses ++= template.instantiate(blocker, result, arg)
        case Capture(container, containerType) =>
          val template = CaptureTemplate(info.getType, containerType)
          newClauses ++= template.instantiate(blocker, container, arg)
      }

      reporter.debug("Unrolling datatypes (" + newClauses.size + ")")
      for (cl <- newClauses) {
        reporter.debug("  . " + cl)
      }

      newClauses.toSeq
    }
  }
}
