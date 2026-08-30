# Engineering Workflow v0.2

## 1. 목적

이 Workflow는 1인 개발 환경에서 사람과 AI가 협업할 때 불필요한 탐색, 출력, 승인 반복과 검증 비용을 줄이면서 포트폴리오 및 2~3년차 백엔드 개발자 수준의 코드 품질을 유지하는 것을 목표로 한다.

핵심 원칙은 다음과 같다.

- 작업은 **Goal 중심**으로 진행한다.
- 현재 Goal과 직접 관련된 범위부터 탐색한다.
- Finding은 발견한 문제를 모두 나열하지 않고 정제한다.
- 하나의 Sub-issue는 하나의 PR을 기본 단위로 한다.
- 확인된 범위 내 작업은 별도 확인 없이 진행한다.
- 중요한 범위·기술 결정은 확인 후 변경하고 merge는 사람이 수행한다.
- 문서, 코드와 테스트는 필요한 범위만 확인한다.
- 세부 Git, 테스트와 기술 규칙은 기존 정본 문서를 따른다.

---

# 2. Workflow

```text
Goal
 ↓
Scoped Review
 ↓
Finding 정제
 ↓
Issue Planning
 ├─ Parent Issue 분해
 └─ Sub-issue 분해
 ↓
Branch
 ↓
Implementation
 ↓
Targeted Verification
 ↓
PR / CI
 ↓
Merge
```

기본 작업 구조는 다음과 같다.

```text
Goal
 └─ Parent Issue
      └─ Sub-issue
           └─ Branch
                └─ PR
```

하나의 Goal은 1개 이상의 Parent Issue를 가진다.

하나의 Parent Issue는 1개 이상의 Sub-issue를 가진다.

하나의 Sub-issue는 하나의 PR로 완료하는 것을 기본으로 한다.

단, migration 선행 적용이나 cross-repository contract 변경처럼 여러 PR이 필요한 경우 Sub-issue를 추가로 분리한다.

---

# 3. Goal

## Input

- 사용자가 달성하려는 목표
- 현재 프로젝트의 시간·비용·기술적 제약

## 진행

- Goal을 기준으로 조사 범위와 필요한 작업을 판단한다.
- Goal과 직접 관련 없는 개선을 작업 범위에 포함하지 않는다.

## 확인

- Goal과 주요 범위를 확인한다.

## Output

- 작업 판단의 기준으로 사용할 명확한 Goal

---

# 4. Scoped Review

저장소 전체를 선제적으로 조사하지 않는다.

현재 Goal, Parent Issue 또는 Sub-issue에서 시작하여 필요한 범위만 탐색한다.

## 탐색 순서

```text
Goal / Parent Issue / Sub-issue
      ↓
Entry Point
      ↓
직접 관련 코드
      ↓
직접 의존 코드 / 테스트
      ↓
필요할 때만 범위 확장
```

## Rules

- Sub-issue의 entry point부터 탐색한다.
- 직접 호출하거나 의존하는 코드와 테스트를 우선 확인한다.
- 현재 근거만으로 판단할 수 없을 때 탐색 범위를 한 단계씩 확장한다.
- repository-wide scan은 필요성이 확인된 경우에만 수행한다.
- Git history는 현재 코드만으로 의도나 원인을 판단할 수 없을 때 확인한다.
- 이미 확인한 파일을 이유 없이 반복해서 읽지 않는다.
- 관련 없는 문제를 발견해도 현재 Goal을 막지 않으면 탐색을 확장하지 않는다.

---

# 5. Finding Policy

## Finding 인정 기준

다음 중 하나에 의미 있게 해당하는 문제를 Finding 후보로 본다.

### Goal Quality

현재 Goal의 완성도에 영향을 주는 문제.

예:

- correctness
- data integrity
- transaction
- reliability
- security
- performance
- failure handling
- regression risk

### Portfolio / Engineering Quality

2~3년차 백엔드 개발자라면 설명하거나 판단할 수 있어야 하며 포트폴리오의 기술적 완성도에 의미 있는 문제.

예:

- 책임과 의존성
- transaction 설계
- DB 설계
- 장애 및 재시도 처리
- 테스트 전략
- 운영 관점
- 외부 시스템 연동

## 제외

다음은 기본적으로 Finding으로 만들지 않는다.

