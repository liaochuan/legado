# AGENTS.md

本文件是 Legado 仓库的项目级约定，供 AI 编码助手与新贡献者参考。修改代码前先读本文件，以及目标目录下的 `README.md`（如 `app/src/main/java/io/legado/app/` 各子目录）。

## 项目概况

Legado（阅读）是一个开源 Android 小说 / 漫画阅读器，核心能力是用户自定义书源规则、本地与网络书籍阅读、朗读、下载、WebDAV 备份，以及内置 Web / MCP 服务。

- 主模块：`app/`（包名根 `io.legado.app`）
- 辅助库模块：`modules/book`（EPUB / UMD 解析）、`modules/rhino`（改版 Rhino JS 引擎）
- Web 前端源码：`modules/web`（Vue 3 + TypeScript + Vite + Element Plus），构建产物同步到 `app/src/main/assets/web/vue/`
- 项目文档：`README.md`、`api.md`（Web / MCP 接口说明）、各源码目录的 `README.md`

## 环境与工具链

| 项 | 版本 / 说明 |
| --- | --- |
| JDK | 21（`jvmToolchain`、`sourceCompatibility` 均为 21） |
| Gradle | 8.14.4（Wrapper） |
| AGP / Kotlin / KSP | 8.13.2 / 2.4.20 / 2.3.12 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 23 |
| Room | schema 导出到 `app/schemas`，当前数据库版本 113 |
| Web 前端 | Node >= 20、pnpm >= 9 |

Android SDK 路径来自根目录 `local.properties`，这是本地文件，不要修改或提交。签名相关配置通过 `RELEASE_*` Gradle 属性注入，仓库内不应出现密钥。

## 常用命令

Windows 使用 `gradlew.bat`，其他平台使用 `./gradlew`。以下命令在仓库根目录执行。

### Android

```bash
# 编译 debug（product flavor 只有 app）
./gradlew :app:assembleAppDebug

# 单元测试（与 CI 一致，使用 release variant）
./gradlew :app:testAppReleaseUnitTest

# 只跑某个测试类 / 方法
./gradlew :app:testAppDebugUnitTest --tests "io.legado.app.model.AnalyzeUrlUploadClientTest"

# 仅构建 ARM ABI 的 release 包（CI 使用）
./gradlew :app:assembleRelease -ParmOnly=true
```

- 仪器测试位于 `app/src/androidTest`，需要模拟器或真机。
- 可用 `./gradlew :app:tasks` 查看当前 variant 对应的任务名。

### Web 前端

```bash
cd modules/web
pnpm install --frozen-lockfile
pnpm test          # node --test
pnpm type-check    # vue-tsc
pnpm build         # test + build-only + type-check + sync
pnpm lint:fix
pnpm format
```

`modules/web/scripts/sync.js` 只在 `GITHUB_ENV` 存在（即 CI）时把 `dist/` 复制到 `app/src/main/assets/web/vue/`；本地 `pnpm build` 不会自动同步，需要时手动复制。不要手改生成后的 `app/src/main/assets/web/vue/` 文件。

## 本地打包（liaocl）

用于生成与官方包名隔离的个人安装包：

- `applicationId`：`top.liaocl.legado`（不带 `.release` 后缀）
- ABI：仅 `arm64-v8a`
- 构建类型：`release`（R8 + 资源压缩 + 指定 keystore 签名）
- 入口：`packaging/build-liaocl.ps1`（Windows 可直接运行 `packaging/build-liaocl.bat`）

### 前置要求

| 项 | 要求 |
| --- | --- |
| 系统 | Windows + PowerShell 5.1+ |
| JDK | 21 或更高（项目 `jvmToolchain(21)`），脚本会校验主版本 |
| Android SDK | 含 `build-tools`（`aapt2`、`apksigner`）与 `platform-tools` |
| Gradle | 使用仓库 Wrapper（8.14.4），首次可能自动下载 |

### 配置

