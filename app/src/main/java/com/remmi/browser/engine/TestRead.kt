import org.mozilla.geckoview.GeckoPreferenceController

class TestRead {
    fun test() {
        val clazz = GeckoPreferenceController.GeckoPreference::class.java
        clazz.methods.forEach { println(it.name) }
    }
}
