# KOReader / CrossPoint 双向进度同步实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Legado 的本地 TXT/EPUB 阅读进度与 KOReader / CrossPoint 通过 KOSync 兼容服务器双向同步，并保留现有 WebDAV 行为。

**Architecture:** 新增 `help/sync` 包：KOSync HTTP 客户端、partial MD5 文档标识、TXT/EPUB 位置映射、进度同步后端接口与协调器。`ReadBook` 只调用协调器；WebDAV 继续负责备份/书籍文件，并在适用书上作为上传端和失败回退端。

**Tech Stack:** Kotlin 2.4.20、Android minSdk 23、OkHttp 5.4.0、Gson、jsoup、Room 113→114、JUnit 4 + Mockito。

**Spec:** `docs/superpowers/specs/2026-11-17-koreader-sync-interop-design.md`

## Global Constraints

- 仅支持本地 TXT/EPUB，且两端文件字节完全一致。
- 文档标识只实现 KOReader Binary partial MD5，不实现 Filename 模式。
- 冲突规则完全按 KOReader：服务器 `timestamp` 最新者胜，Unix 秒。
- 两者都开启时，KOReader 适用书由 KOReader 拉取/判断，WebDAV 只上传；KOReader 拉取失败时提示并回退 WebDAV 拉取一次。
- 非 KOReader 适用书（在线书、PDF、MOBI、UMD、漫画）保持现有 WebDAV 双向逻辑。
- 位置精度为同一段/同一屏；XPointer 生成失败时跳过 KOReader 上传，不能用无效字符串覆盖远端。
- 不把 `x-auth-key`、密码写入日志。
- 新增/修改用户可见文案至少同步 `values/` 与 `values-zh/`。
- 数据库版本 113 → 114，提交 `app/schemas/` 新 schema。
- 不自动执行 `git commit` / `git push`；每项任务以测试通过结束，提交由用户确认后执行。

## Review Focus

1. **TXT 格式检测差异**：crengine 可能按“每行一段”或“空行分段”解析 TXT；映射到错误行会导致落点偏一屏以上。测试必须覆盖两种段落模型和百分比回退。
2. **EPUB 包装节点/单 spine**：XPointer 可能带 crengine 包装节点，或单 spine 省略 `DocFragment[N]`；必须逐级回退到可解析元素，不能抛异常。
3. **服务器异常响应**：`200 {}`、404 HTML、401、5xx 混用；必须区分“无进度”和“拉取失败”，不能覆盖本地或反复重试。
4. **不可 seek 的 SAF 文件**：partial MD5 可能失败；必须跳过该书并记录日志，不能阻塞 UI 或其它书。
5. **双后端交叉**：KOReader 失败后 WebDAV 回退成功、非适用书仍走 WebDAV、KOReader 成功时 WebDAV 不得回拉覆盖；这三种情况必须有协调器测试。

---

### Task 1: KOReader partial MD5 文档标识

**Files:**
- Create: `app/src/main/java/io/legado/app/help/sync/KosyncDocumentId.kt`
- Test: `app/src/test/java/io/legado/app/help/sync/KosyncDocumentIdTest.kt`
- Modify: `app/src/main/java/io/legado/app/help/book/BookExtensions.kt`（仅如缺少可复用的本地文件读取辅助）

**Interfaces:**
- Produces:
  ```kotlin
  object KosyncDocumentId {
      fun partialMd5(channel: FileChannel, size: Long): String
      fun compute(book: Book): String?
  }
  ```
- `partialMd5` 读取偏移 `0, 1024, 4096, ..., 1073741824`，每段最多 1024 字节，读完即停止，输出小写 32 位 hex。
- `compute(book)` 使用 `BookHelp.getBookPFD(book)`，只接受 `book.isLocal && (book.isLocalTxt || book.isEpub)`；异常返回 `null`。
- `compute` 使用内存缓存，键为 `bookUrl + fileSize + lastModified`；文件变化或配置指纹变化后失效。

- [ ] **Step 1: 写失败测试**

测试内容：
- 用临时文件写入 3000 字节，独立构造期望的采样拼接（偏移 0、1024、4096）并计算 MD5，断言 `partialMd5` 相同。
- 空文件、长度 1、长度 1024、长度 1025。
- 断言采样顺序和“遇到不足 1024 字节即停止”的行为。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.sync.KosyncDocumentIdTest"`
Expected: FAIL，`KosyncDocumentId` 不存在。

