# Git 브랜치·릴리스·커밋 운영 가이드

이 문서는 Pizza 저장소에서 브랜치를 만들고 pull request(PR)를 병합하며 릴리스와 긴급 수정 사항을 운영하는 기준을 정의합니다. GitHub 저장소 설정과 실제 CI/CD 권한은 이 정책을 강제하도록 별도로 구성해야 합니다.

## 브랜치와 책임

| 브랜치 | 책임 | 생성 기준 | 종료 기준 |
| --- | --- | --- | --- |
| `main` | 운영 배포와 릴리스의 기준 | 상시 유지 | 삭제하지 않음 |
| `develop` | 다음 릴리스 후보의 통합 | 상시 유지 | 삭제하지 않음 |
| `feature/*` | 새로운 기능 개발 | 최신 `develop`에서 분기 | `develop` PR 병합 후 삭제 |
| `fix/*` | 출시 전 또는 일반 버그 수정 | 최신 `develop`에서 분기 | `develop` PR 병합 후 삭제 |
| `docs/*` | 문서만 변경 | 기본적으로 최신 `develop`에서 분기 | 대상 브랜치 PR 병합 후 삭제 |
| `hotfix/*` | 현재 운영 버전의 긴급 수정 | 최신 `main`에서 분기 | `main` 반영 및 `develop` 역병합 후 삭제 |

`main`과 `develop`에는 직접 push하지 않고 PR과 필수 CI를 거쳐 병합합니다. 하나의 작업 브랜치는 하나의 이슈 또는 하나의 논리적 변경만 다룹니다.

### 브랜치 이름

작업 브랜치는 `<type>/<issue-number>-<short-description>` 형식을 사용합니다.

- `type`은 `feature`, `fix`, `hotfix`, `docs` 중 하나를 사용합니다.
- `issue-number`는 GitHub issue 번호를 사용합니다.
- `short-description`은 소문자 영문과 숫자를 `-`로 연결한 짧은 설명으로 작성합니다.
- 이슈가 없는 사전 조사처럼 번호를 붙일 수 없는 작업은 `<type>/<short-description>`을 허용합니다.

```text
feature/42-analysis-history-api
fix/51-invalid-display-name
hotfix/63-login-token-expiry
docs/7-git-workflow
```

## 작업과 병합 절차

일반 기능, 수정과 문서 작업은 다음 순서로 진행합니다.

1. 최신 `develop`에서 작업 브랜치를 생성합니다.
2. 변경과 회귀 테스트를 함께 작성하고 저장소의 필수 검증을 실행합니다.
3. `develop`을 대상으로 PR을 만들고 관련 이슈, 변경 이유, 검증 결과와 배포 주의 사항을 기록합니다.
4. 리뷰 승인과 CI 통과 후 **Squash and merge**합니다.
5. 원격과 로컬 작업 브랜치를 정리합니다.

문서가 이미 배포된 버전의 잘못된 운영 절차를 긴급히 바로잡는 등 `main`에 직접 반영해야 하는 경우에는 `hotfix/*` 절차를 따릅니다.

### 대상별 merge 방식

| 출발 브랜치 | 대상 브랜치 | merge 방식 | 이유 |
| --- | --- | --- | --- |
| `feature/*`, `fix/*`, `docs/*` | `develop` | Squash and merge | 작업 단위로 통합 이력을 간결하게 유지 |
| `develop` | `main` | Create a merge commit | 릴리스 경계와 포함 commit을 보존 |
| `hotfix/*` | `main` | Create a merge commit | 긴급 수정 경계를 운영 이력에 보존 |
| `main` | `develop` | Create a merge commit | hotfix 등 `main`에만 반영된 변경을 다음 개발선에 동기화 |

정규 릴리스의 `develop → main` merge 결과는 다시 `develop`에 역병합하지 않습니다. 릴리스 merge commit에는 코드 변경이 새로 추가되지 않으며 다음 개발은 기존 `develop`에서 계속합니다. hotfix처럼 `main`에만 생긴 변경은 `main → develop` 역병합 PR로 동기화합니다. 충돌은 별도 임시 브랜치에서 해결하고 역병합 PR로 검증합니다.

## 커밋 메시지

커밋 메시지는 Conventional Commits를 바탕으로 다음 형식을 사용합니다.

```text
<type>: <summary>
```

