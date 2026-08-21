# Repository Guidelines

## Scope

이 저장소는 Pizza 백엔드입니다. 인증, 워크스페이스, 프로젝트, 스프린트, 태스크와 분석 요청 lifecycle을 소유합니다.

- Box는 Pizza API를 사용하는 클라이언트입니다. 명시적인 요청 없이 Box를 수정하거나 기존 Box–Pizza API 스펙을 변경하지 않습니다.
- Pickle은 AI 분석 worker입니다. Pizza–Pickle 메시지 계약을 변경할 때 양쪽 저장소의 DTO, 소비 코드와 테스트를 함께 확인합니다.
- 요청받은 변경과 무관한 리팩터링을 섞지 않고 기존 사용자 변경을 보존합니다.
- 사용자가 명시적으로 요청하기 전에는 commit, push 또는 pull request를 생성하지 않습니다.

## Project Structure

- `src/main/kotlin/pizza/psycho/sos/identity/`: 인증, 계정과 보안
- `src/main/kotlin/pizza/psycho/sos/workspace/`: 워크스페이스와 멤버십
- `src/main/kotlin/pizza/psycho/sos/project/`: 프로젝트, 스프린트와 태스크
- `src/main/kotlin/pizza/psycho/sos/analysis/`: 분석 요청, 메트릭, 리포트와 SQS 연동
- `src/main/kotlin/pizza/psycho/sos/audit/`: 감사 이벤트와 이력
- `src/main/kotlin/pizza/psycho/sos/common/`: 공통 설정, 응답, 예외와 메시지 기능
- `src/main/resources/db/migration/`: Flyway migration
- `src/main/resources/db/seed/`: 개발 및 평가용 seed 자료
- `src/test/`: 단위, 통합 및 Testcontainers 테스트

기존 기능 영역의 `presentation`, `application`, `domain`, `infrastructure` 경계를 따릅니다. Domain이 infrastructure에 의존하게 만들지 말고 외부 연동은 port 또는 infrastructure 구현으로 격리합니다.

## Verification

변경 범위에 맞는 최소 검증을 실행합니다.

| 변경 | 필수 검증 |
| --- | --- |
| Kotlin 코드 | `./gradlew ktlintCheck`와 `./gradlew test` |
| `integration` 태그가 붙은 테스트 | `./gradlew integrationTest` |
| PostgreSQL query, mapping 또는 migration | `./gradlew testTc` |
| 빌드 또는 배포 설정 | `./gradlew bootJar`와 관련 script 검증 |
| 문서만 변경 | 링크, Mermaid, 예제 명령과 `git diff --check` 확인 |

기본 `test` task는 `integration`, `tc` 태그를 제외합니다. 현재 PostgreSQL Testcontainers 테스트는 `tc`, Spring context 통합 테스트는 `integration` 태그를 사용하며 각각 대응하는 Gradle task에서 실행합니다.

코드 동작을 변경할 때 같은 변경에 회귀 테스트를 포함합니다. 테스트를 통과시키기 위해 의미 있는 검증을 삭제하거나 지나치게 넓은 mock으로 대체하지 않습니다.

## Testing Conventions

- 테스트를 새로 작성하거나 기존 테스트를 수정하기 전에 `docs/conventions/testing.md`를 확인하고 해당 기준을 따릅니다.
- 테스트 계층, 기술 스택, fixture, mock, assertion, 테스트 이름과 주석 작성 기준은 `docs/conventions/testing.md`를 정본으로 사용합니다.
- 새 분석 테스트는 JUnit 5, MockK와 AssertJ를 기본으로 사용합니다.
- 기존 테스트의 기술 스택은 요청받은 변경과 무관하게 일괄 변환하지 않습니다.
- 기능별 테스트 시나리오와 정책은 해당 기능 문서에서 관리하고 공통 작성 기준을 기능 문서에 중복 정의하지 않습니다.

## Database Changes

- 적용된 Flyway migration을 수정하지 않고 새 migration 파일을 추가합니다.
- 운영 환경은 `ddl-auto=none`이므로 schema 변경을 entity 수정만으로 처리하지 않습니다.
- 새 column의 nullable 여부, 기존 행 backfill, index와 constraint 적용 비용을 검토합니다.
- 배포 실패와 rollback 가능성을 고려해 schema와 application code를 호환 가능한 순서로 변경합니다.
- destructive migration은 명시적인 승인과 복구 계획 없이 수행하지 않습니다.

## Configuration and Secrets

- 환경변수 목록은 `.env.template`, Spring configuration과 배포 script 사이에서 일치하도록 유지합니다. 어느 한 파일만 완전한 정본이라고 가정하지 않습니다.
- `.env`, AWS credential, JWT secret, 메일 비밀번호와 OpenAI API key를 커밋하거나 로그에 출력하지 않습니다.
- 실제 AWS 또는 OpenAI 호출이 필요한 테스트는 기본 단위 테스트에서 격리합니다.
- 환경변수를 추가하거나 이름을 바꾸면 `.env.template`, Spring configuration과 배포 script를 함께 검토합니다.

## AWS Conventions

