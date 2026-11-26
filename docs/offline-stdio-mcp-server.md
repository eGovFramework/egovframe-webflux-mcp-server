# 폐쇄망에서 stdio MCP 서버 준비하기

`npx -y`(Node)나 `uvx`(Python) 같은 실행기는 필요한 패키지가 캐시나 로컬에 없으면 **실행 시점에
내려받으므로** 폐쇄망에서 실패한다. 캐시를 미리 채우고 오프라인 옵션으로 실행하는 방법도 있지만,
이 문서는 반입 파일과 실행 경로를 분명히 관리할 수 있도록 **실행기를 거치지 않고 미리 반입한 파일을
직접 가리키는** 방법을 설명한다.
`command` 를 `npx` 나 `uvx` 대신 `node`, `python`, 실행 바이너리로 바꾸고, `args` 첫 항목에 반입한
파일의 절대 경로를 준다.

아래는 `@modelcontextprotocol/server-filesystem` 예시이고, 다른 stdio 서버도 같은 방법이다.
패키지별 차이는 맨 아래 표에 정리했다.

## 1. 사전 점검: 순수 JS인가

네이티브 모듈이 섞여 있으면 **다른 OS 에서 만든 결과물을 옮겨 쓸 수 없다.**

아래 명령은 Git Bash 등 bash 셸 기준이다(`find`, `grep` 사용).

```bash
npm install --prefix C:/mcp-offline <패키지명>

# 네이티브 바이너리
find C:/mcp-offline/node_modules -name "*.node"

# 플랫폼 제약 선언. 경로 패턴을 node_modules/*/package.json 으로 적으면 스코프 패키지
# (@esbuild/win32-x64 등)와 중첩 node_modules 가 빠져 네이티브를 놓친다
grep -rl --include=package.json -E '"(os|cpu)"[[:space:]]*:[[:space:]]*\[' C:/mcp-offline/node_modules
```

출력이 있으면 npm 이 *현재 플랫폼용만* 설치하고 나머지는 설치하지 않으므로 **배포 대상 OS 에서
`npm install` 한다.** Windows 에서 만들어 Linux 로 옮기면 설치는 성공한 것처럼 보이고 실행 시점에 깨진다.

둘 다 출력이 없으면 다른 OS 에서 만들어 옮겨도 될 가능성이 높지만, 그것만으로 보장되지는 않는다.
JS 코드가 OS 별 외부 실행 파일이나 시스템 라이브러리를 호출할 수도 있기 때문이다. 마지막 판단은
맨 아래의 방법대로 **대상 OS 에서 네트워크를 끊고 실제로 실행해 보고** 내린다.

## 2. 진입점 확인

두 방식 모두 패키지의 실행 파일 경로가 필요하다(A 는 `args` 에, B 는 esbuild 입력으로).

```bash
node -p "JSON.stringify(require('C:/mcp-offline/node_modules/<패키지명>/package.json').bin)"
# {"mcp-server-filesystem":"dist/index.js"}  → dist/index.js
```

## 3. 반입: 방식 A(`node_modules` 전체) 또는 B(단일 파일)

인터넷이 되는 PC 에서 한 번 수행한 뒤 산출물을 옮긴다. `npm pack` 으로는 안 된다. 이
패키지가 `bundledDependencies` 를 선언하지 않아(1.0.0 에서도 그대로) tarball 에 의존성이
들어가지 않기 때문이다.

```bash
# 공통: 설치 (반입 산출물을 재현해야 하면 @<버전> 으로 고정)
npm install --prefix C:/mcp-offline @modelcontextprotocol/server-filesystem

# 방식 B 만: 단일 파일로 번들
npx esbuild C:/mcp-offline/node_modules/@modelcontextprotocol/server-filesystem/dist/index.js \
  --bundle --platform=node --format=esm --outfile=C:/mcp-offline/mcp-filesystem.mjs
```