- 단순 코드 스타일
- naming 취향
- 현재 Goal과 관계없는 cleanup
- 나중에 해도 되는 단순 refactoring
- 현재 규모에 필요하지 않은 고급 아키텍처
- 기술적으로 흥미롭지만 현재 프로젝트 가치가 낮은 개선

## 정제

- 중복되거나 동일한 원인의 Finding은 통합한다.
- 하나의 원인에서 발생한 여러 증상을 불필요하게 각각 Finding으로 만들지 않는다.
- Goal과 관련성이 낮은 Finding은 기본 출력하지 않는다.

정제 후에도 비차단 Finding이 **5개를 초과하면 전체를 나열하지 않는다.**

다시 통합·정제한 뒤 핵심 Finding을 제시하고 나머지는 범주와 생략 이유를 요약한다.

다음 critical Finding은 개수 제한과 관계없이 보고한다.

- 현재 Goal의 correctness 또는 safety를 차단하는 문제
- 보안 침해 또는 데이터 손실 가능성
- 복구하기 어려운 변경 가능성
- 외부 API 또는 message contract의 의도하지 않은 파손 가능성
- production 장애로 직접 이어질 가능성이 높은 문제

critical Finding이 현재 Goal 밖에 있더라도 임의로 수정하지 않고 영향과 판단이 필요한 이유를 보고한다.

## CI / Test Failure

- 현재 PR의 변경으로 발생한 CI 또는 test failure는 별도 Finding으로 만들지 않는다.
- 현재 PR의 defect로 간주하고 같은 작업 범위에서 해결한다.
- 기존부터 존재하는 unrelated failure가 현재 Goal 또는 필수 검증을 차단하는 경우에만 blocker로 보고한다.

---

# 6. Finding → Issue

모든 Finding을 Issue로 만들지 않는다.

정제된 Finding은 범위와 관계에 따라 Parent Issue 또는 Sub-issue에 반영한다.

Finding이 다음 조건을 만족하면 Issue 반영 후보가 된다.

- 현재 Goal과 직접 관련된다.
- 독립적인 변경 목적으로 설명할 수 있다.
- 별도 작업 단위로 관리할 가치가 있다.

다음은 별도 Issue로 만들지 않는다.

- 현재 Sub-issue에서 바로 해결 가능한 작은 문제
- 다른 Finding의 단순 증상
- 포트폴리오 및 엔지니어링 가치가 낮은 개선
- 현재 시점에 해결할 필요가 없는 개선

---

# 7. Issue Planning

Goal을 1개 이상의 Parent Issue로 분해한다.

각 Parent Issue는 Goal을 구성하는 독립적인 문제 영역 또는 작업 축을 나타낸다.

## Parent Issue

Parent Issue에는 최소한 다음을 명확히 한다.

- 목적
- 배경과 해결할 문제
- 포함 범위
- 제외 범위
- 완료 조건
- Sub-issue 목록
- 필요한 경우 Sub-issue 간 의존 순서

Parent Issue는 직접적인 PR 단위가 아니다.

연결된 Sub-issue가 완료되고 Parent Issue의 완료 조건을 충족했을 때 종료한다.

## Sub-issue

Parent Issue를 1개 이상의 검토 가능한 Sub-issue로 분해한다.

기본 관계는 다음과 같다.

> **1 Sub-issue ≈ 1 PR**

각 Sub-issue에는 최소한 다음을 명확히 한다.

- 목적
- 포함 범위
- 제외 범위
- 완료 조건
- 주요 검증 방법
- 필요한 경우 다른 Sub-issue와의 의존 관계

PR 내부의 commit 분해, Commit Plan과 구현 순서는 별도 확인 없이 정한다.

## 중요한 기술 결정

다음과 같은 결정은 변경 전에 확인한다.

- DB schema 변경
- 새로운 infrastructure 도입
- 새로운 주요 library 도입
- architecture boundary 변경
- 외부 API/message contract 변경
- destructive operation 또는 복구하기 어려운 변경

작업 중 Goal, Parent Issue, Sub-issue 범위 또는 중요한 기술 방향을 변경해야 하면 이유와 영향을 설명하고 확인받은 뒤 계속한다.

---

# 8. GitHub / Git 권한

아래 작업은 Workflow에서 요구하는 확인이 완료된 범위에서 자동 수행할 수 있다.

- Goal을 Parent Issue로 분해
- Parent Issue 초안 작성 및 확인된 Parent Issue 생성
- Parent Issue를 Sub-issue로 분해
- Sub-issue 초안 작성 및 확인된 Sub-issue 생성
- 확인된 Sub-issue의 작업 branch 생성
- 코드 수정
- 테스트 및 검증
- commit message 초안
- PR title/body 초안
- 전체 diff Self Review

