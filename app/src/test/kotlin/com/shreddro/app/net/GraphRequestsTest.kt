package com.shreddro.app.net

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for v0.7.0: the shared Json drops default values, and the
 * folder facet / conflict behaviour were defaults — Graph answered 400 to
 * every bank-folder create, so no Excel rows ever landed.
 */
class GraphRequestsTest {

    // Same configuration as Clients.json (private there).
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `folder create body carries the folder facet and fail-on-conflict`() {
        val body = json.encodeToString(GraphCreateFolderRequest.serializer(), GraphCreateFolderRequest("KBank"))
        assertTrue("\"folder\":{}" in body, body)
        assertTrue("\"@microsoft.graph.conflictBehavior\":\"fail\"" in body, body)
        assertTrue("\"name\":\"KBank\"" in body, body)
    }
}
