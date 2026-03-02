package com.jules.loader.data.api

import com.jules.loader.data.model.*
import retrofit2.http.*

interface JulesService {
    @GET("v1alpha/sessions")
    suspend fun listSessions(
        @Header("X-Goog-Api-Key") apiKey: String,
        @Query("pageSize") pageSize: Int = 20,
        @Query("pageToken") pageToken: String? = null
    ): ListSessionsResponse

    @POST("v1alpha/sessions")
    suspend fun createSession(
        @Header("X-Goog-Api-Key") apiKey: String,
        @Body request: CreateSessionRequest
    ): Session

    @GET("v1alpha/sessions/{sessionId}/activities")
    suspend fun listActivities(
        @Header("X-Goog-Api-Key") apiKey: String,
        @Path("sessionId") sessionId: String,
        @Query("pageSize") pageSize: Int = 20,
        @Query("pageToken") pageToken: String? = null
    ): ListActivitiesResponse

    @POST("v1alpha/sessions/{sessionId}/activities")
    suspend fun createActivity(
        @Header("X-Goog-Api-Key") apiKey: String,
        @Path("sessionId") sessionId: String,
        @Body request: CreateActivityRequest
    ): ActivityLog

    @DELETE("v1alpha/sessions/{sessionId}")
    suspend fun deleteSession(
        @Header("X-Goog-Api-Key") apiKey: String,
        @Path("sessionId") sessionId: String
    ): retrofit2.Response<Unit>

    @POST("v1alpha/sessions/{sessionId}:cancel")
    suspend fun cancelSession(
        @Header("X-Goog-Api-Key") apiKey: String,
        @Path("sessionId") sessionId: String
    ): Session

    @GET("v1alpha/sessions/{sessionId}")
    suspend fun getSession(
        @Header("X-Goog-Api-Key") apiKey: String,
        @Path("sessionId") sessionId: String
    ): Session

    @GET("v1alpha/sources")
    suspend fun listSources(
        @Header("X-Goog-Api-Key") apiKey: String,
        @Query("filter") filter: String? = null,
        @Query("pageSize") pageSize: Int = 20,
        @Query("pageToken") pageToken: String? = null
    ): ListSourcesResponse
    @GET("v1alpha/{name}")
    suspend fun getSource(
        @Header("X-Goog-Api-Key") apiKey: String,
        @Path("name", encoded = true) name: String
    ): SourceContext

    @GET("v1alpha/{name}")
    suspend fun getActivity(
        @Header("X-Goog-Api-Key") apiKey: String,
        @Path("name", encoded = true) name: String
    ): ActivityLog
}