A 는 `C:/mcp-offline/node_modules` 전체를, B 는 `mcp-filesystem.mjs` 한 개만 옮기고 `node` 로
직접 실행한다.

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "node",
      "args": [
        "C:/mcp-offline/node_modules/@modelcontextprotocol/server-filesystem/dist/index.js",
        "C:/path/to/docs"
      ]
    }
  }
}
```

> 방식 B 는 `args` 첫 항목만 `"C:/mcp-offline/mcp-filesystem.mjs"` 로 바꾸면 된다.
> `node_modules` 는 필요 없다.
> 어느 쪽이든 `npx` 를 거치지 않으므로 Windows 에서 `cmd /c` 로 감쌀 일도, `node_modules/.bin/`
> 아래의 실행 파일(OS 별로 `.cmd` 나 심볼릭 링크)을 신경 쓸 일도 없다.

## 4. 어느 쪽을 고를까

| 항목 | 방식 A (`node_modules`) | 방식 B (번들) |
|------|------------------------|--------------|
| 반입 크기 | 29MB / 4,168개 파일 (압축 약 4MB) | **1.2MB / 파일 1개** |
| 필요한 것 | `npm` | `npm` 과 `esbuild` |
| 원본 보존 | 패키지 트리 그대로 | 빌드 산출물로 변형 |
| 실패 위험 | 낮음 | 동적 `require` 나 `__dirname` 쓰는 패키지는 깨질 수 있음 |

- 반입 심사에서 **원본 트리나 의존성 목록을 제출**해야 하면 A 다. 전송 용량만 문제라면 A 도
  압축하면 4MB 다.
- **파일 개수나 반입 절차가 제약**이면 B 다. "파일 하나" 라는 형태 자체가 필요할 때 의미가
  있다.

> 위 수치와 방식 B 동작 확인(`initialize` 요청과 응답, `read_text_file`, 허용 디렉터리 밖 차단)은
> `server-filesystem@2026.7.10` 기준이다. 그 뒤 패키지가 **`1.0.0`(2026-10-11)** 으로 올라가며
> 의존이 바뀌었으므로(`glob` ^10에서 ^13으로, `zod` 추가) **수치는 규모 감각용으로만 보고, 번들이
> 실제로 뜨는지는 연결된 PC 에서 한 번 실행해 확인한 뒤 반입한다.** 진입점과
> `bundledDependencies` 미선언은 `1.0.0` 에서도 같다.

## 다른 stdio 서버를 추가할 때

| 상황 | 대응 |
|------|------|
| **순수 JS npm 패키지** (사전 점검에서 출력 없음) | 위 절차 그대로. 방식 A 는 가능하고, 방식 B 는 번들 결과를 실행해 확인한 뒤 사용 |
| **네이티브 모듈 포함** (`*.node`, `os`/`cpu` 제약 있음)<br>예: `better-sqlite3`, `sharp`, `esbuild` 계열 | **배포 대상 OS 에서 `npm install`** 후 그 `node_modules` 를 반입. 방식 B(번들)는 네이티브를 번들에 넣을 수 없으므로 **사용 불가** |
| **Python 기반** (`uvx`, `pip`) | `uvx` 도 캐시에 없으면 실행 시점에 PyPI 에서 받는다. 연결된 PC 에서 `pip download` 로 wheel 을 모아 반입 후 오프라인 설치(`pip install --no-index --find-links`)하고, `command` 를 해당 가상환경의 `python` 으로 지정. wheel 을 모은 PC 와 대상 PC 의 OS·CPU·Python 버전이 같아야 한다 |
| **Docker 이미지 기반** | 연결된 환경에서 `docker save` 로 tar 추출 후 반입해 `docker load` |
| **단일 실행 바이너리** (Go, Rust 등) | 파일 하나만 반입하고 `command` 에 절대 경로 지정. 대상 OS 와 아키텍처용 바이너리를 받음 |

어느 경우든 확인 방법은 같다. **네트워크를 차단한 상태에서 클라이언트를 기동해 해당 서버가
연결되고 도구 목록이 뜨는지, 주요 도구를 실제로 호출했을 때 동작하는지** 본다. 반입 전에 연결된 PC 에서 한 번 실행해 두면 문제를 미리
걸러낼 수 있다.