首次运行：

```powershell
Copy-Item packaging\liaocl-build.example.json packaging\liaocl-build.local.json
```

`packaging/liaocl-build.local.json` 字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `androidHome` | 是 | Android SDK 根目录 |
| `javaHome` | 是 | JDK 21+ 根目录 |
| `storeFile` | 是 | keystore 路径，建议绝对路径 |
| `keyAlias` | 是 | 签名 alias |
| `storePassword` | 是 | keystore 密码 |
| `keyPassword` | 是 | key 密码 |
| `gradleUserHome` | 否 | 默认 `<仓库上一级>/.gradle-home`，必须与项目同盘 |
| `appId` | 否 | 默认 `top.liaocl.legado` |
| `abi` | 否 | 默认 `arm64-v8a`，多个用逗号分隔 |

- 该文件包含明文密码，已被 `.gitignore` 忽略，**不要提交，也不要粘贴到日志或 Issue**。
- 签名信息通过环境变量传给 Gradle 子进程，不出现在命令行或 PowerShell 历史中；同一用户的其他进程理论上可读取，共享电脑上请自行限制权限。

### 命令

```powershell
# 默认配置打包（也可双击）
packaging\build-liaocl.bat

# 等价命令
powershell -NoProfile -ExecutionPolicy Bypass -File packaging\build-liaocl.ps1

# 可选参数
powershell -NoProfile -ExecutionPolicy Bypass -File packaging\build-liaocl.ps1 `
  -Config packaging\liaocl-build.local.json `
  -OutputDir D:\apk `
  -SkipVerify
```

`-SkipVerify` 会跳过包名 / ABI / 签名校验，仅在环境缺少 `aapt2` / `apksigner` 时使用；默认缺少校验工具会直接失败。`-OutputDir` 用于指定输出目录，默认 `dist/`。

### 构建过程

脚本会：

1. 读取配置，校验 SDK / JDK / keystore 路径与 Java 主版本；
2. 设置 `JAVA_HOME`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`、`GRADLE_USER_HOME`；
3. 通过 `ORG_GRADLE_PROJECT_RELEASE_*` 注入签名信息（不写入 `gradle.properties`，不出现在命令行）；
4. 执行 `:app:assembleAppRelease`，并传入 `-PcustomApplicationId=...`、`-PcustomAbiFilters=...`；
5. 用 `aapt2 dump badging` 校验 `applicationId` 与 `native-code`；
6. 用 `apksigner verify` 校验签名；
7. 复制到输出目录，打印文件大小与 SHA-256。

产物：`dist/liaocl-legado-<versionName>-<abi>.apk`（`dist/` 已被 `.gitignore` 忽略）。

### Gradle 覆盖属性

| 属性 | 作用 |
| --- | --- |
| `customApplicationId` | 覆盖 `defaultConfig.applicationId`；设置后 release 不再追加 `.release`，debug 仍保留 `.debug` |
| `customAbiFilters` | 覆盖 ABI 过滤，逗号分隔，例如 `arm64-v8a` |

不传这些属性时，构建行为与上游一致。需要改打包参数时优先使用属性，不要直接改 `defaultConfig` 的默认值，以减少与上游同步的冲突。

### 环境排障

- **JDK 版本错误**：`jvmToolchain` 要求 Java 21，JDK 17 会在配置或编译阶段失败。
- **`FAIL_ON_PROJECT_REPOS` 冲突**：若 `~/.gradle/init.d` 有向 project 注入仓库的脚本，打包脚本会额外传入 `-I packaging/gradle/liaocl-repos.init.gradle`；该文件中的仓库列表需与 `settings.gradle` 保持同步。
- **KSP `different roots`**：KSP 2 要求 Gradle 用户目录与项目在同一磁盘。脚本默认使用仓库上一级的 `.gradle-home` 并校验盘符；依赖优先通过 `GRADLE_RO_DEP_CACHE` 复用 `~/.gradle/caches`，缺失项会下载到该目录。
- **签名失败**：核对 `storeFile`、`keyAlias`、`storePassword`、`keyPassword`，路径建议使用绝对路径。
- **首次构建慢**：需要下载 Gradle 发行版与依赖；有缓存后增量构建会快很多。