현재 v0.2에서는 다음 작업을 사람이 직접 수행한다.

- staging
- commit
- push
- PR 생성
- merge
- tag
- release

향후 Workflow 사용 결과에 따라 추가 자동화를 검토한다.

---

# 9. Branch / Commit / PR Convention

branch, commit, merge와 release의 세부 규칙은 [Git 브랜치·릴리스·커밋 운영 가이드](../conventions/git-workflow.md)를 정본으로 따른다.

---

# 10. Verification Strategy

검증은 작업 진행에 따라 단계적으로 확대하며, 같은 코드 상태에서 이미 통과한 비용이 큰 검증을 이유 없이 반복하지 않는다.

## Implementation

구현 중에는 현재 변경과 직접 관련된 targeted test를 우선 실행한다.

- Commit Plan으로 작업을 나눴다면 현재 구현한 commit 범위만 검증한다.
- 후속 commit에서 구현할 동작이나 아직 변경하지 않은 계층까지 검증 범위를 넓히지 않는다.
- 영향받는 테스트를 특정하기 어렵거나 CI 실패를 재현할 때만 전체 테스트를 실행한다.

## PR 준비 전

Sub-issue에서 PR에 포함할 변경이 모두 끝나면 변경과 직접 관련된 로컬 검증을 수행한다.

| 변경 | 로컬 검증 |
| --- | --- |
| Kotlin 코드 | 관련 targeted test, `./gradlew ktlintCheck` |
| `integration` 태그 테스트 | `integrationTest`의 관련 targeted test |
| PostgreSQL query, mapping 또는 migration | `testTc`의 관련 targeted test |
| 빌드 또는 배포 설정 | `./gradlew bootJar`와 관련 script 검증 |
| 문서만 변경 | 링크, Mermaid, 예제 명령과 `git diff --check` |

코드 동작을 변경할 때 필요한 회귀 테스트가 없으면 추가하거나 수정한다.

전체 테스트는 CI에서 수행한다.

## 재검증

다음 경우에만 이미 수행한 검증을 다시 실행한다.

- 코드 변경으로 기존 검증 결과가 무효화된 경우
- 새로운 failure 또는 regression 가능성이 확인된 경우
- 추가 검증이 필요한 변경이 발생한 경우

필요한 로컬 또는 CI 검증을 실행하지 못하면 다음을 기록한다.

- 실행하지 못한 검증
- 사유
- 영향
- 재현 명령

---

# 11. PR / CI

Sub-issue 구현과 관련 로컬 검증이 완료되면 전체 diff를 Self Review한다.

확인 대상:

- Sub-issue 목적과 실제 diff의 일치
- 완료 조건 충족
- 범위 밖 변경
- regression risk
- 검증 결과와 남은 위험
- migration / contract / configuration 영향
- debug code, secret 또는 임시 파일

CI는 프로젝트 설정에 정의된 전체 검증을 수행한다.

취소, 미실행 또는 실패한 검증은 통과로 간주하지 않는다.

CI failure를 해결하기 위해 Sub-issue 범위나 중요한 기술 방향을 변경해야 하면 이유와 영향을 설명하고 확인받은 뒤 계속한다.

Merge 여부는 최종 diff, CI 결과와 남은 위험을 확인한 뒤 사람이 결정한다.

---

# 12. Documentation Loading Policy

문서를 미리 모두 읽지 않는다.

기본 context는 다음으로 제한한다.

```text
AGENTS.md
+
현재 Goal / Parent Issue / Sub-issue
```

추가 문서는 현재 판단에 필요할 때만 읽는다.

예:

```text
테스트 작성 또는 수정
→ testing.md

AWS 리소스 또는 배포 설정 변경
→ AWS convention

Analysis Pipeline 변경
→ 관련 analysis 문서

Git / branch / merge / release 작업
→ git-workflow.md
```

## Rules

- README와 `docs/` 전체를 선제적으로 읽지 않는다.
- 현재 작업과 관련된 문서만 선택적으로 읽는다.
- 같은 작업에서 이미 확인한 문서를 이유 없이 다시 읽지 않는다.
- reference 문서를 읽었다는 이유만으로 추가 reference 탐색을 연쇄적으로 수행하지 않는다.
