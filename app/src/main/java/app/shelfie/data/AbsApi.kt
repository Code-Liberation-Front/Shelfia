package app.shelfie.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query

interface AbsApi {

    @GET("status")
    suspend fun status(): ServerStatus

    @retrofit2.http.POST("login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @GET("auth/openid/callback")
    suspend fun oidcCallback(
        @Query("code") code: String,
        @Query("state") state: String,
        @Query("code_verifier") codeVerifier: String,
        @retrofit2.http.Header("Cookie") cookies: String? = null,
    ): LoginResponse

    @GET("api/me")
    suspend fun me(): User

    @GET("api/libraries")
    suspend fun libraries(): LibrariesResponse

    @GET("api/libraries/{id}/items")
    suspend fun libraryItems(
        @Path("id") libraryId: String,
        @Query("limit") limit: Int = 500,
        @Query("sort") sort: String = "media.metadata.title",
    ): LibraryItemsResponse

    @GET("api/libraries/{id}/recent-episodes")
    suspend fun recentEpisodes(
        @Path("id") libraryId: String,
        @Query("limit") limit: Int = 50,
    ): RecentEpisodesResponse

    @GET("api/items/{id}")
    suspend fun item(
        @Path("id") itemId: String,
        @Query("expanded") expanded: Int = 1,
    ): LibraryItemExpanded

    @GET("api/me/listening-stats")
    suspend fun listeningStats(): ListeningStats

    @PATCH("api/me/progress/{itemId}/{episodeId}")
    suspend fun updateEpisodeProgress(
        @Path("itemId") itemId: String,
        @Path("episodeId") episodeId: String,
        @Body body: ProgressUpdate,
    )
}
