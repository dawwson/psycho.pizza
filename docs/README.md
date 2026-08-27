# 프로젝트 문서

이 디렉터리는 코드만으로 파악하기 어려운 시스템 흐름, 서비스 간 계약과 중요한 기술 결정의 이유를 기록합니다.

## 문서 지도

| 궁금한 점 | 문서 |
| --- | --- |
| Pizza–Pickle 분석 파이프라인은 어떻게 동작하는가? | [분석 파이프라인](analysis-pipeline/) |
| 분석 요청의 상태와 전이 조건은 무엇인가? | [분석 요청 lifecycle](analysis-pipeline/lifecycle.md) |
| Pizza와 Pickle이 어떤 메시지를 교환하는가? | [Pizza–Pickle 메시지 계약](analysis-pipeline/message-contract.md) |
| 오류, 재시도와 중복 메시지를 어떻게 처리하는가? | [분석 실패 처리 정책](analysis-pipeline/failure-policy.md) |
| 이 저장소의 개발 작업은 어떤 절차로 수행하는가? | [Engineering Workflow v0.1](workflow/engineering-workflow-v0.1.md) |
| 저장소의 테스트는 어떤 기준으로 작성하는가? | [테스트 작성 원칙](conventions/testing.md) |
| 테스트, Git 운영과 AWS naming 등 공통 개발 컨벤션에는 무엇이 있는가? | [개발 컨벤션](conventions/) |
| 왜 현재 설계를 선택했는가? | [Architecture Decision Records](adr/) |

## 작성 원칙

- 한국어를 기본으로 하고 코드 식별자와 기술 용어는 원문을 유지합니다.
- 코드에서 바로 확인할 수 있는 목록보다 책임, 경계, 계약, 제약과 결정 이유를 기록합니다.
- 현재 구현, 확정된 정책과 아직 구현되지 않은 목표를 명시적으로 구분합니다.
- 한 문서는 하나의 중심 질문에 답하고 같은 규칙을 여러 문서에 중복 정의하지 않습니다.
- 처리 흐름은 필요한 경우 Mermaid로, 상태별 처리 결과는 표로 표현합니다.
- 구현되지 않은 세부 알고리즘과 설정값은 담당 PR의 코드와 테스트를 작성할 때 결정합니다.
- 최종 수정일과 변경 이력은 문서에 반복하지 않고 Git 이력으로 확인합니다.

## 문서 책임과 동기화

- `docs/README.md`는 프로젝트 문서 지도와 공통 작성 원칙을 관리합니다.
- `analysis-pipeline/`은 분석 lifecycle, 메시지 계약과 실패 처리 정책을 관리합니다.
- `conventions/`은 테스트, Git 운영과 AWS naming처럼 반복 적용하는 개발 기준을 관리합니다.
- `adr/`은 대안이 존재하고 장기적인 영향을 주는 기술 결정과 선택 이유를 관리합니다.
- `workflow/`은 Codebase Review부터 release까지 사람이 승인하는 개발 작업 절차를 관리합니다.

코드와 문서가 다르면 현재 구현을 확인해 차이를 명시하고 관련 변경에서 함께 갱신합니다. 분석 상태, 메시지 계약, 실패·재시도·멱등성 동작의 구체적인 동기화 대상은 [분석 파이프라인 문서](analysis-pipeline/)를 따릅니다.

저장소 작업 시 반드시 적용할 규칙은 [AGENTS.md](../AGENTS.md)를 따릅니다.
