package com.shreddro.core.model

/** User-controlled sync configuration. Providers may be linked concurrently. */
data class SyncState(
    val linkedProviders: Set<CloudProvider>,
    val localModeForced: Boolean = false,
    val online: Boolean = true,
) {
    /** Cloud targets that should actually receive data right now. */
    val activeCloudTargets: Set<CloudProvider>
        get() = if (localModeForced || !online) emptySet()
        else linkedProviders - CloudProvider.LOCAL_CSV
}

sealed interface SyncOutcome {
    val provider: CloudProvider

    data class Success(override val provider: CloudProvider, val detail: String = "") : SyncOutcome
    data class Failure(override val provider: CloudProvider, val error: Throwable) : SyncOutcome
    data class Deferred(override val provider: CloudProvider) : SyncOutcome
}

/** Result of recording one slip across the local sink + all active cloud targets. */
data class RecordResult(
    val local: SyncOutcome,
    val cloud: List<SyncOutcome>,
) {
    /** Purge is allowed only once the row is durably persisted locally. */
    val safeToPurgeOriginal: Boolean get() = local is SyncOutcome.Success
    val pendingRetry: List<CloudProvider>
        get() = cloud.filter { it !is SyncOutcome.Success }.map { it.provider }
}
