# Repository Guidelines

## Repository Scope

이 저장소는 Pizza 백엔드입니다. 인증, 워크스페이스, 프로젝트, 스프린트, 태스크와 분석 요청 lifecycle을 소유합니다.

- Box는 Pizza API를 사용하는 클라이언트입니다. 명시적인 요청 없이 Box를 수정하거나 기존 Box–Pizza API 계약을 변경하지 않습니다.
- Pickle은 AI 분석 worker입니다. Pizza–Pickle 메시지 계약을 변경할 때 양쪽 저장소의 DTO, 소비 코드와 테스트를 함께 확인합니다.
- 요청받은 변경과 무관한 refactoring을 섞지 않고 기존 사용자 변경을 보존합니다.
- 사용자가 명시적으로 요청하기 전에는 Git staging, commit, push, GitHub PR 생성, merge 또는 tag 생성을 수행하지 않습니다.

## Architecture and Project Structure

- `src/main/kotlin/pizza/psycho/sos/identity/`: 인증, 계정과 보안
- `src/main/kotlin/pizza/psycho/sos/workspace/`: 워크스페이스와 멤버십
- `src/main/kotlin/pizza/psycho/sos/project/`: 프로젝트, 스프린트와 태스크
- `src/main/kotlin/pizza/psycho/sos/analysis/`: 분석 요청, 메트릭, 리포트와 SQS 연동
- `src/main/kotlin/pizza/psycho/sos/audit/`: 감사 이벤트와 이력
- `src/main/kotlin/pizza/psycho/sos/common/`: 공통 설정, 응답, 예외와 메시지 기능
- `src/main/resources/db/migration/`: Flyway migration
- `src/main/resources/db/seed/`: 개발 및 평가용 seed 자료
- `src/test/`: 단위, 통합 및 Testcontainers 테스트

기존 기능 영역의 `presentation`, `application`, `domain`, `infrastructure` 경계를 따릅니다. Domain이 infrastructure에 의존하지 않게 하고 외부 연동은 port 또는 infrastructure 구현으로 격리합니다.

## Engineering Workflow

Codebase Review부터 Finding, Issue, PR·commit 설계, 구현, 검토, CI, merge와 release까지의 절차는 [Engineering Workflow v0.1](docs/workflow/engineering-workflow-v0.1.md)을 따릅니다.

- `Finding → Triage → Issue → PR → Commit` 순서로 작업을 구체화합니다.
- AI는 승인된 commit 범위만 변경하고 범위 밖 문제는 별도 Finding으로 보고합니다.
- 작업 branch의 commit은 사람의 단계별 검토 단위입니다.
- `feature/*`, `fix/*`, `docs/*`에서 `develop`로 병합할 때는 Squash and merge합니다.
- staging, commit, PR 생성, merge와 tag 생성은 사람이 직접 수행합니다.
- Workflow와 이 파일의 저장소별 규칙이 충돌하면 더 구체적인 이 파일의 규칙을 우선 적용하고 차이를 보고합니다.

## Verification

변경 범위에 맞는 최소 검증을 실행합니다.

| 변경 | 필수 검증 |
| --- | --- |
| Kotlin 코드 | `./gradlew ktlintCheck`와 `./gradlew test` |
| `integration` 태그가 붙은 테스트 | `./gradlew integrationTest` |
| PostgreSQL query, mapping 또는 migration | `./gradlew testTc` |
| 빌드 또는 배포 설정 | `./gradlew bootJar`와 관련 script 검증 |
| 문서만 변경 | 링크, Mermaid, 예제 명령과 `git diff --check` 확인 |

기본 `test` task는 `integration`, `tc` 태그를 제외합니다. PostgreSQL Testcontainers 테스트는 `tc`, Spring context 통합 테스트는 `integration` 태그를 사용하며 각각 대응하는 Gradle task에서 실행합니다.

코드 동작을 변경할 때 같은 변경에 회귀 테스트를 포함합니다. 테스트를 통과시키기 위해 의미 있는 검증을 삭제하거나 지나치게 넓은 mock으로 대체하지 않습니다.

## Change-Specific Rules

### Testing