- [ ] **Step 3: 实现 `KosyncDocumentId`**

使用 `MessageDigest("MD5")` 和 `FileChannel.read(ByteBuffer, position)`。不要用 `RandomAccessFile` 绕过注入的 channel。`compute` 用 `use` 关闭 PFD。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.sync.KosyncDocumentIdTest"`
Expected: PASS。

---

### Task 2: XPointer 解析与序列化

**Files:**
- Create: `app/src/main/java/io/legado/app/help/sync/XPointer.kt`
- Test: `app/src/test/java/io/legado/app/help/sync/XPointerTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class XPointer(
      val fragmentIndex: Int?,
      val steps: List<XPointerStep>,
      val point: Int?,
  )
  sealed interface XPointerStep {
      data class Element(val name: String, val index: Int) : XPointerStep
      data class Text(val index: Int) : XPointerStep
  }
  object XPointerParser {
      fun parse(raw: String): XPointer?
      fun serialize(pointer: XPointer): String
  }
  ```
- 支持 `DocFragment[N]`、单 spine 的 `DocFragment`、元素步、`text()[N]`、`.offset`。
- 不支持 V1 包装节点；解析成功但导航失败由调用方回退。

- [ ] **Step 1: 写失败测试**

用例：
- `/body/DocFragment[8]/body/div[2]/p[4]/text()[1].96` 解析/序列化 round-trip。
- `/body/DocFragment/body/p[42]` 解析为 `fragmentIndex = null`。
- `/body/DocFragment[1]/body`。
- 非法字符串返回 `null`。
- `text().0` 与 `text()[2].5` 的索引处理。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.sync.XPointerTest"`
Expected: FAIL。

- [ ] **Step 3: 实现解析器/序列化器**

只做字符串层解析，不依赖 Android。序列化保持经典 V2：`/body/DocFragment[N]/body...`；`fragmentIndex == null` 时输出不带索引的 `DocFragment`。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.sync.XPointerTest"`
Expected: PASS。

---

### Task 3: KOSync HTTP 客户端

**Files:**
- Create: `app/src/main/java/io/legado/app/help/sync/KosyncClient.kt`
- Create: `app/src/main/java/io/legado/app/help/sync/KosyncModels.kt`
- Test: `app/src/test/java/io/legado/app/help/sync/KosyncClientTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class KosyncProgressDto(
      val document: String? = null,
      val progress: String? = null,
      val percentage: Double? = null,
      val device: String? = null,
      @SerializedName("device_id") val deviceId: String? = null,
      val timestamp: Long? = null,
  )
  data class KosyncListItem(
      val document: String? = null,
      val title: String? = null,
      val author: String? = null,
      val filename: String? = null,
      val progress: String? = null,
      val percentage: Double? = null,
      @SerializedName("device_id") val deviceId: String? = null,
      val timestamp: Long? = null,
  )
  data class KosyncListResponse(
      val items: List<KosyncListItem> = emptyList(),
      val next: String? = null,
  )
  class KosyncClient(
      baseUrl: String,
      username: String,
      authKey: String,
      client: OkHttpClient,
  ) {
      suspend fun auth(): Boolean
      suspend fun getProgress(document: String): KosyncProgressDto?
      suspend fun putProgress(
          document: String,
          progress: String,
          percentage: Double,
          device: String,
          deviceId: String,
      ): Long?
      suspend fun listProgress(limit: Int = 500): List<KosyncListItem>
      suspend fun supportsExtendedApi(): Boolean
  }
  class KosyncException(
      message: String,
      val statusCode: Int? = null,
  ) : IOException(message)
  ```
- `auth()` 调 `GET /users/auth`，200 为 true，401 为 false，其它抛 `IOException`。
- `getProgress`：200 且 body 有 `progress` 或 `percentage` 才返回；200 `{}` 返回 `null`；404 返回 `null`；401/5xx 抛 `KosyncException`。
- `putProgress`：成功后解析 `timestamp`；缺失时 GET 一次补取；仍缺失返回 `null`。
- `listProgress`：`GET /api/v1/progress?limit=...&cursor=...`，按 `next` 翻页，最多 100 页，失败抛 `KosyncException`。
- `supportsExtendedApi()`：`GET /api/v1/progress?limit=1`，200 为 true，404/HTML 为 false，401 抛认证异常。
- 所有请求带 `x-auth-user`、`x-auth-key`、`Accept: application/vnd.koreader.v1+json`。

