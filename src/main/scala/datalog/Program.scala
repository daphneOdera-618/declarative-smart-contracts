package datalog

sealed abstract class Parameter {
  def _type: Type
  def name: String
  override def toString: String = name
  def setType(newType: Type): Parameter
}

case class Constant(_type: Type, name: String) extends Parameter {
  def setType(newType: Type): Constant = this.copy(_type=newType)
}
case class Variable(_type: Type, name: String) extends Parameter {
  def setType(newType: Type): Variable = this.copy(_type=newType)
}

sealed abstract class Relation {
  def name: String
  def sig: List[Type]
  def memberNames: List[String]
  require(sig.size == memberNames.size)
}
object Relation {
  val reservedRelations: Set[Relation] = Set(
    MsgSender()
  )
}

case class SimpleRelation(name: String, sig: List[Type], memberNames: List[String]) extends Relation
case class SingletonRelation(name: String, sig: List[Type], memberNames: List[String]) extends Relation
sealed abstract class ReservedRelation extends Relation
case class MsgSender() extends ReservedRelation {
  def name: String = "msgSender"
  def sig: List[Type] = List(Type.addressType)
  def memberNames: List[String] = List("p")
}

case class Literal(relation: Relation, fields: List[Parameter]) {
  override def toString: String = {
    val fieldStr = fields.mkString(",")
    s"${relation.name}($fieldStr)"
  }
}

case class Rule(head: Literal, body: Set[Literal], functors: Set[Functor], aggregators: Set[Aggregator]) {
  override def toString: String = {
    val litStr = body.map(_.toString)
    val functorStr = functors.map(_.toString)
    val aggStr = aggregators.map(_.toString)
    val bodyStr = (litStr++functorStr++aggStr).mkString(",")
    s"$head :- $bodyStr."
  }

  def groundedParams: Set[Parameter] = {
    val allParams = (head.fields ++ body.flatMap(_.fields)).toSet.filterNot(_.name=="_")
    allParams
  }
}

case class Interface(relation: Relation, inputIndices: List[Int], optReturnIndex: Option[Int]) {
  def inputTypes: List[Type] = inputIndices.map(i => relation.sig(i))
  def returnType: Type = optReturnIndex match {
    case Some(i) => relation.sig(i)
    case None => UnitType()
  }
  override def toString: String = {
    val inputStr = inputTypes.mkString(",")
    val retStr = returnType match {
      case _: AnyType => throw new Exception(s"Interface ${relation} does not return Any type.")
      case _: UnitType => s""
      case _:SymbolType|_:NumberType|_:CompoundType => s": $returnType"
    }
    s"${relation.name}($inputStr)" + retStr
  }
}
case class Program(rules: Set[Rule], interfaces: Set[Interface], relationIndices: Map[SimpleRelation, Int],
                   name: String = "Contract0") {
  val relations = rules.flatMap(r => r.body.map(_.relation) + r.head.relation)
  override def toString: String = {
    var ret: String = s""
    ret += "Interfaces:\n" + interfaces.mkString("\n") + "\n"
    ret += "Indices:\n" + relationIndices.mkString("\n") + "\n"
    ret += "Rules:\n" + rules.mkString("\n")
    ret
  }
  def setName(newName: String): Program = this.copy(name=newName)
}