- AWS 리소스 또는 배포 설정을 추가하거나 변경하기 전에 `docs/conventions/aws-naming-convention.md`를 확인합니다.
- AWS 리소스 이름과 공통 태그 규칙은 `docs/conventions/aws-naming-convention.md`를 정본으로 사용합니다.
- 기존 운영 리소스의 이름을 변경해야 하는 경우 문서 규칙만 적용하지 말고 배포 영향과 migration 계획을 함께 검토합니다.

## Analysis Pipeline Documentation

Pizza와 Pickle에 걸친 AI 분석 파이프라인 문서는 다음 구조로 관리합니다.

```text
docs/
├── README.md
├── analysis-pipeline/
│   ├── README.md
│   ├── lifecycle.md
│   ├── message-contract.md
│   ├── failure-policy.md
│   ├── testing.md
│   └── operations.md
└── adr/
    └── NNNN-short-title.md
```

### Document Responsibilities

- `docs/README.md`: 프로젝트 문서 지도와 공통 작성 원칙
- `analysis-pipeline/README.md`: 전체 처리 흐름, 시스템 책임 경계와 세부 문서 링크
- `analysis-pipeline/lifecycle.md`: 분석 요청 상태 의미, 허용·금지 전이와 전이 주체
- `analysis-pipeline/message-contract.md`: 요청·성공·실패 메시지 schema와 호환성 규칙
- `analysis-pipeline/failure-policy.md`: 오류 분류, 재시도, 중복·순서 역전 메시지, `stale job`과 DLQ 처리
- `analysis-pipeline/testing.md`: 정책별 자동화 테스트와 통합·장애·부하 테스트 범위
- `analysis-pipeline/operations.md`: 장애 확인, 복구, 재처리와 관측 절차
- `adr/`: 대안이 존재하고 장기적인 영향을 주는 기술 결정과 선택 이유

### Writing Rules

- 문서는 한국어를 기본으로 하고 클래스명, 필드명과 상태명 같은 코드 식별자는 원문을 유지합니다.
- 코드에서 바로 확인할 수 있는 목록보다 책임, 경계, 계약, 제약과 결정 이유를 기록합니다.
- 현재 구현, 확정된 정책과 아직 구현되지 않은 목표를 명시적으로 구분합니다.
- 한 문서는 하나의 중심 질문에 답하며 같은 규칙을 여러 문서에 중복 정의하지 않습니다.
- 처리 흐름은 필요한 경우 Mermaid로, 상태별 처리 결과는 표로 표현합니다.
- 메시지 계약에는 필드 타입, 필수 여부, 의미와 대표 JSON 예제를 포함합니다.
- 정상 경로뿐 아니라 중복 전달, 순서 역전, 알 수 없는 요청과 잘못된 payload를 다룹니다.
- 최종 수정일과 변경 이력은 문서에 반복하지 않고 Git 이력으로 확인합니다.

### Documentation Synchronization

- 분석 상태 또는 전이 조건을 변경하면 `lifecycle.md`와 관련 테스트를 함께 검토합니다.
- SQS DTO 또는 직렬화 형식을 변경하면 `message-contract.md`와 Pizza·Pickle의 관련 DTO, 소비 코드와 테스트를 함께 검토합니다.
- 재시도, 복구, 멱등성 또는 DLQ 동작을 변경하면 `failure-policy.md`, `testing.md`, `operations.md`를 함께 검토합니다.
- 문서와 코드가 다르면 현재 구현을 확인해 차이를 명시합니다. 코드 변경 없이 문서만으로 차이를 숨기지 않습니다.

## Analysis Pipeline Design Constraints

다음 항목은 분석 파이프라인 고도화 작업의 설계 제약입니다. 현재 구현이 이미 모두 보장한다고 가정하지 않습니다.

- Pizza DB를 분석 요청 lifecycle의 기준 상태로 사용합니다.
- SQS의 at-least-once 전달을 전제로 소비 처리를 멱등하게 설계합니다.
- Pizza의 프로세스 내부 메모리 큐는 DB 기반 Dispatcher로 교체합니다.
- 부하 테스트에는 fake LLM을 사용하고 실제 OpenAI API는 소규모 품질 평가에만 사용합니다.
- 대규모 아키텍처 개편보다 분석 파이프라인에 필요한 범위의 변경을 우선합니다.

## ADR Criteria

다음처럼 여러 대안 중 선택했고 후속 구현에 장기적인 영향을 주는 경우에만 ADR을 추가합니다.

- 작업 원장과 source of truth 선택
- 메시지 전달 보장 및 데이터 일관성 전략
- 모듈 또는 서비스 간 책임과 의존 방향 변경
- 호환성, 보안 또는 운영에 장기적인 영향을 주는 결정

단순 PR 요약, 현재 메시지 필드 목록이나 재시도 횟수 같은 조정 가능한 정책값은 ADR로 작성하지 않습니다.

## Commit Boundaries

- 하나의 commit에는 하나의 논리적 변경을 담습니다.
- 동작 변경과 그 동작을 검증하는 테스트는 같은 commit에 포함합니다.
- 순차 배포가 필요한 migration, application code와 cleanup은 안전한 적용 순서로 분리합니다.
- formatting만 바뀐 파일을 기능 변경 commit에 불필요하게 섞지 않습니다.
- commit 전에 변경 파일과 diff를 확인하고 관련 검증 결과를 기록합니다.
