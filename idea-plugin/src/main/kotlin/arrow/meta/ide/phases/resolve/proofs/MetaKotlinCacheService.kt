package arrow.meta.ide.phases.resolve.proofs

import arrow.meta.log.Log
import arrow.meta.log.invoke
import arrow.meta.phases.resolve.disposeProofCache
import arrow.meta.phases.resolve.initializeProofCache
import arrow.meta.phases.resolve.proofCache
import arrow.meta.phases.resolve.typeProofs
import arrow.meta.proofs.extensionCallables
import arrow.meta.proofs.extensions
import arrow.meta.quotes.get
import com.intellij.openapi.components.ProjectComponent
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.testFramework.registerServiceInstance
import com.intellij.util.pico.DefaultPicoContainer
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.caches.resolve.KotlinCacheService
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.BindingTraceContext
import org.jetbrains.kotlin.resolve.BindingTraceFilter
import org.jetbrains.kotlin.resolve.DelegatingBindingTrace
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.MutableDataFlowInfoForArguments
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCallImpl
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.tasks.ResolutionCandidate
import org.jetbrains.kotlin.resolve.calls.tasks.TracingStrategy
import org.jetbrains.kotlin.resolve.diagnostics.KotlinSuppressCache
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.types.expressions.KotlinTypeInfo
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

private class MetaKotlinCacheServiceHelper(private val delegate: KotlinCacheService) : KotlinCacheService by delegate {
  override fun getResolutionFacade(elements: List<KtElement>): ResolutionFacade =
    Log.Verbose({ "MetaKotlinCacheServiceHelper.getResolutionFacade $elements $this" }) {
      delegate.getResolutionFacade(elements).initializeProofsIfNeeded()
    }

  private fun ResolutionFacade.initializeProofsIfNeeded(): ResolutionFacade {
    if (moduleDescriptor.typeProofs.isEmpty()) {
      Log.Verbose({ "MetaKotlinCacheServiceHelper.initializeProofCache $moduleDescriptor ${this.size}" }) {
        println("Current cache size: ${proofCache.size}")
        moduleDescriptor.initializeProofCache()
      }
    }
    return MetaResolutionFacade(this)
  }

  override fun getResolutionFacade(elements: List<KtElement>, platform: TargetPlatform): ResolutionFacade =
    Log.Verbose({ "MetaKotlinCacheServiceHelper.getResolutionFacade $elements $platform $this" }) {
      delegate.getResolutionFacade(elements, platform).initializeProofsIfNeeded()
    }

  override fun getResolutionFacadeByFile(file: PsiFile, platform: TargetPlatform): ResolutionFacade? =
    Log.Verbose({ "MetaKotlinCacheServiceHelper.getResolutionFacadeByFile $file $platform $this" }) {
      delegate.getResolutionFacadeByFile(file, platform)?.initializeProofsIfNeeded()
    }

  override fun getResolutionFacadeByModuleInfo(moduleInfo: ModuleInfo, platform: TargetPlatform): ResolutionFacade? =
    Log.Verbose({ "MetaKotlinCacheServiceHelper.getResolutionFacadeByModuleInfo $moduleInfo $platform $this" }) {
      delegate.getResolutionFacadeByModuleInfo(moduleInfo, platform)?.initializeProofsIfNeeded()
    }

  override fun getSuppressionCache(): KotlinSuppressCache =
    Log.Verbose({ "MetaKotlinCacheServiceHelper.getSuppressionCache $this" }) {
      delegate.getSuppressionCache()
    }

}

class MetaResolutionFacade(val delegate: ResolutionFacade) : ResolutionFacade by delegate {

  private fun AnalysisResult.checkProofed(): AnalysisResult {
    val (ctx, _, _) = this
    //ctx.resolveExtensionCalls()
    return this
  }

