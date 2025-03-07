package arrow.meta.plugins.analysis.phases.analysis.solver.check.model

import arrow.meta.plugins.analysis.phases.analysis.solver.ast.context.elements.Element
import org.sosy_lab.java_smt.api.BooleanFormula

data class CurrentVarInfo(private val varInfo: List<VarInfo>) {

  fun get(name: String): VarInfo? = varInfo.firstOrNull { it.name == name }

  fun add(
    name: String,
    smtName: String,
    origin: Element,
    invariant: BooleanFormula? = null
  ): CurrentVarInfo = this.add(listOf(VarInfo(name, smtName, origin, invariant)))

  fun add(vars: List<VarInfo>): CurrentVarInfo = CurrentVarInfo(vars + varInfo)

  companion object {
    fun new(): CurrentVarInfo = CurrentVarInfo(emptyList())
  }
}
