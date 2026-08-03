package expo.modules.memegetbg

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import expo.modules.kotlin.AppContext
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the way Expo actually instantiates a native view, which is not the way
 * any other test in this module instantiates one.
 *
 * Every other test calls the constructor directly with both arguments, and that
 * is exactly why they all passed while the shipped app crashed: Kotlin compiles
 * a defaulted parameter into (Context, MemeTextDensity) plus a synthetic bitmask
 * overload and NO plain (Context). Expo's ViewDefinitionBuilder.createViewFactory
 * reflects for (Context) or (Context, AppContext), finds neither, and red-boxes
 * "Didn't find a correct constructor for class ..." the first time a text layer
 * is added to the studio.
 *
 * So this asserts the reflective shape — the one property a direct call can
 * never exercise. MemeTextPreviewView is currently the only view registered in
 * MemegetBgModule; a second one should get the same guard.
 */
@RunWith(AndroidJUnit4::class)
class MemeTextPreviewViewConstructorTest {
  private val context: Context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  /** Resolves a constructor the same way Expo does, accepting either signature. */
  private fun resolveAsExpoWould(): java.lang.reflect.Constructor<MemeTextPreviewView> {
    val cls = MemeTextPreviewView::class.java
    return runCatching { cls.getDeclaredConstructor(Context::class.java) }
      .recoverCatching { cls.getDeclaredConstructor(Context::class.java, AppContext::class.java) }
      .getOrElse {
        val available = cls.declaredConstructors.joinToString("\n  ") { c ->
          c.parameterTypes.joinToString(", ") { it.simpleName }
        }
        throw AssertionError(
          "Expo resolves (Context) or (Context, AppContext); neither exists, so " +
            "adding a text layer red-boxes at runtime. Declared constructors:\n  $available"
        )
      }
  }

  @Test
  fun exposesAConstructorExpoCanResolve() {
    assertNotNull(resolveAsExpoWould())
  }

  @Test
  fun theResolvedConstructorProducesAUsableView() {
    val ctor = resolveAsExpoWould()
    ctor.isAccessible = true
    // Reflection hands back Object; the cast is the point — a resolvable ctor
    // that produced something unusable would still break the studio.
    val view = ctor.newInstance(context)
    // The density default must actually have been applied, not left null: this
    // measures through the same path the real view uses to lay text out.
    view.measure(0, 0)
    assertTrue("view built via Expo's path should measure", view.measuredWidth >= 0)
  }

  @Test
  fun stillSupportsTheExplicitDensityConstructorOtherTestsUse() {
    // The fix must be additive. If @JvmOverloads were ever swapped for a plain
    // secondary constructor that dropped the density parameter, the layout and
    // parity suites would start silently testing a different object.
    val view = MemeTextPreviewView(context, MemeTextDensity(2f))
    assertNotNull(view)
  }
}