- [ ] **Step 1: 写失败测试（JDK `HttpServer`）**

在测试内启动 `com.sun.net.httpserver.HttpServer`，端口 0：
- `/users/auth` 断言两个认证头，返回 200/401。
- `/syncs/progress/{doc}` 返回 `200 {}`、404、200 数据。
- `PUT /syncs/progress` 断言 JSON body 和 `device_id`，返回 `{"document":"...","timestamp":123}`。
- `/api/v1/progress?limit=1` 返回列表/404。
- 分页：第一页 `next = "abc"`，第二页 `next = null`，断言 `listProgress` 拉两页。
- PUT 缺失 timestamp 时，测试服务器第二次 GET 返回 timestamp。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.sync.KosyncClientTest"`
Expected: FAIL。

- [ ] **Step 3: 实现客户端**

使用 `okHttpClient.newCallResponse` 或 `client.newCall(...).await()`；测试注入 `OkHttpClient()`，生产注入 `io.legado.app.help.http.okHttpClient`。用 `GSON` 解析 DTO。不要依赖 Android 类，保证 JVM 单测可运行。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.sync.KosyncClientTest"`
Expected: PASS。

---

### Task 4: 数据库字段与迁移

**Files:**
- Modify: `app/src/main/java/io/legado/app/data/entities/Book.kt`
- Modify: `app/src/main/java/io/legado/app/data/AppDatabase.kt`
- Modify: `app/src/main/java/io/legado/app/data/DatabaseMigrations.kt`
- Test: `app/src/androidTest/java/io/legado/app/data/DatabaseMigration113To114Test.kt`
- Generated: `app/schemas/...`

**Interfaces:**
- Produces: `Book.kosyncTime: Long`，默认 `0L`，SQL 列 `kosyncTime INTEGER NOT NULL DEFAULT 0`。
- 迁移：`MIGRATION_113_114` 执行 `ALTER TABLE books ADD COLUMN kosyncTime INTEGER NOT NULL DEFAULT 0`。

- [ ] **Step 1: 写迁移测试**

