package arrow.meta.ide.plugins.proofs

import arrow.meta.Plugin
import arrow.meta.ide.IdeMetaPlugin
import arrow.meta.ide.resources.ArrowIcons
import arrow.meta.invoke
import arrow.meta.phases.analysis.isAnnotatedWith
import arrow.meta.plugins.proofs.phases.proofs
import arrow.meta.plugins.proofs.phases.Proof
import arrow.meta.plugins.proofs.phases.ProofStrategy
import arrow.meta.plugins.proofs.phases.resolve.suppressProvenTypeMismatch
import arrow.meta.quotes.ScopedList
import arrow.meta.quotes.nameddeclaration.stub.typeparameterlistowner.NamedFunction
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptorIfAny
import org.jetbrains.kotlin.idea.refactoring.pullUp.renderForConflicts
import org.jetbrains.kotlin.idea.util.IdeDescriptorRenderers
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.Call
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.blockExpressionsOrSingle
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.smartcasts.SingleSmartCast
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.resolve.lazy.ResolveSession
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.SimpleType
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

val proofAnnotation: Regex = Regex("@(arrow\\.)?Proof\\((.*)\\)")

fun KtNamedFunction.isProof(): Boolean =
  isAnnotatedWith(proofAnnotation)

private fun KtNamedFunction.isProofOf(strategy: ProofStrategy): Boolean =
  isAnnotatedWith(proofAnnotation) && this.proofTypes().value.any {
    it.text.endsWith(strategy.name)
  }

fun KtNamedFunction.isExtensionProof(): Boolean = isProofOf(ProofStrategy.Extension)

fun KtNamedFunction.isNegationProof(): Boolean = isProofOf(ProofStrategy.Negation)

fun KtNamedFunction.isRefinementProof(): Boolean = isProofOf(ProofStrategy.Refinement)

fun KtNamedFunction.isSubtypingProof(): Boolean = isProofOf(ProofStrategy.Subtyping)

fun KtNamedFunction.proofTypes(): ScopedList<KtExpression> =
  ScopedList(annotationEntries
    .first { it.text.matches(proofAnnotation) }
    .valueArguments.mapNotNull { it.getArgumentExpression() })

fun NamedFunction.withStrategy(strategy: ProofStrategy, f: NamedFunction.() -> String): String =
  when {
    value.proofTypes().value.any {
      it.text.endsWith(strategy.name)
    } -> f(this)
    else -> ""
  }

val FunctionDescriptor.from: String
  get() =
    extensionReceiverParameter?.type?.let(IdeDescriptorRenderers.SOURCE_CODE::renderType).orEmpty()

val FunctionDescriptor.to: String
  get() =
    returnType?.toString().orEmpty()

fun FunctionDescriptor.proof(): Proof? =
  module.proofs.find { it.through.fqNameSafe == fqNameSafe }

fun Proof.extensionMarkerMessage(name: Name?): String {
  return """
  All members of <code lang="kotlin">$to</code> become available in <code lang="kotlin">$from</code> :
  <ul>
  ${through.returnTypeCallableMembers().joinToString("\n -") {
    """
            <li>${it.renderForConflicts()}</li>
            """.trimIndent()
  }}
  </ul>
  <code lang="kotlin">$from</code> does not need to explicitly extend <code lang="kotlin">$to</code>, instead <code lang="kotlin">$name</code>
  is used as proof to support the intersection of <code lang="kotlin">$from & $to</code>.
  """.trimIndent()
}

fun Proof.negationMarkerMessage(name: Name?): String {
  return "TODO"
}

fun Proof.refinementMarkerMessage(name: Name?): String {
  return """
        <code lang="kotlin">$from</code> is a refined type that represents a set of values of the type <code lang="kotlin">$to</code>
        that meet a certain type-level predicate.
        ```
  """.trimIndent()
}

