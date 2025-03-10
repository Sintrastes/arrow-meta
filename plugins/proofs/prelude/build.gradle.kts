plugins {
  alias(libs.plugins.arrowGradleConfig.multiplatform)
  alias(libs.plugins.arrowGradleConfig.publishMultiplatform)
}

version = property("projects.meta_version").toString()

kotlin {
  explicitApi = null

  sourceSets {
    commonMain {
      dependencies {
        implementation(libs.kotlin.stdlibCommon)
      }
    }

    named("jvmMain") {
      dependencies {
        implementation(libs.kotlin.stdlibJDK8)
      }
    }

    named("jsMain") {
      dependencies {
        implementation(libs.kotlin.stdlibJS)
      }
    }
  }
}