用 `MigrationTestHelper` 创建 v113 数据库，插入一本书，执行 113→114，断言 `kosyncTime = 0` 且原有字段保留。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:connectedAppDebugAndroidTest --tests "io.legado.app.data.DatabaseMigration113To114Test"`
Expected: FAIL 或编译失败（字段/迁移不存在）。没有设备时记录为待设备验证，继续。

- [ ] **Step 3: 修改实体与迁移**

`Book` 增加字段；`AppDatabase.version = 114`；注册迁移。不要修改历史迁移。

- [ ] **Step 4: 生成 schema 并运行编译/单测**

Run:
```powershell
.\gradlew.bat :app:assembleAppDebug
.\gradlew.bat :app:testAppDebugUnitTest
```
Expected: BUILD SUCCESSFUL，`app/schemas/io.legado.app.data.AppDatabase/114.json` 生成。

---

### Task 5: TXT 位置映射

**Files:**
- Create: `app/src/main/java/io/legado/app/help/sync/TxtPositionMapper.kt`
- Modify: `app/src/main/java/io/legado/app/model/localBook/TextFile.kt`（暴露 `padRegex` 或等价的章节文本规范化函数）
- Test: `app/src/test/java/io/legado/app/help/sync/TxtPositionMapperTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class TxtParagraphMode { LINE, EMPTY_LINE }
  class TxtPositionMapper(
      private val book: Book,
      private val mode: TxtParagraphMode = TxtParagraphMode.LINE,
      private val chapters: () -> List<BookChapter> =
          { appDb.bookChapterDao.getChapterList(book.bookUrl) },
  ) {
      fun toRemote(progress: BookProgress): RemotePosition?
      fun fromRemote(xpointer: String, percentage: Double): BookProgress?
      fun fromPercentage(percentage: Double): BookProgress?
      fun paragraphKey(progress: BookProgress): String?
  }
  data class RemotePosition(
      val progress: String,
      val percentage: Double,
      val paragraphKey: String?,
  )
  ```
- `LINE` 模型：每个非空行一个 `p`，空行一个 `empty-line`。
- `EMPTY_LINE` 模型：连续非空行组成一个 `p`，空行分隔段落。
- 生产默认 `LINE`；Task 10 用真实 KOReader 样本确认后改为实际模型，两种模型都必须有测试。
- 绝对偏移：
  ```kotlin
  fun absoluteByteOffset(book: Book, chapter: BookChapter, chapterPos: Int): Long?
  fun positionAt(book: Book, absoluteByte: Long, chapters: List<BookChapter>): Pair<Int, Int>?
  ```
- XPointer 使用行/段模型：`/body/DocFragment/body/p[lineIndex]/text().column`；`lineIndex` 为 1-based 行号（以 `\n` 计），`column` 为行内字符偏移。`paragraphKey = "txt:$lineIndex"`。
- 解析失败或无法定位时返回 `null`，由调用方用 `percentage` 回退。

- [ ] **Step 1: 写失败测试**

用临时 TXT：
- UTF-8、GBK 各一份；
- LF、CRLF；
- 空行、缩进、超长行；
- 已知章节与 `durChapterPos`，断言绝对偏移；
- 断言生成 `p[N]/text().M` 并能 round-trip 回同一 `BookProgress`；
- 断言 `percentage` 单调、在 0..1；
- 断言 `fromPercentage` 落在合理章节且不抛异常；
- 断言 `LINE` 与 `EMPTY_LINE` 两种段落模型下，生成/解析都能 round-trip；
- 断言章节切分不同但绝对偏移相同的两本书能落到同一行。

测试中不要依赖真实用户文件；真实文件只用于 Task 10 手工验证。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.sync.TxtPositionMapperTest"`
Expected: FAIL。

- [ ] **Step 3: 实现绝对偏移与行模型**

读取 `LocalBook.getBookInputStream(book)`，按 `chapter.start/end` 读取字节，用 `book.fileCharset()` 解码，应用与 `TextFile.getContent()` 相同的 `padRegex` 规范化。不要一次性读完整本 8MB 文件；只读章节范围，必要时用 `skip`。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.sync.TxtPositionMapperTest"`
Expected: PASS。

- [ ] **Step 5: 记录真实 TXT 验证待办**

在 `docs/superpowers/plans/2026-11-18-koreader-sync-interop.md` 的 Task 10 手工步骤中勾选：用真实 KOReader 的 TXT `progress` 样本校准行模型；如果 KOReader 使用空行分段而不是逐行分段，调整 `lineIndex` 计算并补测试。

---

### Task 6: EPUB 位置映射

**Files:**
- Create: `app/src/main/java/io/legado/app/help/sync/EpubPositionMapper.kt`
- Modify: `app/src/main/java/io/legado/app/model/localBook/EpubFile.kt`
- Test: `app/src/test/java/io/legado/app/help/sync/EpubPositionMapperTest.kt`

**Interfaces:**
- `EpubFile` 新增：
  ```kotlin
  fun getSpineHrefs(): List<String>
  fun getSpineIndex(href: String): Int
  fun getSpineBody(href: String): org.jsoup.nodes.Element?
  fun getChapterSpineHref(chapter: BookChapter): String?
  ```
- `EpubPositionMapper`：
  ```kotlin
  class EpubPositionMapper(
      private val book: Book,
      private val chapters: () -> List<BookChapter> =
          { appDb.bookChapterDao.getChapterList(book.bookUrl) },
  ) {
      fun toRemote(
          chapter: BookChapter,
          chapterPos: Int,
          anchorText: String?,
      ): RemotePosition?
      fun fromRemote(
          xpointer: String,
          percentage: Double,
      ): BookProgress?
      fun fromPercentage(percentage: Double): BookProgress?
      fun paragraphKey(progress: BookProgress): String?
  }
  ```
- 生成：在 spine 原始 DOM 中查找 `anchorText`，向上找最近块级元素，生成元素级 XPointer；找不到返回 `null`，不生成降级章首。
- 解析：按 XPointer 导航；失败时逐级去掉最后一步取最深可解析元素；用该元素文本在章节中查找；仍失败用 `percentage`。