fun Proof.subtypingMarkerMessage(name: Name?): String {
  return """
        <code lang="kotlin">$from</code> is seen as subtype of <code lang="kotlin">$to</code> :
        
        <code lang="kotlin">
        $from : $to
        </code>
        ```
        
        <code lang="kotlin">$from</code> does not need to explicitly extend <code lang="kotlin">$to</code>, instead <code lang="kotlin">$name</code> 
        is used as injective function proof to support all subtype associations of <code lang="kotlin">$from : $to</code>.
        
        <code lang="kotlin">
        val a: $from = TODO()
        val b: $to = a //ok
        </code>
        
        In the example above compiling <code lang="kotlin">val b: $to = a</code> would have failed to compile but because we have proof of 
        <code lang="kotlin">$from : $to</code> this becomes a valid global ad-hoc synthetic subtype relationship.
        """.trimIndent()
}

fun KtNamedFunction.markerMessage(): String =
  NamedFunction(this).run {
    value.resolveToDescriptorIfAny(bodyResolveMode = BodyResolveMode.PARTIAL)?.proof()?.let { proof ->
      """
      <code lang="kotlin">${text}</code> 

      ${withStrategy(ProofStrategy.Extension) {
        Proof::extensionMarkerMessage.invoke(proof, this.name)
      }}
      ${withStrategy(ProofStrategy.Negation) {
        Proof::negationMarkerMessage.invoke(proof, this.name)
      }}
      ${withStrategy(ProofStrategy.Refinement) {
        Proof::refinementMarkerMessage.invoke(proof, this.name)
      }}
      ${withStrategy(ProofStrategy.Subtyping) {
        Proof::subtypingMarkerMessage.invoke(proof, this.name)
      }}
      
    <a href="">More info on Type Proofs</a>: ${ProofStrategy.values().joinToString { """<code lang="kotlin">${it.name}</code>""" }}
    """.trimIndent()
    }.orEmpty()
  }

private fun FunctionDescriptor.returnTypeCallableMembers(): List<CallableMemberDescriptor> =
  returnType
    ?.memberScope
    ?.getContributedDescriptors { true }
    ?.filterIsInstance<CallableMemberDescriptor>()
    ?.filter { it.kind != CallableMemberDescriptor.Kind.FAKE_OVERRIDE }
    .orEmpty()

@Suppress("UnstableApiUsage")
val IdeMetaPlugin.proofsIdePlugin: Plugin
  get() = "ProofsIdePlugin" {
    meta(
      addLineMarkerProvider(
        icon = ArrowIcons.INTERSECTION,
        composite = KtNamedFunction::class.java,
        transform = {
          it.safeAs<KtNamedFunction>()?.takeIf(KtNamedFunction::isExtensionProof)
        },
        message = {
          it.markerMessage()
        }
      ),
      addLineMarkerProvider(
        icon = ArrowIcons.NEGATION,
        composite = KtNamedFunction::class.java,
        transform = {
          it.safeAs<KtNamedFunction>()?.takeIf(KtNamedFunction::isNegationProof)
        },
        message = {
          it.markerMessage()
        }
      ),
      addLineMarkerProvider(
        icon = ArrowIcons.REFINEMENT,
        composite = KtNamedFunction::class.java,
        transform = {
          it.safeAs<KtNamedFunction>()?.takeIf(KtNamedFunction::isRefinementProof)
        },
        message = {
          it.markerMessage()
        }
      ),
      addLineMarkerProvider(
        icon = ArrowIcons.SUBTYPING,
        composite = KtNamedFunction::class.java,
        transform = {
          it.safeAs<KtNamedFunction>()?.takeIf(KtNamedFunction::isSubtypingProof)
        },
        message = {
          it.markerMessage()
        }
      )
//      extraImports {
//        Log.Verbose({ "extraImports: $this" }) {
//          val cachedModule = cachedModule()
//          cachedModule?.typeProofs?.importableNames()?.mapNotNull { fqName ->
//            println("import $fqName")
//            importDirective(ImportPath(fqName, true)).value
//          }.orEmpty()
//        }
//      }
//      syntheticResolver(
//        generatePackageSyntheticClasses = { thisDescriptor, name, ctx, declarationProvider, result ->
//          Log.Verbose({ "resolveBodyWithExtensionsScope $thisDescriptor $name" }) {
//            result.firstOrNull()?.ktFile()?.let {
//              Log.Verbose({ "resolveBodyWithExtensionsScope file $it" }) {
//                resolveBodyWithExtensionsScope(ctx as ResolveSession, it)
//              }
//            }
//          }
//        }
//      ),
//      addDiagnosticSuppressor { diagnostic ->
//        if (diagnostic.factory == Errors.UNRESOLVED_REFERENCE) {
//          Errors.UNRESOLVED_REFERENCE.cast(diagnostic).let {
//            module.typeProofs.extensions().any { ext ->
//              ext.to.memberScope.getContributedDescriptors { true }.any {
//                it.name.asString() == diagnostic.psiElement.text
//              }
//            }
//          }
//        } else false
//      },
      ,
      addDiagnosticSuppressor { suppressProvenTypeMismatch(it, module.proofs) }
//      addDiagnosticSuppressor { it.suppressUpperboundViolated(module.typeProofs) }
    )
  }

