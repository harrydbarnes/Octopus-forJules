package com.jules.loader.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.jules.loader.BuildConfig
import com.jules.loader.data.api.JulesService
import com.jules.loader.data.model.ActivityLog
import com.jules.loader.data.model.CreateSessionRequest
import com.jules.loader.data.model.CreateActivityRequest
import com.jules.loader.data.model.GithubRepoContext
import com.jules.loader.data.model.ListActivitiesResponse
import com.jules.loader.data.model.ListSessionsResponse
import com.jules.loader.data.model.ListSourcesResponse
import com.jules.loader.data.model.MessageLog
import com.jules.loader.data.model.Session
import com.jules.loader.data.model.SourceContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File

class JulesRepository private constructor(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        try {
            createEncryptedSharedPreferences()
        } catch (e: Exception) {
            // Handle corrupted or incompatible file (e.g., plain text file exists)
            // Delete the old file and recreate
            deleteSharedPreferences()
            createEncryptedSharedPreferences()
        }
    }

    private fun createEncryptedSharedPreferences(): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS_FILE_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun deleteSharedPreferences() {
        try {
            // Clear data if possible first
            context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()

            // Delete the file
            val file = File(context.filesDir.parent, "shared_prefs/$PREFS_FILE_NAME.xml")
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE })
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://jules.googleapis.com/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val service = retrofit.create(JulesService::class.java)

    private var cachedSessions: List<Session>? = null
    private var cachedSources: ListSourcesResponse? = null
    private var lastSourcesFetchTime: Long = 0

    companion object {
        private const val PREFS_FILE_NAME = "jules_prefs"
        private const val KEY_API_KEY = "jules_api_key"
        const val API_KEY_LENGTH = 53

        @Volatile
        private var INSTANCE: JulesRepository? = null

        fun getInstance(context: Context): JulesRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: JulesRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun saveApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
        cachedSessions = null
    }

    fun getApiKey(): String? {
        return prefs.getString(KEY_API_KEY, null)
    }

    private fun requireApiKey(): String {
        return getApiKey() ?: throw IllegalStateException("API Key not found")
    }

    fun hasCachedSessions(): Boolean = cachedSessions != null

    suspend fun getSessions(pageToken: String? = null, forceRefresh: Boolean = false): ListSessionsResponse {
        // If requesting the first page without force refresh and we have cache, return it?
        // But cachedSessions is just a List<Session>, it doesn't store nextPageToken.
        // So simple caching strategy might need to be adjusted or disabled for pagination.
        // For simplicity, let's bypass cache if pageToken is provided, or just update cache on first page load.

        val apiKey = requireApiKey()
        val response = service.listSessions(apiKey, pageToken = pageToken)

        if (pageToken == null) {
             cachedSessions = response.sessions
        }

        return response
    }

    suspend fun createSession(
        prompt: String,
        repoUrl: String? = null,
        branch: String? = null,
        automationMode: String? = null,
        requirePlanApproval: Boolean? = null
    ): Session {
        val apiKey = requireApiKey()
        val sourceContext = if (repoUrl != null) {
            SourceContext(
                source = repoUrl,
                githubRepoContext = GithubRepoContext(startingBranch = branch, branches = null, defaultBranch = null)
            )
        } else null
        val request = CreateSessionRequest(
            prompt = prompt,
            sourceContext = sourceContext,
            automationMode = automationMode,
            requirePlanApproval = requirePlanApproval
        )
        return service.createSession(apiKey, request)
    }

    suspend fun getActivities(sessionId: String, pageToken: String? = null): ListActivitiesResponse {
        val apiKey = requireApiKey()
        return service.listActivities(apiKey, sessionId, pageToken = pageToken)
    }

    suspend fun createActivity(sessionId: String, message: String): ActivityLog {
        val apiKey = requireApiKey()
        val userMessage = MessageLog(message = message, text = null, prompt = null)
        val request = CreateActivityRequest(userMessage = userMessage)
        return service.createActivity(apiKey, sessionId, request)
    }

    suspend fun deleteSession(sessionId: String) {
        val apiKey = requireApiKey()
        val response = service.deleteSession(apiKey, sessionId)
        if (!response.isSuccessful) {
            throw HttpException(response)
        }
        cachedSessions = cachedSessions?.filter { it.id != sessionId }
    }

    suspend fun cancelSession(sessionId: String): Session {
        val apiKey = requireApiKey()
        return service.cancelSession(apiKey, sessionId)
    }

    suspend fun getSession(sessionId: String): Session {
        val apiKey = requireApiKey()
        return service.getSession(apiKey, sessionId)
    }

    fun hasValidSourceCache(): Boolean {
        return cachedSources != null && (System.currentTimeMillis() - lastSourcesFetchTime) < 5000
    }

    suspend fun getSources(
        filter: String? = null,
        pageSize: Int = 100,
        pageToken: String? = null
    ): ListSourcesResponse {
        // Simple cache behavior: only cache the first unfiltered page
        if (filter == null && pageToken == null && hasValidSourceCache()) {
            return cachedSources!!
        }

        val apiKey = requireApiKey()
        val response = service.listSources(apiKey, filter, pageSize, pageToken)

        if (filter == null && pageToken == null) {
            cachedSources = response
            lastSourcesFetchTime = System.currentTimeMillis()
        }
        return response
    }

    private fun ensurePrefix(value: String, prefix: String): String {
        return if (value.startsWith(prefix)) value else "$prefix$value"
    }

    suspend fun getActivity(activityName: String): ActivityLog {
        val apiKey = requireApiKey()
        val name = ensurePrefix(activityName, "sessions/")
        return service.getActivity(apiKey, name)
    }

    suspend fun getSource(sourceName: String): SourceContext {
        val apiKey = requireApiKey()
        val name = ensurePrefix(sourceName, "sources/")
        return service.getSource(apiKey, name)
    }

    suspend fun validateApiKey(apiKey: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                service.listSources(apiKey)
                true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // For debugging
                Log.e("API_VALIDATION", "API key validation failed", e)
                false
            }
        }
    }
}