- [ ] **Step 1: 写失败测试（程序化生成 EPUB）**

在测试中创建最小 EPUB zip（container.xml、content.opf、两个 XHTML）：
- 单 spine 与多 spine；
- `<p>`、`<div>`、`<li>` 中的锚点；
- 重复文本；
- XPointer 指向不存在节点；
- 单 spine 的 `/body/DocFragment/body/p[1]` 与 `/body/DocFragment[1]/body/p[1]`。
断言：
- 生成路径可被 `fromRemote` 解析回同一章节/段落；
- 解析失败时返回 `percentage` 对应位置或 `null`，不抛异常。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.sync.EpubPositionMapperTest"`
Expected: FAIL。

- [ ] **Step 3: 扩展 `EpubFile` 并实现映射**

`EpubFile` 内部已有 `epubBook`；用 `epubBook.spine.getSpineReferences()[i].resource.href` 获取 spine href，用 `Jsoup.parse(String(resource.data, mCharset))` 获取原始 body。生成路径时从 body 到目标块级元素，按同名兄弟 1-based 计数；`DocFragment` 序号为 `spineIndex + 1`，始终输出 `[N]`。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.sync.EpubPositionMapperTest"`
Expected: PASS。

---

### Task 7: 后端接口、WebDAV 包装与协调器

**Files:**
- Create: `app/src/main/java/io/legado/app/help/sync/ProgressSyncProvider.kt`
- Create: `app/src/main/java/io/legado/app/help/sync/WebDavProgressProvider.kt`
- Create: `app/src/main/java/io/legado/app/help/sync/KosyncProgressProvider.kt`
- Create: `app/src/main/java/io/legado/app/help/sync/ProgressSyncCoordinator.kt`
- Test: `app/src/test/java/io/legado/app/help/sync/ProgressSyncCoordinatorTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class RemoteProgress(
      val bookProgress: BookProgress?,
      val timestamp: Long,
      val percentage: Double?,
      val paragraphKey: String? = null,
      val device: String? = null,
      val rawProgress: String? = null,
  )
  interface ProgressSyncProvider {
      suspend fun pull(book: Book): RemoteProgress?
      suspend fun push(book: Book, progress: BookProgress): Long?
      suspend fun pullAll(books: List<Book>): Map<String, RemoteProgress> = emptyMap()
      fun paragraphKey(book: Book, progress: BookProgress): String? = null
  }
  object PositionEquality {
      fun same(local: BookProgress, localKey: String?, remote: RemoteProgress): Boolean
      fun remoteIsBehind(local: BookProgress, localKey: String?, remote: RemoteProgress): Boolean
  }
  object KosyncCapabilities {
      fun isSupported(serverUrl: String, authKey: String): Boolean
      fun setSupported(serverUrl: String, authKey: String, supported: Boolean)
      fun clear()
  }
  object PositionMapperFactory {
      fun create(book: Book): PositionMapper?
  }
  interface PositionMapper {
      fun toRemote(progress: BookProgress, anchorText: String?): RemotePosition?
      fun fromRemote(xpointer: String, percentage: Double): BookProgress?
      fun fromPercentage(percentage: Double): BookProgress?
      fun paragraphKey(progress: BookProgress): String?
  }
  class ProgressSyncCoordinator(
      private val webDav: ProgressSyncProvider?,
      private val kosync: ProgressSyncProvider?,
      private val isKosyncEligible: (Book) -> Boolean,
      private val now: () -> Long = { System.currentTimeMillis() / 1000 },
  ) {
      suspend fun syncOnOpen(
          book: Book,
          plus: Boolean,
          onApplyRemote: (BookProgress) -> Unit,
          onPromptRemote: (BookProgress) -> Unit,
          onMessage: (String) -> Unit,
      )
      suspend fun syncOnClose(book: Book, onMessage: (String) -> Unit = {})
      suspend fun syncAllOnStartup(books: List<Book>)
  }
  ```