//    modifiedScope.implicitReceiver?.type?.asSimpleType()?.let {
//      applySmartCast(null, it, ktCallable, session)
//    }


fun List<Proof>.mentioning(kotlinType: KotlinType): List<Proof> =
  filter { it.from.constructor == kotlinType.constructor && it.to.constructor == kotlinType.constructor }

//fun Call.inProof(proofs: List<Proof>): List<Proof> {
//  val type = this.call
//  return proofs.filter { proof ->
//    type.`isSubtypeOf(NewKotlinTypeChecker)`(proof.from) ||
//      type.`isSubtypeOf(NewKotlinTypeChecker)`(proof.to)
//  }
//}
//
//private fun SimpleFunctionDescriptor.referencedProofedCalls(proofs: List<Proof>, bindingContext: BindingContext): List<Pair<ResolvedCall<*>, List<Proof>>> =
//  findPsi()
//    ?.collectDescendantsOfType<KtCallExpression> { true }
//    ?.mapNotNull { it.getCall(bindingContext) }
//    ?.map { it to it.inProof(proofs) }
//    .orEmpty()

private fun applySmartCast(
  call: Call?,
  uberExtendedType: SimpleType,
  ktCallable: KtExpression,
  session: ResolveSession
) {
  val smartCast = SingleSmartCast(call, uberExtendedType)
  ktCallable.blockExpressionsOrSingle().filterIsInstance<KtExpression>().firstOrNull()?.let { expression ->
    session.trace.record(BindingContext.SMARTCAST, expression, smartCast)
    ExpressionReceiver.create(expression, uberExtendedType, session.trace.bindingContext)
  }
}

//private fun Proof.lexicalScope(currentScope: LexicalScope, containingDeclaration: DeclarationDescriptor): LexicalScope {
//  val proofIntersection = from.intersection(to)
//  val ownerDescriptor = AnonymousFunctionDescriptor(containingDeclaration, Annotations.EMPTY, CallableMemberDescriptor.Kind.DECLARATION, SourceElement.NO_SOURCE, false)
//  val extensionReceiver = ExtensionReceiver(ownerDescriptor, proofIntersection, null)
//  val extensionReceiverParamDescriptor = ReceiverParameterDescriptorImpl(ownerDescriptor, extensionReceiver, ownerDescriptor.annotations)
//  ownerDescriptor.initialize(extensionReceiverParamDescriptor, null, through.typeParameters, through.valueParameters, through.returnType, Modality.FINAL, through.visibility)
//  return LexicalScopeImpl(currentScope, ownerDescriptor, true, extensionReceiverParamDescriptor, LexicalScopeKind.FUNCTION_INNER_SCOPE)
//}


