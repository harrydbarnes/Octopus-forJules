package com.jules.loader.data.model

import com.google.gson.annotations.SerializedName
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

data class ListSessionsResponse(
    val sessions: List<Session>?,
    val nextPageToken: String?
)

@Parcelize
data class Session(
    val name: String,
    val id: String,
    val title: String?,
    val prompt: String?,
    @SerializedName("state") val status: String?,
    val sourceContext: SourceContext?,
    val createTime: String?,
    val updateTime: String?,
    val outputs: List<SessionOutput>? = null
) : Parcelable

@Parcelize
data class SessionOutput(
    val pullRequest: PullRequest?
) : Parcelable

@Parcelize
data class PullRequest(
    val url: String,
    val title: String,
    val description: String
) : Parcelable

@Parcelize
data class SourceContext(
    @SerializedName(value = "source", alternate = ["name"]) val source: String,
    @SerializedName("githubRepo") val githubRepoContext: GithubRepoContext?
) : Parcelable {
    val cleanSource: String
        get() = if (source.startsWith("sources/github/")) source.removePrefix("sources/github/") else source
}

@Parcelize
data class GithubRepoContext(
    val startingBranch: String?,
    val branches: List<Branch>?,
    val defaultBranch: Branch?
) : Parcelable

@Parcelize
data class Branch(
    val displayName: String
) : Parcelable

// Request body for creating a new session/task
data class CreateSessionRequest(
    val prompt: String,
    val sourceContext: SourceContext? = null,
    val automationMode: String? = null,
    val requirePlanApproval: Boolean? = null,
    val title: String? = null
)

// Request body for creating a new activity (user message)
data class CreateActivityRequest(
    val userMessage: MessageLog
)

// Response models for live logs (activities)
data class ListActivitiesResponse(
    val activities: List<ActivityLog>?,
    val nextPageToken: String?
)

data class ActivityLog(
    val name: String?,
    val id: String?,
    val description: String?,
    @SerializedName("createTime") val timestamp: String?,
    val type: String?,
    val originator: String?,

    // Union fields containing the replies/comments
    @SerializedName("userMessaged") val userMessage: MessageLog?,
    @SerializedName("agentMessaged") val agentMessage: MessageLog?,
    val planGenerated: PlanGeneratedLog?,
    val planApproved: PlanApprovedLog?,
    val progressUpdated: ProgressUpdatedLog?,
    val sessionCompleted: SessionCompletedLog?,
    val sessionFailed: SessionFailedLog?,
    val artifacts: List<Artifact>?
) {
    fun getResolvedType(): String {
        return type ?: when {
            userMessage != null -> "USER MESSAGE"
            agentMessage != null -> "AGENT MESSAGE"
            planGenerated != null -> "PLAN GENERATED"
            planApproved != null -> "PLAN APPROVED"
            progressUpdated != null -> "PROGRESS UPDATED"
            sessionCompleted != null -> "SESSION COMPLETED"
            sessionFailed != null -> "SESSION FAILED"
            else -> "ACTIVITY"
        }
    }

    fun getResolvedDescription(): String {
        // Fallbacks to extract the actual comment details and content
        return userMessage?.message ?: userMessage?.text ?: userMessage?.prompt ?:
               agentMessage?.message ?: agentMessage?.text ?: agentMessage?.prompt ?:
               planGenerated?.plan?.steps?.joinToString("\n") { it.title } ?: planGenerated?.message ?:
               planApproved?.planId?.let { "Plan Approved: $it" } ?:
               progressUpdated?.description ?: progressUpdated?.title ?:
               sessionCompleted?.let { "Session Completed" } ?:
               sessionFailed?.reason ?:
               description ?: "No details"
    }
}

data class MessageLog(
    val message: String?,
    val text: String?,
    val prompt: String?
)

data class PlanGeneratedLog(
    val plan: PlanDetail?,
    val message: String?
)

data class PlanDetail(
    val id: String?,
    val steps: List<PlanStep>?
)

data class PlanStep(
    val id: String?,
    val title: String,
    val index: Int?
)

data class PlanApprovedLog(
    val planId: String?
)

data class ProgressUpdatedLog(
    val title: String?,
    val description: String?
)

class SessionCompletedLog

data class Artifact(
    val bashOutput: BashOutput?,
    val changeSet: ChangeSet?,
    val media: Media?
)

data class BashOutput(
    val command: String?,
    val output: String?,
    val exitCode: Int?
)

data class ChangeSet(
    val source: String?,
    val gitPatch: GitPatch?
)

data class GitPatch(
    val unidiffPatch: String?,
    val baseCommitId: String?,
    val suggestedCommitMessage: String?
)

data class Media(
    val data: String?,
    val mimeType: String?
)

// Response models for user sources (repositories)
data class ListSourcesResponse(
    val sources: List<SourceContext>?,
    val nextPageToken: String?
)

data class SessionFailedLog(
    val reason: String?
)
