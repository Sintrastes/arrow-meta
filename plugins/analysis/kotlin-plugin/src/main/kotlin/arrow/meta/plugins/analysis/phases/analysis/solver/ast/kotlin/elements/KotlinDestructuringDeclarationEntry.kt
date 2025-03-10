package arrow.meta.plugins.analysis.phases.analysis.solver.ast.kotlin.elements

import arrow.meta.plugins.analysis.phases.analysis.solver.ast.context.elements.DestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry

fun interface KotlinDestructuringDeclarationEntry :
  DestructuringDeclarationEntry, KotlinVariableDeclaration {
  override fun impl(): KtDestructuringDeclarationEntry
  override val isVar: Boolean
    get() = impl().isVar
}
