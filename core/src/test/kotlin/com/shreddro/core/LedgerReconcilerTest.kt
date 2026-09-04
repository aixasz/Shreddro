package com.shreddro.core

import com.shreddro.core.ledger.CloudLedger
import com.shreddro.core.ledger.LedgerReconciler
import com.shreddro.core.ledger.LedgerRecord
import com.shreddro.core.model.CloudProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerReconcilerTest {

    private fun record(ref: String, bank: String = "KBank", amount: Double = 100.0, image: String = "") =
        LedgerRecord(
            loggedAtUtc = "2026-09-03T08:00:00Z",
            bankName = bank,
            dateTime = "2026-09-03 10:00",
            amount = amount,
            sender = "A",
            receiver = "B",
            referenceId = ref,
            imageFile = image,
        )

    private class FakeCloud(
        override val provider: CloudProvider,
        val keys: Set<String>?,
        val failOn: Set<String> = emptySet(),
        val listingThrows: Boolean = false,
    ) : CloudLedger {
        val appended = mutableListOf<LedgerRecord>()
        override suspend fun existingKeys(): Set<String>? {
            if (listingThrows) error("cannot list")
            return keys
        }
        override suspend fun append(record: LedgerRecord) {
            if (record.key in failOn) error("http 500")
            appended += record
        }
    }

    @Test
    fun `appends only records whose key is missing from each cloud`() = runTest {
        val local = listOf(record("R1"), record("R2"), record("R3"))
        val google = FakeCloud(CloudProvider.GOOGLE, setOf("R1"))
        val microsoft = FakeCloud(CloudProvider.MICROSOFT, setOf("R1", "R2", "R3"))

        val report = LedgerReconciler().reconcile(local, listOf(google, microsoft))

        assertEquals(listOf("R2", "R3"), google.appended.map { it.referenceId })
        assertEquals(emptyList(), microsoft.appended)
        assertEquals(mapOf(CloudProvider.GOOGLE to 2, CloudProvider.MICROSOFT to 0), report.appended)
        assertEquals(mapOf(CloudProvider.GOOGLE to 0, CloudProvider.MICROSOFT to 0), report.failed)
    }

    @Test
    fun `null existing keys appends nothing`() = runTest {
        val cloud = FakeCloud(CloudProvider.GOOGLE, keys = null)
        val report = LedgerReconciler().reconcile(listOf(record("R1"), record("R2")), listOf(cloud))
        assertEquals(emptyList(), cloud.appended)
        assertEquals(mapOf(CloudProvider.GOOGLE to 0), report.appended)
        assertEquals(mapOf(CloudProvider.GOOGLE to 0), report.failed)
    }

    @Test
    fun `per-record failures are counted and do not stop the rest`() = runTest {
        val local = listOf(record("R1"), record("R2"), record("R3"))
        val google = FakeCloud(CloudProvider.GOOGLE, emptySet(), failOn = setOf("R2"))
        val microsoft = FakeCloud(CloudProvider.MICROSOFT, emptySet())

        val report = LedgerReconciler().reconcile(local, listOf(google, microsoft))

        assertEquals(listOf("R1", "R3"), google.appended.map { it.referenceId })
        assertEquals(3, microsoft.appended.size)
        assertEquals(mapOf(CloudProvider.GOOGLE to 2, CloudProvider.MICROSOFT to 3), report.appended)
        assertEquals(mapOf(CloudProvider.GOOGLE to 1, CloudProvider.MICROSOFT to 0), report.failed)
    }

    @Test
    fun `listing failure counts every record as failed for that provider only`() = runTest {
        val local = listOf(record("R1"), record("R2"))
        val broken = FakeCloud(CloudProvider.GOOGLE, emptySet(), listingThrows = true)
        val fine = FakeCloud(CloudProvider.MICROSOFT, emptySet())

        val report = LedgerReconciler().reconcile(local, listOf(broken, fine))

        assertEquals(emptyList(), broken.appended)
        assertEquals(2, fine.appended.size)
        assertEquals(mapOf(CloudProvider.GOOGLE to 0, CloudProvider.MICROSOFT to 2), report.appended)
        assertEquals(mapOf(CloudProvider.GOOGLE to 2, CloudProvider.MICROSOFT to 0), report.failed)
    }

    @Test
    fun `local duplicates are appended once`() = runTest {
        val local = listOf(
            record("R1"),
            record(" R1 "), // same key after trim
            record("", "SCB", 42.0, "a.jpg"),
            record("", "SCB", 42.0, "a.jpg"),
            record("", "SCB", 42.0, "b.jpg"), // different image -> different key
        )
        val cloud = FakeCloud(CloudProvider.GOOGLE, emptySet())

        val report = LedgerReconciler().reconcile(local, listOf(cloud))

        assertEquals(listOf("R1", "SCB|2026-09-03 10:00|42.00|a.jpg", "SCB|2026-09-03 10:00|42.00|b.jpg"), cloud.appended.map { it.key })
        assertEquals(mapOf(CloudProvider.GOOGLE to 3), report.appended)
    }

    @Test
    fun `local list is never modified`() = runTest {
        val local = listOf(record("R1"), record("R1"))
        val snapshot = local.toList()
        LedgerReconciler().reconcile(local, listOf(FakeCloud(CloudProvider.GOOGLE, emptySet())))
        assertEquals(snapshot, local)
    }
}
