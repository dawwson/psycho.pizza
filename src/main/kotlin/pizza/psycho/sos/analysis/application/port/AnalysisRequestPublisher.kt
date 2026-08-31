package pizza.psycho.sos.analysis.application.port

import pizza.psycho.sos.analysis.application.service.dto.SprintAnalysisInput
import java.util.UUID

interface AnalysisRequestPublisher {
    fun publish(
        workspaceId: UUID,
        analysisRequestId: UUID,
        payload: SprintAnalysisInput,
    ): AnalysisRequestPublishResult
}

sealed interface AnalysisRequestPublishResult {
    data object Published : AnalysisRequestPublishResult

    sealed interface Failed : AnalysisRequestPublishResult {
        val message: String
        val cause: Throwable

        data class Retryable(
            override val message: String,
            override val cause: Throwable,
        ) : Failed

        data class Permanent(
            override val message: String,
            override val cause: Throwable,
        ) : Failed
    }
}
