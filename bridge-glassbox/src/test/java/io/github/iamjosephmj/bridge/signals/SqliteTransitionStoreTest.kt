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

    @Test fun `replaceAll swaps the whole log in one shot`() {
        val s = store()
        s.append(10L, "a"); s.append(20L, "b"); s.append(30L, "c")
        s.replaceAll(listOf(5L to "x", 6L to "y"))
        assertEquals(listOf(5L to "x", 6L to "y"), s.all())
        assertEquals(2, s.count())
        assertEquals(5L, s.oldestAt())
        s.replaceAll(emptyList())
        assertEquals(0, s.count())
        assertNull(s.oldestAt())
    }
}