  private fun BindingContext.resolveExtensionCalls() {
    getSliceContents(BindingContext.CALL).forEach {
      val (element, call) = it
      val resolvedCall = call.getResolvedCall(this)
      if (resolvedCall == null) {
        call.explicitReceiver?.safeAs<ExpressionReceiver>()?.let {
          val from = it.type
          val extensions = delegate.moduleDescriptor.typeProofs.extensions(from)
          extensions.forEach {
            val callable = it.extensionCallables { true }.find {
              it.name.asString() == call.callElement.text.substringBefore("(")
            }
            if (callable != null) {
              val innerContext: BindingContext = this.javaClass
                .getDeclaredField("context")
                .also { it.isAccessible = true }.let {
                  it.get(this) as BindingContext
                }

              val trace: BindingTrace = innerContext.safeAs<MetaBindingContext>()?.trace ?: innerContext.javaClass
                .getDeclaredField("this$0")
                .also { it.isAccessible = true }.let {
                  it.get(innerContext) as BindingTrace
                }
              val candidate: ResolutionCandidate<CallableMemberDescriptor> = ResolutionCandidate.create(call, callable)
              val proofResolvedCall = ResolvedCallImpl.create(
                candidate, DelegatingBindingTrace(this, "Proof Resolution", callable, BindingTraceFilter(false)), TracingStrategy.EMPTY, MutableDataFlowInfoForArguments.WithoutArgumentsCheck(DataFlowInfo.EMPTY)
              )
              trace.record(BindingContext.RESOLVED_CALL, call, proofResolvedCall)
              call.callElement.safeAs<KtExpression>()?.apply {
                trace.record(BindingContext.EXPRESSION_TYPE_INFO, this, KotlinTypeInfo(callable.returnType, DataFlowInfo.EMPTY, true, DataFlowInfo.EMPTY))
                println("MetaResolutionFacade Recorded RESOLVED_CALL: ${call.callElement.text} to -> $callable")
              }
              println("MetaResolutionFacade Recorded EXPRESSION_TYPE_INFO: ${call.calleeExpression?.text}, ${callable.returnType}")
            }
          }
        }
        println("MetaResolutionFacade.checkProofed ${element.text}, call: $call, resolvedCall: $resolvedCall")
      }
    }
  }

  override fun analyze(elements: Collection<KtElement>, bodyResolveMode: BodyResolveMode): BindingContext =
    Log.Verbose({ "MetaResolutionFacade.analyze" }) {
      delegate.analyze(elements, bodyResolveMode)//.apply { resolveExtensionCalls() }
    }

  override fun analyze(element: KtElement, bodyResolveMode: BodyResolveMode): BindingContext =
    Log.Verbose({ "MetaResolutionFacade.analyze" }) {
      delegate.analyze(element, bodyResolveMode)//.apply { resolveExtensionCalls() }
    }

  override fun analyzeWithAllCompilerChecks(elements: Collection<KtElement>): AnalysisResult =
    Log.Verbose({ "MetaResolutionFacade.analyzeWithAllCompilerChecks" }) {
      delegate.analyzeWithAllCompilerChecks(elements)//.checkProofed()
    }

  override fun resolveToDescriptor(declaration: KtDeclaration, bodyResolveMode: BodyResolveMode): DeclarationDescriptor =
    Log.Verbose({ "MetaResolutionFacade.resolveToDescriptor" }) {
      delegate.resolveToDescriptor(declaration, bodyResolveMode)
    }
}

class MetaKotlinCacheService(val project: Project) : ProjectComponent {

  val delegate: KotlinCacheService = KotlinCacheService.getInstance(project)

  override fun initComponent() {
    Log.Verbose({ "MetaKotlinCacheService.initComponent" }) {
      project.replaceKotlinCacheService { MetaKotlinCacheServiceHelper(delegate) }
    }
  }

  override fun disposeComponent() {
    Log.Verbose({ "MetaKotlinCacheService.disposeComponent" }) {
      disposeProofCache()
      project.replaceKotlinCacheService { delegate }
    }
  }

  private inline fun Project.replaceKotlinCacheService(f: (KotlinCacheService) -> KotlinCacheService): Unit {
    picoContainer.safeAs<DefaultPicoContainer>()?.apply {
      getComponentAdapterOfType(KotlinCacheService::class.java)?.apply {
        val instance = getComponentInstance(componentKey) as? KotlinCacheService
        if (instance != null) {
          val newInstance = f(instance)
          unregisterComponent(componentKey)
          registerServiceInstance(KotlinCacheService::class.java, newInstance)
        }
      }
    }
  }

}