| type | 사용 범위 |
| --- | --- |
| `feat` | 새로운 기능 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변경 없는 구조 개선 |
| `test` | 테스트 추가 또는 수정 |
| `docs` | 문서 변경 |
| `chore` | 설정, 의존성 및 기타 유지보수 |
| `build` | 빌드 시스템 또는 빌드 의존성 변경 |
| `ci` | CI/CD 변경 |
| `hotfix` | 운영 긴급 수정 |

`summary`는 변경 결과를 명확히 드러내는 한국어 또는 영어 명사형 문장으로 작성하며 마침표를 붙이지 않습니다. 서로 다른 목적의 변경은 별도 commit으로 나눕니다. 본문이 필요하면 제목 다음 빈 줄을 두고 변경 이유, 제약과 영향을 기록합니다. 호환성을 깨는 변경은 본문 footer에 `BREAKING CHANGE: <description>`을 추가합니다.

```text
feat: 분석 요청 목록 조회 API 추가
fix: 비활성 계정 display name 조회 오류 수정
docs: 릴리스 전략 문서화
```

## 릴리스와 버전

릴리스 버전은 [Semantic Versioning](https://semver.org/)의 `MAJOR.MINOR.PATCH`를 따르고 Git tag에는 `v` 접두사를 붙입니다.

| 변경 | 증가 기준 | 예시 |
| --- | --- | --- |
| PATCH | 하위 호환되는 버그 수정 | `v1.0.1` → `v1.0.2` |
| MINOR | 하위 호환되는 기능 추가 | `v1.0.2` → `v1.1.0` |
| MAJOR | 기존 API, 메시지 또는 동작과 호환되지 않는 변경 | `v1.1.0` → `v2.0.0` |

여러 변경이 포함되면 가장 큰 영향의 증가 기준을 적용합니다. 아직 안정 버전 이전인 `0.y.z`에서도 호환성을 깨는 변경은 릴리스 노트에 명시하고 팀이 합의한 다음 minor 버전으로 올립니다. 이미 게시한 버전과 tag는 재사용하거나 다른 commit으로 이동하지 않습니다.

### 정규 릴리스

1. `develop`의 CI와 릴리스 범위별 추가 검증을 완료합니다.
2. 변경 목록을 검토해 다음 SemVer를 결정합니다.
3. `develop`에서 `main`으로 PR을 만들고 **Create a merge commit**으로 병합합니다.
4. 해당 merge commit에 annotated tag `vMAJOR.MINOR.PATCH`를 생성해 push합니다.
5. 같은 tag로 GitHub Release를 만들고 주요 변경, 수정 사항, 호환성 또는 migration 주의 사항을 기록합니다.

`main` push는 현재 CI/CD에서 운영 배포를 시작하므로 릴리스 PR에는 배포 영향과 필요한 환경 또는 DB 변경 순서를 미리 기록합니다. tag와 GitHub Release는 어떤 commit이 배포된 릴리스인지 식별하며, 배포 자체의 성공 여부는 CI/CD 결과로 확인합니다.

## Hotfix

운영 장애, 보안 문제 또는 즉시 수정해야 하는 심각한 회귀에만 `hotfix/*`를 사용합니다.

1. 최신 `main`에서 `hotfix/<issue-number>-<short-description>`을 생성합니다.
2. 문제 해결에 필요한 최소 변경과 회귀 테스트를 작성합니다.
3. `main` 대상 PR에서 영향, 검증 결과와 배포 주의 사항을 확인합니다.
4. 승인과 CI 통과 후 **Create a merge commit**으로 병합합니다.
5. 병합된 `main` commit에 새 PATCH tag를 만들고 GitHub Release에 긴급 수정 내용을 기록합니다. 호환성을 깨는 긴급 변경이라면 실제 영향에 맞춰 MINOR 또는 MAJOR를 선택합니다.
6. `main`에서 `develop`으로 역병합 PR을 만들고 **Create a merge commit**으로 병합합니다.
7. 두 브랜치 반영과 운영 확인이 끝나면 hotfix 브랜치를 삭제합니다.

## 미확정 운영 정책

운영 rollback 절차는 아직 확정되지 않았습니다. 배포 artifact 복구 방식, Git 변경 취소 방식, 버전과 tag 처리 및 DB 복구 기준은 운영 환경과 책임 주체를 정한 뒤 별도 문서에서 정의합니다. 정책이 확정되기 전까지 이 문서는 특정 rollback 절차를 규정하지 않습니다.
