# liaocl 打包构建 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提供一个可重复执行的 PowerShell 打包脚本，构建仅含 `arm64-v8a`、包名为 `top.liaocl.legado`、使用指定 keystore 签名的 release APK。

**Architecture:** 不改变项目默认构建行为，通过 Gradle 项目属性（`-P`）在脚本中覆盖 `applicationId`、ABI 过滤和 Google Services 插件；签名参数通过 `ORG_GRADLE_PROJECT_RELEASE_*` 环境变量注入，避免写入被跟踪的 `gradle.properties`。脚本读取本地 JSON 配置，校验 JDK / SDK / keystore 后调用 Gradle，并整理产物。

**Tech Stack:** Gradle 8.14.4、AGP 8.13.2、Kotlin 2.4.20、PowerShell 5.1+、Android build-tools 36.1.0（aapt2）。

**Spec:** 用户本轮需求（打包脚本、`top.liaocl.legado`、仅 arm64-v8a、给定 keystore 与密码）。

## Global Constraints

- JDK 必须为 21；用户提供的 `corretto-17.0.17` 不满足 `jvmToolchain(21)`。
- `androidHome` = `E:\Code\SDK\android`；`storeFile` = `<本机 keystore 路径>`；`keyAlias` = `<keystore alias>`。
- 默认构建（不传自定义属性）行为必须保持不变。
- 含密码的配置只存在于被 `.gitignore` 忽略的本地文件。
- 不 commit、不 push。

## Review Focus

- 未安装 JDK 21 或 `JAVA_HOME` 指向 17 时，脚本需给出明确错误而不是晦涩的 toolchain 报错。
- 自定义 applicationId 时 Firebase `google-services` 插件会因包名不匹配失败，必须跳过。
- 打包结果必须只含 `arm64-v8a`，且 `applicationId` 精确为 `top.liaocl.legado`（不能带 `.release` 后缀）。
- keystore 路径或密码错误时，失败信息应能定位到签名配置。
- 配置缺失或路径不存在时，脚本不应继续执行到 Gradle 阶段。
- 本机 `~/.gradle/init.d/mirrors.gradle` 会向 project 注入仓库，与 `FAIL_ON_PROJECT_REPOS` 冲突；打包脚本需通过 `-I` 提供兼容的仓库模式。

---

### Task 1: Gradle 侧支持打包覆盖

**Files:**
- Modify: `app/build.gradle`

**Interfaces:**
- Produces: 项目属性 `customApplicationId`、`customAbiFilters`、`skipGoogleServices`，供 Task 2 的脚本传入。

- [x] **Step 1:** 在 `app/build.gradle` 顶部读取属性。
- [x] **Step 2:** `defaultConfig` 中按属性设置 `applicationId`，并优先使用自定义 ABI 列表；未知属性时保持 `com.legado.app` 与 `armOnly` 原逻辑。
- [x] **Step 3:** release / debug 的 `applicationIdSuffix` 仅在未设置 `customApplicationId` 时生效。
- [x] **Step 4:** 从 `plugins {}` 移除 `alias libs.plugins.google.services`，在文件末尾改为 `if (!project.hasProperty("skipGoogleServices")) { apply plugin: 'com.google.gms.google-services' }`。

### Task 2: 打包脚本与本地配置

**Files:**
- Create: `packaging/build-liaocl.ps1`
- Create: `packaging/build-liaocl.bat`
- Create: `packaging/gradle/liaocl-repos.init.gradle`
- Create: `packaging/liaocl-build.example.json`
- Create: `packaging/liaocl-build.local.json`（不提交）
- Modify: `.gitignore`

**Interfaces:**
- Consumes: Task 1 的三个项目属性。
- Produces: `packaging/build-liaocl.ps1 -Config <json>`，成功时输出 `release/liaocl-legado-<versionName>-arm64-v8a.apk`。

- [x] **Step 1:** `.gitignore` 增加 `packaging/*.local.json`。
- [x] **Step 2:** 脚本读取 JSON，校验 `androidHome` / `javaHome` / `storeFile` 存在，校验 Java 主版本 >= 21。
- [x] **Step 3:** 设置 `JAVA_HOME`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`、`PATH`，以及 `ORG_GRADLE_PROJECT_RELEASE_STORE_FILE` / `_KEY_ALIAS` / `_STORE_PASSWORD` / `_KEY_PASSWORD`。
- [x] **Step 4:** 执行 `gradlew.bat :app:assembleAppRelease -PcustomApplicationId=top.liaocl.legado -PcustomAbiFilters=arm64-v8a -PskipGoogleServices=true -I packaging/gradle/liaocl-repos.init.gradle`。
- [x] **Step 5:** 读取 `output-metadata.json` 获取 versionName，复制 APK 到 `release/`，用 `aapt2 dump badging` 校验 `package name` 与 `native-code`，输出 SHA-256。

### Task 3: 文档

**Files:**
- Modify: `AGENTS.md`

- [x] **Step 1:** 增加「本地打包（liaocl）」小节：脚本路径、配置项、JDK 21 要求、产物位置、密码文件不入库。

### Task 4: 实际构建验证

- [x] **Step 1:** 运行 `powershell -ExecutionPolicy Bypass -File packaging/build-liaocl.ps1`。
- [x] **Step 2:** 确认退出码 0，产物存在，`package: name='top.liaocl.legado'`，`native-code: 'arm64-v8a'`。
- [x] **Step 3:** 记录构建耗时与任何环境告警。