테스트를 새로 작성하거나 수정하기 전에 [테스트 작성 원칙](docs/conventions/testing.md)을 확인합니다. 기존 테스트의 기술 스택을 관련 없는 변경에서 일괄 변환하지 않으며 기능별 테스트 시나리오는 해당 기능 문서에서 관리합니다.

### Database

- 적용된 Flyway migration을 수정하지 않고 새 migration 파일을 추가합니다.
- 운영 환경은 `ddl-auto=none`이므로 entity 변경만으로 schema를 변경하지 않습니다.
- nullable 여부, 기존 행 backfill, index와 constraint 적용 비용 및 순차 배포 호환성을 검토합니다.
- destructive migration은 명시적인 승인과 복구 계획 없이 수행하지 않습니다.

### Configuration and Secrets

- 환경변수를 추가하거나 변경하면 `.env.template`, Spring configuration과 배포 script를 함께 검토합니다.
- `.env`, AWS credential, JWT secret, 메일 비밀번호와 OpenAI API key를 저장소에 추가하거나 로그에 출력하지 않습니다.
- 실제 AWS 또는 OpenAI 호출이 필요한 테스트는 기본 단위 테스트에서 격리합니다.

### AWS

AWS 리소스 또는 배포 설정을 변경하기 전에 [AWS 리소스 네이밍 컨벤션](docs/conventions/aws-naming-convention.md)을 확인합니다. 기존 운영 리소스의 이름을 변경할 때는 배포 영향과 migration 계획을 함께 검토합니다.

### Analysis Pipeline

분석 파이프라인을 변경하기 전에 [분석 파이프라인 문서](docs/analysis-pipeline/README.md)와 관련 세부 문서를 확인합니다. 다음은 현재 구현이 모두 보장한다고 가정하지 않는 설계 제약입니다.

- Pizza DB를 분석 요청 lifecycle의 기준 상태로 사용합니다.
- SQS의 at-least-once 전달을 전제로 소비 처리를 멱등하게 설계합니다.
- Pizza의 process-local queue는 DB 기반 Dispatcher로 교체하는 방향을 유지합니다.
- 부하 테스트에는 fake LLM을 사용하고 실제 OpenAI API는 소규모 품질 평가에만 사용합니다.
- 대규모 아키텍처 개편보다 분석 파이프라인에 필요한 범위의 변경을 우선합니다.

분석 상태, 메시지 계약, 실패·재시도·멱등성 또는 DLQ 동작을 변경하면 관련 문서와 Pizza·Pickle의 코드 및 테스트를 함께 검토합니다.

## Commit Boundaries

- 하나의 commit에는 하나의 논리적 변경을 담습니다.
- 동작 변경과 그 동작을 검증하는 테스트는 같은 commit에 포함합니다.
- migration, application code와 cleanup은 안전한 적용 순서로 분리합니다.
- formatting만 바뀐 파일을 기능 변경 commit에 섞지 않습니다.
- commit 전에 변경 파일, diff와 관련 검증 결과를 사람이 확인합니다.

## Documentation

문서 구조, 책임과 작성 규칙은 [프로젝트 문서 지도](docs/README.md)를 따릅니다.

- 문서는 한국어를 기본으로 하고 코드 식별자와 기술 용어는 원문을 유지합니다.
- 현재 구현, 확정된 정책과 아직 구현되지 않은 목표를 구분합니다.
- 코드와 문서가 다르면 차이를 명시하고 관련 변경에서 함께 갱신합니다.
- 여러 대안 중 선택한 장기적인 Architecture Decision만 [ADR](docs/adr/README.md)로 기록합니다.

## Reference Documents

- [Engineering Workflow v0.1](docs/workflow/engineering-workflow-v0.1.md)
- [Git 브랜치·릴리스·커밋 운영 가이드](docs/conventions/git-workflow.md)
- [테스트 작성 원칙](docs/conventions/testing.md)
- [AWS 리소스 네이밍 컨벤션](docs/conventions/aws-naming-convention.md)
- [분석 파이프라인](docs/analysis-pipeline/README.md)
- [프로젝트 문서 지도](docs/README.md)
- [Architecture Decision Records](docs/adr/README.md)
