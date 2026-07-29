package io.github.iamjosephmj.bridge.signals

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SqliteTransitionStoreTest {

    private fun store() = SqliteTransitionStore(
        ApplicationProvider.getApplicationContext(), dbName = "test-signals.db")

    @Test fun `append all count oldest deleteOldest`() {
        val s = store()
        assertEquals(0, s.count())
        assertNull(s.oldestAt())
        s.append(10L, "a"); s.append(20L, "b"); s.append(30L, "c")
        assertEquals(3, s.count())
        assertEquals(10L, s.oldestAt())
        assertEquals(listOf(10L to "a", 20L to "b", 30L to "c"), s.all())
        s.deleteOldest(2)
        assertEquals(listOf(30L to "c"), s.all())
        assertEquals(30L, s.oldestAt())
    }
}
