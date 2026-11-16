package app.productlens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LiveApiTest {
    @Test fun officialApiV3KnownProductAndUnknownGtin() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LensStore(context, "live-${UUID.randomUUID()}.db")
        try {
            val source = OpenFactsSource(context, store)
            val known = source.lookup("3017620422003")
            android.util.Log.i("LensTest", "LIVE known=$known")
            assertNotNull(known.note, known.product)
            assertTrue(known.product!!.name.isNotBlank())
            kotlinx.coroutines.delay(5100)
            val missing = source.lookup("9999999999994")
            android.util.Log.i("LensTest", "LIVE missing=$missing")
            assertNull(missing.product)
            assertTrue(missing.note, missing.note.contains("등록") || missing.note.contains("찾지 못"))
        } finally { val name = store.databaseName; store.close(); context.deleteDatabase(name) }
    }
}
