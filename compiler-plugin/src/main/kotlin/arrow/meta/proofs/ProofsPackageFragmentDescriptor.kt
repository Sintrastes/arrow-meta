package arrow.meta.proofs

import arrow.meta.phases.resolve.typeProofs
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.impl.PackageFragmentDescriptorImpl
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.scopes.MemberScope

class ProofsPackageFragmentDescriptor(val module: ModuleDescriptor, fqName: FqName) : PackageFragmentDescriptorImpl(module, fqName) {
  override fun getMemberScope(): MemberScope = scope

  private val scope by lazy {
    if (module.typeProofs.isEmpty()) MemberScope.Empty
    else module.typeProofs.chainedMemberScope()
  }
}