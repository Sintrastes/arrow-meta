plugins {
  alias(libs.plugins.arrowGradleConfig.jvm)
  alias(libs.plugins.arrowGradleConfig.publishJvm)
}

version = property("projects.meta_version").toString()

tasks.processResources {
  filesMatching("**/plugin.properties") {
    filter { it.replace("%compilerPluginVersion%", "$version") }
    filter { it.replace("%kotlinVersion%", libs.versions.kotlin.get()) }
    filter { it.replace("%arrowVersion%", libs.versions.arrow.get()) }
  }
}

dependencies {
  compileOnly(gradleApi())
  compileOnly(libs.kotlin.stdlibJDK8)
  api(libs.kotlin.gradlePluginApi)
  compileOnly(libs.kotlin.gradlePluginX)
  compileOnly(libs.kotlin.reflect)
  compileOnly(libs.classgraph)
}
