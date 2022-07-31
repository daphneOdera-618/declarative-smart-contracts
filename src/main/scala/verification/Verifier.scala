package verification

import com.microsoft.z3.{ArithSort, ArrayExpr, ArraySort, BitVecSort, BoolExpr, BoolSort, Context, Expr, Quantifier, Sort, Symbol, TupleSort}
import datalog.{Add, AnyType, Arithmetic, Assign, BinFunctor, BinaryOperator, BooleanType, CompoundType, Constant, Equal, Geq, Greater, Leq, Lesser, Literal, MsgSender, MsgValue, Mul, Negative, Now, NumberType, One, Param, Parameter, Program, Relation, ReservedRelation, Rule, Send, SimpleRelation, SingletonRelation, Sub, SymbolType, Type, Unequal, UnitType, Variable, Zero}
import imp.SolidityTranslator.transactionRelationPrefix
import imp.Translator.getMaterializedRelations
import imp.{AbstractImperativeTranslator, DeleteTuple, ImperativeAbstractProgram, IncrementValue, InsertTuple, ReplacedByKey, Trigger}
import verification.TransitionSystem.makeStateVar
import verification.Z3Helper.{addressSize, functorToZ3, getSort, literalToConst, makeTupleSort, paramToConst, simplifyByRenamingConst, typeToSort, uintSize}
import view.{CountView, JoinView, MaxView, SumView, View}