- Coordinator 规则：
  - 非适用书：仅 `webDav` 走现有逻辑。
  - 适用书且 kosync 非空：先 `kosync.pull`；失败提示并 `webDav.pull` 一次。
  - `timestamp > book.kosyncTime`：应用远端；`plus` 且远端在本地后面时走 `onPromptRemote`；否则 `onApplyRemote`。
  - `timestamp <= book.kosyncTime` 且位置不同：`kosync.push`，再 `webDav.push`。
  - 远端 `null`：`kosync.push` + `webDav.push`。
  - 应用远端后：`book.kosyncTime = remote.timestamp`；`webDav.push` 当前进度。
  - 位置相同：只更新 `kosyncTime`，不上传。
- `WebDavProgressProvider.pull` 包装 `AppWebDav.getBookProgress`，`timestamp = 0`；`push` 包装 `AppWebDav.uploadBookProgress`。
- `KosyncProgressProvider` 用 `KosyncDocumentId`、`KosyncClient`、对应 `PositionMapper`；上传成功后更新 `book.kosyncTime`。

- [ ] **Step 1: 写失败测试（fake providers）**

用例：
- 只 WebDAV：非适用书走 WebDAV。
- 只 KOSync：适用书拉取/上传。
- 两者都开：KOSync 优先；WebDAV 只 push；KOSync 成功时不调用 WebDAV pull。
- KOSync pull 抛异常：提示一次，WebDAV pull 一次。
- 远端 timestamp 更新：应用远端；timestamp 旧：上传本地；相同位置：不上传。
- `plus` 且远端在本地后面：走 prompt 而不是 apply。
- 非适用书：即使 KOSync 开启也不调用 KOSync。
- 文档 ID 为 null：按 KOSync 失败处理。
- `PositionEquality`：两端 paragraphKey 相同为 same；只有一端有 key 时用 percentage 差值 0.001 判定；远端百分比落后超过 0.001 为 behind。
- `KosyncCapabilities`：同一 URL+authKey 命中缓存；配置变化后 `clear()` 生效。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.sync.ProgressSyncCoordinatorTest"`
Expected: FAIL。

- [ ] **Step 3: 实现 provider 与协调器**

协调器不直接依赖 Android；`onMessage` 注入提示。`WebDavProgressProvider` 可以只在生产构造时引用 `AppWebDav`。`KosyncProgressProvider` 的 mapper 由 `PositionMapperFactory` 按 `book.isLocalTxt` / `book.isEpub` 创建。

`KosyncProgressProvider.pull` 的映射顺序：

1. `mapper.fromRemote(progress, percentage)`；
2. 失败则 `mapper.fromPercentage(percentage)`；
3. 仍失败则返回 `RemoteProgress(bookProgress = null, ...)`，协调器按 KOSync 失败处理。

`KosyncCapabilities` 用 `ConcurrentHashMap<String, Boolean>` 缓存 `serverUrl + "\n" + authKey`；配置指纹变化时 `clear()`。检测只在首次 `pullAll` / UI 测试连接时执行。

`KosyncProgressProvider.pullAll`：

1. `client.listProgress()` 取所有条目；
2. 对每本适用书计算 `document`；
3. 用 `document` 匹配条目；
4. 映射位置并返回 `Map<document, RemoteProgress>`；
5. 未匹配或映射失败的条目跳过，不抛异常。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.sync.ProgressSyncCoordinatorTest"`
Expected: PASS。

---

### Task 8: 配置、指纹与 UI

