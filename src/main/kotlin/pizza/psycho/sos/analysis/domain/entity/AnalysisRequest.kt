package pizza.psycho.sos.analysis.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import pizza.psycho.sos.analysis.domain.exception.AnalysisErrorCode
import pizza.psycho.sos.analysis.domain.vo.AnalysisRequestStatus
import pizza.psycho.sos.analysis.domain.vo.AnalysisTargetType
import pizza.psycho.sos.common.entity.BaseEntity
import pizza.psycho.sos.common.handler.DomainException
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "analysis_request")
class AnalysisRequest(
    @Column(name = "workspace_id", nullable = false, updatable = false)
    val workspaceId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 50, updatable = false)
    val targetType: AnalysisTargetType,
    @Column(name = "target_id", nullable = false, updatable = false)
    val targetId: UUID,
    @Column(name = "requested_by", nullable = true, updatable = false)
    val requestedBy: UUID? = null,
) : BaseEntity() {
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: AnalysisRequestStatus = AnalysisRequestStatus.QUEUED
        protected set

    @Column(name = "started_at", nullable = true)
    var startedAt: Instant? = null
        protected set

    @Column(name = "completed_at", nullable = true)
    var completedAt: Instant? = null
        protected set

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null
        protected set

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0
        protected set

    @Column(name = "last_attempt_at", nullable = true)
    var lastAttemptAt: Instant? = null
        protected set

    @Column(name = "next_retry_at", nullable = true)
    var nextRetryAt: Instant? = null
        protected set

    // QUEUED 상태의 request queue 발행 시도를 기록한다.
    fun recordDispatchAttempt(attemptedAt: Instant) {
        if (status != AnalysisRequestStatus.QUEUED) {
            throw DomainException(
                AnalysisErrorCode.INVALID_ANALYSIS_STATE,
                "분석 요청 상태가 QUEUED일 때만 발행 시도를 기록할 수 있습니다. (현재 상태=$status)",
            )
        }
        attemptCount += 1
        lastAttemptAt = attemptedAt
        nextRetryAt = null
    }

    // QUEUED 상태의 다음 발행 가능 시각을 기록한다.
    fun scheduleDispatchRetry(
        retryAt: Instant,
        reason: String,
    ) {
        if (status != AnalysisRequestStatus.QUEUED) {
            throw DomainException(
                AnalysisErrorCode.INVALID_ANALYSIS_STATE,
                "분석 요청 상태가 QUEUED일 때만 재시도를 예약할 수 있습니다. (현재 상태=$status)",
            )
        }
        nextRetryAt = retryAt
        errorMessage = reason
    }

    // QUEUED -> RUNNING
    fun markAsRunning(startedAt: Instant) {
        if (status != AnalysisRequestStatus.QUEUED) {
            throw DomainException(
                AnalysisErrorCode.INVALID_ANALYSIS_STATE,
                "분석 요청 상태가 QUEUED일 때만 RUNNING으로 변경할 수 있습니다. (현재 상태=$status)",
            )
        }
        status = AnalysisRequestStatus.RUNNING
        this.startedAt = startedAt
        completedAt = null
        errorMessage = null
        nextRetryAt = null
    }

    // RUNNING -> DONE
    fun markAsCompleted(completedAt: Instant) {
        if (status != AnalysisRequestStatus.RUNNING) {
            throw DomainException(
                AnalysisErrorCode.INVALID_ANALYSIS_STATE,
                "분석 요청 상태가 RUNNING일 때만 DONE으로 변경할 수 있습니다. (현재 상태=$status)",
            )
        }
        status = AnalysisRequestStatus.DONE
        this.completedAt = completedAt
    }

    // QUEUED / RUNNING -> FAILED
    fun markAsFailed(
        reason: String,
        failedAt: Instant,
    ) {
        if (status != AnalysisRequestStatus.QUEUED && status != AnalysisRequestStatus.RUNNING) {
            throw DomainException(
                AnalysisErrorCode.INVALID_ANALYSIS_STATE,
                "분석 요청 상태가 QUEUED 또는 RUNNING일 때만 FAILED로 변경할 수 있습니다. (현재 상태=$status)",
            )
        }
        status = AnalysisRequestStatus.FAILED
        completedAt = failedAt
        errorMessage = reason
        nextRetryAt = null
    }

    // RUNNING -> QUEUED
    fun rescheduleStaleRequest(retryAt: Instant) {
        if (status != AnalysisRequestStatus.RUNNING) {
            throw DomainException(
                AnalysisErrorCode.INVALID_ANALYSIS_STATE,
                "분석 요청 상태가 RUNNING일 때만 QUEUED로 변경할 수 있습니다. (현재 상태=$status)",
            )
        }
        status = AnalysisRequestStatus.QUEUED
        startedAt = null
        nextRetryAt = retryAt
    }

    companion object {
        fun create(
            workspaceId: UUID,
            sprintId: UUID,
            memberId: UUID,
        ): AnalysisRequest =
            AnalysisRequest(
                workspaceId = workspaceId,
                targetType = AnalysisTargetType.SPRINT,
                targetId = sprintId,
                requestedBy = memberId,
            )
    }
}