### 已知限制

- 自定义包名不在应用的更新变体映射中，“检查更新”会返回官方 APK，因包名 / 签名不同无法覆盖安装；需要时关闭更新检查或修改更新源。
- 自定义签名与官方签名不同，不能覆盖安装官方版本，两者可以共存。

## 目录结构

```
app/src/main/java/io/legado/app/
├── api/          # HTTP / MCP 接口与 controller
├── base/         # Activity / Fragment / ViewModel 等基类
├── constant/     # 常量、枚举、EventBus key、PreferKey
├── data/         # Room：AppDatabase、DatabaseMigrations、dao、entities
├── exception/    # 异常类型
├── help/         # 配置、网络、存储、更新、TTS、协程封装等帮助类
│   └── coroutine/# 项目内协程封装（Coroutine / CompositeCoroutine）
├── lib/          # 内置第三方库（dialogs、theme、webdav、mobi 等）
├── model/        # 书源规则解析 analyzeRule、阅读、朗读、下载、订阅等业务
├── receiver/     # 广播接收
├── service/      # 前台服务（阅读、朗读、下载、Web 服务、MCP 等）
├── ui/           # Activity / Fragment / Adapter / 对话框（ViewBinding）
├── utils/        # 通用工具
├── web/          # 内置 Web 服务实现
└── App.kt
```

- `modules/book`：EPUB / UMD 等书籍格式解析。
- `modules/rhino`：Rhino JS 引擎及 htmlunit core-js 补丁，书源脚本执行依赖它。
- `app/src/main/assets/`：默认书源、帮助文档、内置 Web 资源、脚本模板等。
- `app/schemas/`：Room 导出的数据库 schema，必须随迁移一起提交。
- `.github/workflows/`：CI（`test.yml`、`web.yml`、`release.yml` 等）。

## 编码约定

### Kotlin / Android

- 4 空格缩进；新代码使用显式 import，历史文件中的 `*` 通配导入不必顺手格式化。
- 界面层使用 Activity / Fragment + ViewBinding（`XxxBinding`），新代码遵循现有 `base/` 中的基类模式。
- 异步优先使用协程；耗时操作不要阻塞主线程。项目内的协程封装位于 `help/coroutine/`。
- 回调、状态与跨界面通信沿用现有方式（`EventBus` / `LiveEventBus`），不要另起一套。
- 兼容 minSdk 23：使用高版本 API 时必须做版本判断；项目已开启 core library desugaring。
- 修改书源规则解析时，注意 `model/analyzeRule/` 与 `modules/rhino` 的联动，避免破坏现有书源。

### 文案与资源

- 默认资源 `app/src/main/res/values/strings.xml` 为英文，中文主翻译在 `values-zh/`；其余语言目录包括 `values-zh-rHK`、`values-zh-rTW`、`values-es-rES`、`values-ja-rJP`、`values-pt-rBR`、`values-vi`（`values-en/` 只有少量覆盖资源）。
- 新增或修改用户可见文案时，至少同步 `values/` 与 `values-zh/`；其余语言可后续补翻译。
- 注释和提交信息以中文为主（历史代码中也存在英文注释，保持一致即可）；类名、函数名、变量名、API 字段保持英文。
- `lint` 已关闭 `MissingTranslation`，不要以此为由跳过必要的文案同步。

### 数据库

- 修改实体或表结构时必须：
  1. 提升 `app/src/main/java/io/legado/app/data/AppDatabase.kt` 的 `version`；
  2. 在 `data/DatabaseMigrations.kt` 中新增并注册 `Migration`；
  3. 提交 `app/schemas/` 下新生成的 schema JSON。