**Files:**
- Modify: `app/src/main/java/io/legado/app/constant/PreferKey.kt`
- Modify: `app/src/main/java/io/legado/app/help/config/AppConfig.kt`
- Create: `app/src/main/java/io/legado/app/help/sync/KosyncConfig.kt`
- Modify: `app/src/main/res/xml/pref_config_backup.xml`
- Modify: `app/src/main/java/io/legado/app/ui/config/BackupConfigFragment.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Test: `app/src/test/java/io/legado/app/help/sync/KosyncConfigTest.kt`

**Interfaces:**
- `PreferKey` 新增：
  ```kotlin
  const val progressSyncKosync = "progressSyncKosync"
  const val progressSyncWebDav = "progressSyncWebDav"
  const val kosyncServerUrl = "kosyncServerUrl"
  const val kosyncUsername = "kosyncUsername"
  const val kosyncAuthKey = "kosyncAuthKey"
  const val kosyncDeviceName = "kosyncDeviceName"
  const val kosyncBatchOnStartup = "kosyncBatchOnStartup"
  const val kosyncConfigFingerprint = "kosyncConfigFingerprint"
  ```
- `KosyncConfig`：
  ```kotlin
  data class KosyncConfig(
      val serverUrl: String,
      val username: String,
      val authKey: String,
      val deviceName: String,
      val batchOnStartup: Boolean,
  ) {
      fun isConfigured(): Boolean
      fun fingerprint(): String
  }
  ```
- 指纹 = `sha256(serverUrl + "\n" + username + "\n" + authKey)`。
- 配置保存时若指纹变化，执行 `UPDATE books SET kosyncTime = 0`，清空文档 ID 缓存。
- UI：新增“KOReader 同步”分类，密码输入后计算 md5 存 `kosyncAuthKey`，测试连接调用 `auth()` + `supportsExtendedApi()`。

- [ ] **Step 1: 写失败测试**

- `isConfigured` 对空/非空。
- `fingerprint` 对 URL 尾斜杠归一化、用户名/密钥变化敏感。
- 指纹变化触发重置回调（用纯函数 `onConfigChanged(old, new, reset)` 测试）。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.sync.KosyncConfigTest"`
Expected: FAIL。

- [ ] **Step 3: 实现配置与 UI**

`AppConfig` 提供 getter/setter；`BackupConfigFragment` 绑定密码输入、摘要、`Test connection`；`pref_config_backup.xml` 添加分类。文案英文/中文同步。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.sync.KosyncConfigTest"`
Expected: PASS。

---

### Task 9: 接入阅读流程与启动批量

**Files:**
- Create: `app/src/main/java/io/legado/app/help/sync/AppProgressSync.kt`
- Modify: `app/src/main/java/io/legado/app/model/ReadBook.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/read/page/ReadView.kt`
- Modify: `app/src/main/java/io/legado/app/api/controller/BookController.kt`
- Modify: `app/src/main/java/io/legado/app/App.kt`
- Modify: `app/src/main/java/io/legado/app/help/AppWebDav.kt`（给 `downloadAllBookProgress` 增加过滤参数）
- Test: `app/src/test/java/io/legado/app/help/sync/AppProgressSyncContractTest.kt`

**Interfaces:**
- `AppProgressSync` singleton：
  ```kotlin
  object AppProgressSync {
      fun isKosyncEligible(book: Book): Boolean
      suspend fun syncOnOpen(
          book: Book,
          plus: Boolean,
          onApplyRemote: (BookProgress) -> Unit,
          onPromptRemote: (BookProgress) -> Unit,
          onMessage: (String) -> Unit,
      )
      suspend fun syncOnClose(book: Book, onMessage: (String) -> Unit = {})
      suspend fun push(book: Book, onMessage: (String) -> Unit = {})
      suspend fun syncAllOnStartup(books: List<Book>)
  }
  ```
- `ReadBook` 的 `syncProgress` / `uploadProgress` / `syncBookProgress` 改为调用 `AppProgressSync`；保留现有 `BookProgress` 和 `sureNewProgress` 回调。
- `ReadBook.currentPageAnchorText(): String?` 取当前页第一行非空文本，供 EPUB 上传使用。
- `App.kt` 启动时：
  - 适用书：`AppProgressSync.syncAllOnStartup`（扩展接口可用时）；
  - 非适用书：`AppWebDav.downloadAllBookProgress { !isKosyncEligible(it) }`。
- `AppWebDav.downloadAllBookProgress` 增加过滤参数：
  ```kotlin
  suspend fun downloadAllBookProgress(shouldSync: (Book) -> Boolean = { true })
  ```
  不传时保持现有行为。
- `BookController.saveBookProgress`：本地 TXT/EPUB 走 `AppProgressSync`，其它走 `AppWebDav`。
- `BookController.saveBook`：保存书籍后，本地 TXT/EPUB 走 `AppProgressSync.push`，其它保持 `AppWebDav.uploadBookProgress`。

- [ ] **Step 1: 写失败测试（源码契约 + 纯逻辑）**

