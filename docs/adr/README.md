# Architecture Decision Records

ADR은 여러 대안 중 선택했고 후속 구현에 장기적인 영향을 주는 기술 결정과 그 이유를 기록합니다.

## 상태

- `Proposed`: 검토 중이며 아직 적용하기로 결정하지 않은 제안
- `Accepted`: 적용하기로 결정한 현재 기준
- `Superseded`: 이후 ADR로 대체된 결정

승인된 결정을 변경할 때 기존 ADR을 수정해 이력을 지우지 않고, 새 ADR에서 대체 관계를 연결합니다.

## 작성 기준

다음처럼 여러 대안 중 하나를 선택하고 후속 구현에 장기적인 영향을 주는 경우 ADR을 작성합니다.

- 작업 원장과 source of truth 선택
- 메시지 전달 보장과 데이터 일관성 전략
- 모듈 또는 서비스 간 책임과 의존 방향 변경
- 호환성, 보안 또는 운영에 장기적인 영향을 주는 결정

단순 PR 요약, 현재 메시지 필드 목록이나 재시도 횟수처럼 조정 가능한 정책값은 ADR로 작성하지 않습니다.

## 목록

- [0001. Pizza DB를 분석 lifecycle의 source of truth로 사용](0001-use-pizza-db-as-analysis-lifecycle-source.md) — Accepted
- [0002. 분석 메시지를 멱등하게 처리](0002-handle-analysis-messages-idempotently.md) — Accepted
- [0003. 분석 요청을 요청 단위 transaction으로 발행](0003-dispatch-analysis-requests-in-individual-transactions.md) — Accepted