- 不要删除历史 migration，也不要修改已发布的 schema。
- 迁移逻辑要考虑用户跨多个版本升级，以及已发布版本的异常状态（参考现有迁移中的兼容处理）。

### 依赖

- 依赖版本集中在 `gradle/libs.versions.toml`，使用 version catalog，不要在 `build.gradle` 里散落硬编码版本。
- 部分版本带有“不要更新”的注释（如 `jsoup`、`commonsText`、`hutool`、`pdfbox-android`），升级前先确认注释中的兼容性原因。
- `settings.gradle` 中的镜像仓库仅用于本地网络不通时临时启用，不要提交相关改动。

### Web 接口与安全

- 内置 Web 服务（NanoHTTPD）与 MCP 服务默认监听局域网，访问令牌保护可被关闭；修改接口时必须保留 `X-Legado-Token` 校验逻辑。
- 新增或变更 HTTP / MCP 接口时，同步更新根目录 `api.md`。
- 不要在日志中输出令牌、Cookie、书源密钥等敏感信息。

## 测试

- 单元测试使用 JUnit 4 + Mockito，位于 `app/src/test/java`，包结构与 `main` 对应。
- 修复 bug 时优先补一个能复现问题的回归测试；只跑相关测试类以缩短反馈时间。
- 提交前至少执行 `./gradlew :app:testAppReleaseUnitTest`（与 CI 相同）。
- Web 改动至少执行 `pnpm test` 与 `pnpm type-check`。
- 部分测试会读取源码文件内容做约定校验（如 `AnalyzeUrlUploadClientTest`），移动或重命名相关文件时需同步更新测试。

## 分支策略

- `origin` 指向个人 fork：`https://github.com/liaochuan/legado.git`；未配置 `upstream` 远端，同步上游通过 GitHub 的 Sync fork 完成。
- `master`：只用于跟 `origin/master`（以及 Sync fork 后的上游）保持同步，不直接在其上开发。
- `liaocl`：个人开发分支，功能、修复、文档改动都提交到这里，避免在 `master` 上堆积提交后与同步、PR 产生冲突。

日常流程：

1. 同步上游：在 GitHub 执行 Sync fork，然后本地切到 `master`，执行 `git pull --ff-only origin master`。
2. 开发：切回 `liaocl`；需要吸收 `master` 更新时用 `git rebase master`（或 `git merge master`，与团队约定保持一致）。
3. 推送：首次 `git push -u origin liaocl`，之后 `git push`。
4. 提 PR：从 `liaocl` 发起，目标分支按仓库约定选择（通常是 fork 的 `master`）；不要直接把开发提交放在 `master` 上。
5. 提 PR 前确认 `liaocl` 与 `master` 的差异只包含本次改动；不要 force-push 公共分支，改写已推送历史前先征得同意。

## 提交与 PR

- 提交信息使用 Conventional Commits，类型与描述用小写英文类型 + 中文描述，例如：
  - `fix: 修复段落阅读模式背景未同步的问题 (#1445)`
  - `feat(webdav): 本地导入书籍后自动上传到 WebDav (#1418)`
- 一次提交只做一件事，避免混入无关格式化或重构。
- 不要提交 `build/`、`.gradle/`、`.kotlin/`、`local.properties`、`node_modules/` 等生成或本地文件。
- PR 请按 `.github/pull_request_template.md` 填写变更说明、变更类型、涉及平台 / 模块、影响类型与验证结果。

## 助手行为约束

- 不要主动执行 `git commit`、`git push`、`git merge`、`git tag`，也不要改动暂存区，除非用户明确要求。
- 不要擅自升级依赖、调整全局配置或进行无关重构；保持最小 diff。
- 遇到需要修改生成文件的情况（`app/src/main/assets/web/vue/`、`app/schemas/` 等），优先修改源文件并说明重新生成方式。
- 改动完成后说明改了什么、验证了哪些命令，并提示用户自行检查后提交。
