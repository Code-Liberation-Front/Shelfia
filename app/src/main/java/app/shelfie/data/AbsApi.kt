package app.shelfie.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query

interface AbsApi {

    @retrofit2.http.POST("login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

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

    @GET("api/items/{id}")
    suspend fun item(
        @Path("id") itemId: String,
        @Query("expanded") expanded: Int = 1,
    ): LibraryItemExpanded

    @PATCH("api/me/progress/{itemId}/{episodeId}")
    suspend fun updateEpisodeProgress(
        @Path("itemId") itemId: String,
        @Path("episodeId") episodeId: String,
        @Body body: ProgressUpdate,
    )
}
