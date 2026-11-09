package app.olauncher

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.olauncher.data.DeadlineStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeadlineStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = DeadlineStore(context)
    @Before fun setup() { clear() }
    @After fun cleanup() { clear() }
    private fun clear() { context.getSharedPreferences("deadlines", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun titleAndTimeSurviveEditingAndRecreation() {
        val first = store.save("  Submit paper  ", 1000)
        assertEquals("Submit paper", store.all().single().title)
        store.save("Submit revision", 2000, first.id)
        assertEquals(listOf(DeadlineStore.Deadline(first.id, "Submit revision", 2000)), DeadlineStore(context).all())
        store.remove(first.id)
        assertTrue(store.all().isEmpty())
    }

    @Test fun nextMeansNextUpcomingThenMostRecentPast() {
        store.save("Old", 100)
        val recent = store.save("Recent", 500)
        val next = store.save("Next", 1500)
        val later = store.save("Later", 3000)
        assertEquals(next, store.next(1000))
        assertEquals(later, store.next(2000))
        store.remove(later.id); store.remove(next.id)
        assertEquals(recent, store.next(2000))
    }

    @Test fun searchAndSortAreIndependentOfHomeSelection() {
        store.save("Zebra", 1000)
        store.save("alpha", 3000)
        store.save("Alpha revision", 2000)
        assertEquals(listOf("Zebra", "Alpha revision", "alpha"), store.find("", false).map { it.title })
        assertEquals(listOf("alpha", "Alpha revision", "Zebra"), store.find("", true).map { it.title })
        assertEquals(2, store.find(" ALPHA ").size)
        store.sortByName = true
        assertEquals("Zebra", store.next(0)?.title)
    }
}
