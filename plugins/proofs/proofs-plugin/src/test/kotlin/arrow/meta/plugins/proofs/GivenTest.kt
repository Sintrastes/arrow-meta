package arrow.meta.plugins.proofs

import arrow.meta.plugin.testing.Code
import arrow.meta.plugin.testing.CompilerTest
import arrow.meta.plugin.testing.assertThis
import arrow.meta.plugins.newMetaDependencies
import org.junit.jupiter.api.Test

class GivenTest {

  @Test
  fun `coherent polymorphic identity`() {
    givenTest(
      source =
        """
        @Given internal val x = "yes!"
        val result = given<String>()
      """,
      expected = "result" to "yes!"
    )
  }

  @Test
  fun `coherent polymorphic identity inference`() {
    givenTest(
      source =
        """
        @Given internal val x = "yes!"
        val result: String = given()
      """,
      expected = "result" to "yes!"
    )
  }

  @Test
  fun `use of imported interface`() {
    givenTest(
      source =
      """
        @Given 
        internal val intSemigroup: mod.Semigroup<Int> = object: mod.Semigroup<Int> {
            override operator fun Int.plus(other: Int): Int = this + other
        }
        
        fun <S> usingSemigroup(x: S, @Given semigroup: mod.Semigroup<S>): S = with(semigroup) {
            x + x
        }
        
        val result: Int = usingSemigroup<Int>(21)
      """,
      expected = "result" to 42
    )
  }

  @Test
  fun `coherent concrete identity`() {
    givenTest(
      source =
        """
        @Given internal val x = "yes!"
        fun id(@Given evidence: String): String =
          evidence
        val result = id()
      """,
      expected = "result" to "yes!"
    )
  }

  @Test
  fun `user explicit local override`() {
    givenTest(
      source =
        """
        @Given internal val x = "yes!"
        fun id(@Given evidence: String): String =
          evidence
        val result = id("nope!")
      """,
      expected = "result" to "nope!"
    )
  }

  @Test
  fun `value provider`() {
    givenTest(
      source =
        """
        class X(val value: String)
        @Given val x: X = X("yes!")
        val result = given<X>().value
      """,
      expected = "result" to "yes!"
    )
  }

  @Test
  fun `fun provider`() {
    givenTest(
      source =
        """
        class X(val value: String)
        @Given fun x(): X = X("yes!")
        val result = given<X>().value
      """,
      expected = "result" to "yes!"
    )
  }

  @Test
  fun `class provider`() {
    givenTest(
      source =
        """
        @Given class X {
          val value = "yes!"
        }
        val result = given<X>().value
      """,
      expected = "result" to "yes!"
    )
  }

  @Test
  fun `object provider`() {
    givenTest(
      source =
        """
        @Given object X {
          val value = "yes!"
        }
        val result = given<X>().value
      """,
      expected = "result" to "yes!"
    )
  }

  val prelude =
    """
    package test
    import arrow.Context
    import mod.Semigroup
    
    @Context
    @Retention(AnnotationRetention.RUNTIME)
    @Target(
      AnnotationTarget.CLASS,
      AnnotationTarget.FUNCTION,
      AnnotationTarget.PROPERTY,
      AnnotationTarget.VALUE_PARAMETER
    )
    @MustBeDocumented
    annotation class Given

    inline fun <A> given(@Given identity: A): A = identity
      
    //metadebug
  """.trimIndent()

  val externalModule =
    """
      package mod
         
      interface Semigroup<A> {
          operator fun A.plus(other: A): A
      }
    """.trimIndent()

  private fun givenTest(source: String, expected: Pair<String, Any?>) {
    val codeSnippet = """
       $prelude
       $source
      """
    assertThis(
      CompilerTest(
        config = { newMetaDependencies() },
        code = {
          sources(
              Code.Source(
                filename = "Mod.kt",
                text = externalModule
              ),
              Code.Source(
                filename = "Test.kt",
                text = codeSnippet
              )
          )
        },
        assert = {
          allOf(
            Code.Source(
              filename = "TestKt",
              text = expected.first
            ).evalsTo(expected.second)
          )
        }
      )
    )
  }
}