- 断言 `ReadBook` 不再直接调用 `AppWebDav.getBookProgress` / `uploadBookProgress`（用源码字符串检查，沿用 `AnalyzeUrlUploadClientTest` 风格）。
- 断言 `App.kt` 启动分支包含 `isKosyncEligible` 过滤。
- 纯逻辑测试：`isKosyncEligible` 对本地 TXT/EPUB 为 true，对网络书/PDF 为 false。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.sync.AppProgressSyncContractTest"`
Expected: FAIL。

- [ ] **Step 3: 接入 `AppProgressSync` 与阅读流程**

保持现有触发点不变：打开书、`onPause`、手动同步。`ReadBook.syncProgress` 把 `newProgressAction` / `uploadSuccessAction` / `syncSuccessAction` 映射到协调器回调。`ReadBookViewModel.syncBookProgress` 用协调器的 `onApplyRemote` 调 `ReadBook.setProgress`。`ReadBookActivity`/`ReadView` 的手动同步直接调 `AppProgressSync`。

- [ ] **Step 4: 运行测试与编译**

Run:
```powershell
.\gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.help.sync.AppProgressSyncContractTest"
.\gradlew.bat :app:assembleAppDebug
```
Expected: PASS / BUILD SUCCESSFUL。

---

### Task 10: 端到端验证

**Files:**
- No production files.
- Update this plan with verification results (checkboxes and notes).

**Interfaces:**
- Consumes all previous tasks.

- [ ] **Step 1: 单元测试全量**

Run: `.\gradlew.bat :app:testAppDebugUnitTest`
Expected: PASS。

- [ ] **Step 2: 真实服务器协议验证**

用本地脚本（不提交凭据）：
- `GET /users/auth` 200。
- `GET /api/v1/progress?limit=5` 200。
- `PUT /syncs/progress` 返回 timestamp。
- `GET /syncs/progress/{document}` 返回刚写入的数据。
- `DELETE /api/v1/progress/{document}` 清理测试数据。

- [ ] **Step 3: TXT 真机验证**

1. 在 KOReader/CrossPoint 打开 `绍宋 - 榴弹怕水.txt`，跳到已知段落并推送。
2. 记录 `progress`、`percentage` 和大致段落。
3. 在 Legado 用相同文件打开，触发拉取，确认落到同一段/同一屏。
4. 在 Legado 前进若干段，退出触发上传。
5. 在 KOReader 拉取，确认落到同一段/同一屏。
6. 如果 TXT 行模型不匹配，记录真实 `progress`，调整 `TxtPositionMapper` 的 `lineIndex` 计算并补测试。

- [ ] **Step 4: EPUB 真机验证**

用 `快乐的死 - 阿尔贝·加缪.epub` 重复 Step 3。现有服务器样本：
- `document = dd6779917d581702bd48fe5ad2af90cd`
- `progress = /body/DocFragment[6]/body/p[42]`
- `percentage = 0.43333333333333335`
确认 Legado 能解析该 XPointer 并落到同一段。

- [ ] **Step 5: 双后端与回退验证**

- 只 WebDAV：行为不变。
- 只 KOReader：正常双向。
- 两者都开：KOReader 成功时 WebDAV 只上传；断开 KOReader 服务器后打开书，提示“KOReader 拉取失败，已回退 WebDAV”，且 WebDAV 进度被应用。
- 非适用书（在线书/漫画）：KOReader 开启时仍走 WebDAV 双向。

- [ ] **Step 6: 记录结果**

把每项结果写在计划末尾；未通过项保留阻塞说明，不勾选。

## Verification results (2026-11-18)

- `:app:testAppDebugUnitTest` — BUILD SUCCESSFUL.
- `:app:testAppReleaseUnitTest` — BUILD SUCCESSFUL.
- `:app:compileAppReleaseKotlin` — BUILD SUCCESSFUL.
- `:app:assembleAppRelease -ParmOnly=true` — BUILD SUCCESSFUL.
- `:app:assembleAppDebug` — FAILED on pre-existing `htmlunit-core-js` D8 `MethodHandle` / minSdk 23 issue, unrelated to this feature.
- Real crosspoint-sync protocol: auth, `/api/v1/progress` list, `PUT/GET/DELETE /syncs/progress`, timestamp response verified with a temporary document; temporary data removed.
- Manual device sync for TXT/EPUB not executed: no adb device attached and no original TXT progress sample. TXT paragraph mode and complex EPUB anchors remain the main unverified item.

