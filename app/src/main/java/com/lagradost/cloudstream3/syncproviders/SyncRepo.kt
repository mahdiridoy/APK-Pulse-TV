package com.lagradost.cloudstream3.syncproviders

import com.pulsestream.app.syncproviders.SyncAPI
import com.pulsestream.app.syncproviders.SyncRepo as RealSyncRepo

/**
 * Compatibility shim: external .cs3 plugins expect [SyncRepo] at
 * [com.lagradost.cloudstream3.syncproviders.SyncRepo] (the original
 * CloudStream3 package). This class delegates to the real implementation
 * at [com.pulsestream.app.syncproviders.SyncRepo].
 */
class SyncRepo(override val api: SyncAPI) : com.pulsestream.app.syncproviders.AuthRepo(api) {
    private val delegate = RealSyncRepo(api)

    val syncIdName = api.syncIdName

    var requireLibraryRefresh: Boolean
        get() = api.requireLibraryRefresh
        set(value) { api.requireLibraryRefresh = value }

    suspend fun updateStatus(id: String, newStatus: SyncAPI.AbstractSyncStatus): Result<Boolean> =
        delegate.updateStatus(id, newStatus)

    suspend fun status(id: String): Result<SyncAPI.AbstractSyncStatus?> =
        delegate.status(id)

    suspend fun load(id: String): Result<SyncAPI.SyncResult?> =
        delegate.load(id)

    suspend fun library(): Result<SyncAPI.LibraryMetadata?> =
        delegate.library()
}