class Verifier(program: Program, impAbsProgram: ImperativeAbstractProgram)
  extends AbstractImperativeTranslator(program, isInstrument = true) {

  private val ctx: Context = new Context()

  protected val relations: Set[Relation] = program.relations
  protected val indices: Map[SimpleRelation, List[Int]] = impAbsProgram.indices

  private val materializedRelations: Set[Relation] = getMaterializedRelations(impAbsProgram, program.interfaces)
       .filterNot(_.isInstanceOf[ReservedRelation])
  override val rulesToEvaluate: Set[Rule] = getRulesToEvaluate().filterNot(r => program.violations.contains(r.head.relation))

  private def getIndices(relation: Relation): List[Int] = relation match {
    case sr:SimpleRelation => indices(sr)
    case SingletonRelation(name, sig, memberNames) => List()
    case relation: ReservedRelation => ???
  }

  def check(): Unit = {
    val violationRules: Set[Rule] = program.rules.filter(r => program.violations.contains(r.head.relation))
    val tr = TransitionSystem(program.name, ctx)

    var initConditions: List[BoolExpr] = List()
    for (rel <- materializedRelations) {
      val sort = getSort(ctx, rel, getIndices(rel))
      val (v_in, _) = tr.newVar(rel.name, sort)
      initConditions :+= getInitConstraints(rel, v_in)
    }
    tr.setInit(ctx.mkAnd(initConditions.toArray:_*))

    val transitionConditions = getTransitionConstraints()
    tr.setTr(transitionConditions)

    for (vr <- violationRules) {
      val property = getProperty(ctx, vr)
      val (resInit, resTr) = tr.inductiveProve(ctx, property)
      println(property)
      println(s"Init: $resInit")
      println(s"Tr: $resTr")
    }
  }

  private def initValue(_type: Type): Expr[_<:Sort] = _type.name match {
    case "address" => ctx.mkBV(0, addressSize)
    case "int" => ctx.mkInt(0)
    case "uint" => ctx.mkBV(0, uintSize)
  }

  private def getInitConstraints(relation: Relation, const: Expr[Sort]): BoolExpr = relation match {
    case sr: SimpleRelation => {
      val keyTypes: List[Type] = indices(sr).map(i=>relation.sig(i))
      val valueTypes: List[Type] = relation.sig.filterNot(t => keyTypes.contains(t))
      val initValues = if (valueTypes.size == 1) {
        initValue(valueTypes.head)
      }
      else {
        val _initValues = valueTypes.toArray.map(initValue)
        val tupleSort = makeTupleSort(ctx, s"${sr.name}Tuple", valueTypes.toArray)
        tupleSort.mkDecl().apply(_initValues:_*)
      }

      val keyConstArray: Array[Expr[_]] = keyTypes.toArray.zipWithIndex.map{
          case (t,i) => ctx.mkConst(s"${t.name.toLowerCase()}$i", typeToSort(ctx,t))
      }

      ctx.mkForall(keyConstArray, ctx.mkEq(
            ctx.mkSelect(const.asInstanceOf[ArrayExpr[Sort,Sort]], keyConstArray),
            initValues),
        1, null, null, ctx.mkSymbol(s"Q${sr.name}"), ctx.mkSymbol(s"skid${sr.name}"))
    }
    case SingletonRelation(name, sig, memberNames) => ctx.mkEq(const, initValue(sig.head))
    case rel: ReservedRelation => ???
  }

  private def getProperty(ctx: Context, rule: Rule): BoolExpr = {
    /** Each violation query rule is translated into a property as follows:
     *  ! \E (V), P1 /\ P2 /\ ...
     *  where V is the set of variable appears in the rule body,
     *  P1, P2, ... are predicates translated from each rule body literal.
     *  */
    val prefix = "p"
    val _vars: Array[Expr[_]] = rule.body.flatMap(_.fields).toArray.map(p => paramToConst(ctx,p,prefix)._1)
    val bodyConstraints = rule.body.map(lit => literalToConst(ctx, lit, getIndices(lit.relation), prefix)).toArray
    val functorConstraints = rule.functors.map(f => functorToZ3(ctx,f, prefix)).toArray
    ctx.mkNot(ctx.mkExists(
        _vars,
        ctx.mkAnd(bodyConstraints++functorConstraints:_*),
        1, null, null, ctx.mkSymbol("Q"), ctx.mkSymbol("skid2")
      )
    )
  }

  private def getTransitionConstraints(): BoolExpr = {

    val triggers: Set[Trigger] = {
      val relationsToTrigger = program.interfaces.map(_.relation).filter(r => r.name.startsWith(transactionRelationPrefix))
      val relationsInBodies = program.rules.flatMap(_.body).map(_.relation)
      val toTrigger = relationsInBodies.intersect(relationsToTrigger)
      toTrigger.map(rel => InsertTuple(rel,primaryKeyIndices(rel)))
    }

    var transactionConstraints: List[BoolExpr] = List()
    var transactionConst: List[BoolExpr] = List()
    for (t <- triggers) {
      val triggeredRules: Set[Rule] = getTriggeredRules(t)
      for (rule <- triggeredRules) {
        val c = ruleToExpr(rule, t).simplify().asInstanceOf[BoolExpr]

        /** Add the "unchanged" constraints */
        var unchangedConstraints: List[BoolExpr] = List()
        for (rel <- materializedRelations) {
          val sort = getSort(ctx, rel, getIndices(rel))
          val (v_in, v_out) = makeStateVar(ctx, rel.name, sort)
          val allVars = Prove.get_vars(c)
          if (!allVars.contains(v_out)) {
            unchangedConstraints :+= ctx.mkEq(v_out, v_in)
          }
        }
        val trConst = ctx.mkBoolConst(s"${t.relation.name}")
        transactionConst +:= trConst
        transactionConstraints +:= ctx.mkAnd((trConst::c::unchangedConstraints).toArray:_*)
      }
    }


    ctx.mkAnd(
      transactionConst.foldLeft(ctx.mkFalse())(ctx.mkXor).simplify(),
      ctx.mkOr(transactionConstraints.toArray:_*)
    )
  }

  private def ruleToExpr(rule: Rule, trigger: Trigger): BoolExpr = {
    var id = 0

    def _ruleToExpr(rule: Rule, trigger: Trigger): (Array[BoolExpr], Array[(Expr[Sort], Expr[Sort], Expr[_<:Sort])], Int) = {
      val _id = id
      id += 1

      val view = views(rule)
      val isMaterialized = materializedRelations.contains(rule.head.relation)
      val (bodyConstraints, _updateExprs) = view.getZ3Constraint(ctx, trigger, isMaterialized, getPrefix(_id))
      val nextTriggers = view.getNextTriggers(trigger)

      var exprs: Array[BoolExpr] = bodyConstraints
      var updateExprs = _updateExprs
      for (t <- nextTriggers) {
        val dependentRules: Set[Rule] = getTriggeredRules(t)
        for (dr <- dependentRules) {
          val (dependentConstraints, dependentUpdateExprs, nid) = _ruleToExpr(dr, t)
          val (_, from, to) = getNamingConstraints(rule, dr, _id, nid)
          val renamed: Array[BoolExpr] = dependentConstraints.map(_.substitute(from, to).asInstanceOf[BoolExpr])
          val renamedUpdates = dependentUpdateExprs.map(t => (t._1, t._2, t._3.substitute(from, to)))
          exprs ++= renamed
          updateExprs ++= renamedUpdates
        }
      }
      (exprs, updateExprs , _id)
    }

    val (exprs, updates, _) = _ruleToExpr(rule,trigger)
    val merged = mergeUpdates(updates)
    val expr = ctx.mkAnd(exprs++merged:_*)
    val simplified = simplifyByRenamingConst(expr)
    simplified.asInstanceOf[BoolExpr]
  }

  private def mergeUpdates(updates: Array[(Expr[Sort], Expr[Sort], Expr[_<:Sort])]): Array[BoolExpr] = {
    /** Each tuple: (v_in, v_out, v_in + delta )
     * */
    def _mergeUpdates(_updates: Array[(Expr[Sort], Expr[Sort], Expr[_<:Sort])]): BoolExpr = {
      require(_updates.size > 1)
      val v_out = _updates.head._2
      var updateExpr = _updates(1)._3
      for (i <- 0 to _updates.size-2) {
        val from = _updates(i+1)._1
        val to = _updates(i)._3
        updateExpr = updateExpr.substitute(from,to)
      }
      ctx.mkEq(v_out, updateExpr)
    }
    updates.groupBy(_._1).map {
      case (_,v) => if (v.size > 1) {
        _mergeUpdates(v)
      } else {
        ctx.mkEq(v.head._2, v.head._3)
      }
    }.toArray
  }

  private def getNamingConstraints(rule: Rule, dependentRule: Rule, id1: Int, id2: Int): (BoolExpr, Array[Expr[_]], Array[Expr[_]]) = {
    val headLiteral = rule.head
    val bodyLiteral = views(dependentRule) match {
      case CountView(rule, primaryKeyIndices, ruleId) => ???
      case _: JoinView => {
        val _s = dependentRule.body.filter(_.relation == rule.head.relation)
        assert(_s.size==1)
        _s.head
      }
      case MaxView(rule, primaryKeyIndices, ruleId) => ???
      case sv: SumView => sv.sum.literal
      case _ => ???
    }

    var expr: List[BoolExpr] = List()
    var from: List[Expr[_]] = List()
    var to: List[Expr[_]] = List()
    for (v <- headLiteral.fields.zip(bodyLiteral.fields)) {
      if (v._1.name != "_" && v._2.name != "_") {
        val (x1,_) = paramToConst(ctx, v._1, getPrefix(id1))
        val (x2,_) = paramToConst(ctx, v._2, getPrefix(id2))
        expr +:= ctx.mkEq(x1,x2)
        to +:= x1
        from +:= x2
      }
    }
    (ctx.mkAnd(expr.toArray:_*), from.toArray, to.toArray)
  }

  private def getPrefix(id: Int): String = s"i$id"

}
