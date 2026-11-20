# Calibre-Web / OPDS 书库 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Legado 中新增 OPDS 服务器类型，让用户填地址、用户名、密码后逐级浏览 calibre-web 书库，并把书籍下载到本地书架。

**Architecture:** 复用现有「远程书籍」目录浏览器。`RemoteBookManager` 抽象出 OPDS 实现，`RemoteBookViewModel` 按 `Server.type` 创建 manager；`Server` 表只增加枚举值和 JSON 配置，不新增列、不升数据库版本。协议层拆成 `OpdsUrl`、`OpdsFeedParser`、`OpdsClient`，便于单测。

**Tech Stack:** Kotlin、OkHttp、jsoup（XML 解析）、Glide、Room、JUnit 4、ktor-server-test-host

**Spec:** `docs/superpowers/specs/2026-11-19-calibre-web-opds-design.md`

## Global Constraints

- minSdk 23；高版本 API 必须做版本判断。
- `Server` 表不新增列，不升 `AppDatabase.version`，不提交新 schema。
- 不新增 `BookType`；OPDS 导入的书保持 `BookType.localTag`，不写 `webDavTag`。
- 不把用户名、密码、`Authorization` 写入 `AppLog`、HTTP 日志或异常信息。
- 新增用户可见文案至少同步 `app/src/main/res/values/strings.xml` 与 `app/src/main/res/values-zh/strings.xml`。
- 单测命令：`gradlew.bat :app:testAppDebugUnitTest --tests "<类名>"`；提交前跑 `gradlew.bat :app:testAppReleaseUnitTest`。
- OPDS 根入口按 feed 渲染，不硬编码 `/opds/author`、`/opds/series`。

## Review Focus

- 根 feed 的目录 entry 没有 `rel="subsection"`，只有 `link[type*=atom+xml]`；漏掉会让根目录显示为空。
- 书籍 entry 的 `id` 是 `urn:uuid:...`，被当成路径会让下载 404。
- 中文、空格、`/` 的搜索词未编码会让请求 400/404。
- 反向代理把 `/opds` 301 到 `/opds/` 时可能丢 `Authorization`，表现是“密码明明正确却 401”。
- 分页滚动触发多次会造成重复追加或重复请求。

---

### Task 1: Server 增加 OPDS 类型与配置项

**Files:**
- Modify: `app/src/main/java/io/legado/app/data/entities/Server.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/import/remote/ServerConfigDialog.kt`
- Modify: `app/src/main/res/values/arrays.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Create: `app/src/test/java/io/legado/app/data/entities/ServerOpdsConfigTest.kt`

**Interfaces:**
- Consumes: 现有 `Server`、`Server.WebDavConfig`。
- Produces:
  - `Server.TYPE.OPDS`
  - `Server.OpdsConfig(url: String, username: String, password: String) : Parcelable`
  - `Server.getOpdsConfig(): OpdsConfig?`

- [ ] **Step 1: 写失败测试**

```kotlin
class ServerOpdsConfigTest {
    @Test
    fun `opds config round trips through server json`() {
        val server = Server(
            type = Server.TYPE.OPDS,
            config = GSON.toJson(Server.OpdsConfig("https://calibre.example/opds", "u", "p"))
        )
        val config = server.getOpdsConfig()
        assertEquals("https://calibre.example/opds", config?.url)
        assertEquals("u", config?.username)
        assertEquals("p", config?.password)
    }

    @Test
    fun `webdav server has no opds config`() {
        val server = Server(type = Server.TYPE.WEBDAV, config = "{}")
        assertNull(server.getOpdsConfig())
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.data.entities.ServerOpdsConfigTest"`
Expected: 编译失败，`TYPE.OPDS` / `getOpdsConfig` 不存在。

- [ ] **Step 3: 实现模型**

`Server.TYPE` 增加 `OPDS`；新增 `OpdsConfig`，并实现：

```kotlin
fun getOpdsConfig(): OpdsConfig? =
    if (type == TYPE.OPDS) GSON.fromJsonObject<OpdsConfig>(config).getOrNull() else null
```

`arrays.xml` 的 `server_type` 增加 `<item>OPDS</item>`。

- [ ] **Step 4: 运行测试**

Run: `gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.data.entities.ServerOpdsConfigTest"`
Expected: PASS。

- [ ] **Step 5: 配置对话框支持类型分支**

`ServerConfigDialog`：

- `upConfigView` 的 `spType.setSelection` 按 `server?.type` 选择 0/1。
- `when (server?.type)` 两个分支都调用同一个 `upWebDavServerUi(server?.getConfigJsonObject())`（OPDS 字段同为 `url`、`username`、`password`）。
- `getServer()` 的 `server.type` 与 `server.config` 按 `spType.selectedItemPosition` 分支：0 → WEBDAV + `getWebDavConfig()`，1 → OPDS + 同一个 map。

- [ ] **Step 6: 文案**

`values/strings.xml` 与 `values-zh/strings.xml` 增加 `opds`（服务器类型说明）。`test_connection` 等连接测试文案在 Task 4 再加。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/io/legado/app/data/entities/Server.kt \
  app/src/main/java/io/legado/app/ui/book/import/remote/ServerConfigDialog.kt \
  app/src/main/res/values/arrays.xml app/src/main/res/values/strings.xml \
  app/src/main/res/values-zh/strings.xml \
  app/src/test/java/io/legado/app/data/entities/ServerOpdsConfigTest.kt
git commit -m "feat(opds): 增加 OPDS 服务器类型与配置项"
```

---

### Task 2: OPDS 地址规范化

**Files:**
- Create: `app/src/main/java/io/legado/app/model/opds/OpdsUrl.kt`
- Create: `app/src/test/java/io/legado/app/model/opds/OpdsUrlTest.kt`

**Interfaces:**
- Produces:
  - `OpdsUrl.normalize(input: String): String`
  - `OpdsUrl.resolve(base: String, href: String): String`
  - `OpdsUrl.search(root: String, key: String): String`

- [ ] **Step 1: 写失败测试**

```kotlin
class OpdsUrlTest {
    @Test fun `bare host appends opds`() {
        assertEquals("http://host:8083/opds", OpdsUrl.normalize("http://host:8083"))
    }
    @Test fun `trailing slash appends opds`() {
        assertEquals("https://host/calibre/opds", OpdsUrl.normalize("https://host/calibre/"))
    }
    @Test fun `existing opds is not duplicated`() {
        assertEquals("https://host/opds", OpdsUrl.normalize("https://host/opds/"))
    }
    @Test fun `relative href resolves against feed url`() {
        assertEquals("https://host/opds/new?offset=60",
            OpdsUrl.resolve("https://host/opds/new?", "/opds/new?offset=60"))
    }
    @Test fun `search encodes chinese and slash`() {
        assertEquals("https://host/opds/search/%E4%B8%89%E4%BD%93",
            OpdsUrl.search("https://host/opds", "三体"))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.model.opds.OpdsUrlTest"`
Expected: 编译失败，`OpdsUrl` 不存在。

- [ ] **Step 3: 实现**

- `normalize`：`trim()` → 去末尾 `/` → 若 path 以 `/opds` 结尾原样返回，否则拼 `/opds`。
- `resolve`：`base.toHttpUrl().resolve(href)?.toString()`，失败时返回 `href`。
- `search`：`"${normalize(root)}/search/" + URLEncoder.encode(key, "UTF-8")`，编码后把 `+` 换回 `%20`。

- [ ] **Step 4: 运行测试**

Run: `gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.model.opds.OpdsUrlTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/io/legado/app/model/opds/OpdsUrl.kt \
  app/src/test/java/io/legado/app/model/opds/OpdsUrlTest.kt
git commit -m "feat(opds): 规范化 OPDS 地址与搜索 URL"
```

---

### Task 3: OPDS feed 解析

**Files:**
- Create: `app/src/main/java/io/legado/app/model/opds/OpdsException.kt`
- Create: `app/src/main/java/io/legado/app/model/opds/OpdsFeed.kt`
- Create: `app/src/main/java/io/legado/app/model/opds/OpdsFeedParser.kt`
- Create: `app/src/test/java/io/legado/app/model/opds/OpdsFeedParserTest.kt`
- Create: `app/src/test/resources/opds/root.xml`
- Create: `app/src/test/resources/opds/books.xml`
- Create: `app/src/test/resources/opds/search.xml`
- Create: `app/src/test/resources/opds/empty.xml`

**Interfaces:**
- Produces:
  - `enum class OpdsError { UNAUTHORIZED, FORBIDDEN, NOT_FOUND, NOT_OPDS, NETWORK, SERVER }`
  - `class OpdsException(val error: OpdsError, message: String, cause: Throwable? = null) : Exception`
  - `data class OpdsFeed(title: String?, nextUrl: String?, entries: List<OpdsEntry>)`
  - `data class OpdsEntry(title: String, id: String?, updated: Long, author: String?, categories: List<String>, navigationUrl: String?, coverUrl: String?, acquisitionLinks: List<OpdsLink>)`
  - `data class OpdsLink(href: String, mimeType: String?, length: Long?, label: String?)`
  - `OpdsFeedParser.parse(xml: String, baseUrl: String): OpdsFeed`

- [ ] **Step 1: 准备脱敏样本**

从真实实例抓取后，替换书名、作者、简介、封面 ID 为占位值，保留结构：

- `root.xml`：4 个无 `rel` 的目录 entry。
- `books.xml`：含 author、cover、acquisition（TXT/EPUB）、`rel=next`。
- `search.xml`：2 个书籍 entry。
- `empty.xml`：只有 `<feed>`，无 entry。

- [ ] **Step 2: 写失败测试**

```kotlin
class OpdsFeedParserTest {
    private fun resource(name: String) =
        javaClass.classLoader!!.getResource("opds/$name")!!.readText()

    @Test fun `root entries without rel are navigation`() {
        val feed = OpdsFeedParser.parse(resource("root.xml"), "https://host/opds")
        assertEquals(4, feed.entries.size)
        assertEquals("https://host/opds/category", feed.entries.first { it.title == "Categories" }.navigationUrl)
        assertTrue(feed.entries.first { it.title == "Categories" }.acquisitionLinks.isEmpty())
    }

    @Test fun `book entry maps author cover acquisition and next`() {
        val feed = OpdsFeedParser.parse(resource("books.xml"), "https://host/opds/new")
        assertEquals("https://host/opds/new?offset=60", feed.nextUrl)
        val book = feed.entries.first()
        assertEquals("示例作者", book.author)
        assertEquals("https://host/opds/cover/1", book.coverUrl)
        assertEquals(2, book.acquisitionLinks.size)
        assertEquals("application/epub+zip", book.acquisitionLinks.first { it.label == "EPUB" }.mimeType)
        assertNull(book.navigationUrl)
    }

    @Test fun `entry id is never used as path`() {
        val feed = OpdsFeedParser.parse(resource("books.xml"), "https://host/opds/new")
        assertFalse(feed.entries.first().acquisitionLinks.first().href.startsWith("urn:uuid"))
    }

    @Test fun `empty feed is empty not error`() {
        assertTrue(OpdsFeedParser.parse(resource("empty.xml"), "https://host/opds").entries.isEmpty())
    }

    @Test(expected = OpdsException::class)
    fun `html login page is not opds`() {
        OpdsFeedParser.parse("<html><body>login</body></html>", "https://host/opds")
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.model.opds.OpdsFeedParserTest"`
Expected: 编译失败。

- [ ] **Step 4: 实现解析器**

用 `Jsoup.parse(xml, baseUrl, Parser.xmlParser())`：

- 根不是 `feed` → `throw OpdsException(OpdsError.NOT_OPDS, "返回内容不是 OPDS 目录")`。
- `nextUrl = doc.selectFirst("feed > link[rel=next]")?.absUrl("href")`。
- 每个 `feed > entry`：
  - `title = entry.selectFirst("title")?.text().orEmpty()`；空标题跳过。
  - `author = entry.selectFirst("author > name")?.text()`。
  - `updated = parseRfc3339(entry.selectFirst("updated")?.text())`，失败为 0。
  - `categories = entry.select("category").mapNotNull { it.attr("label").ifBlank { it.attr("term") } }`。
  - `acquisitionLinks = entry.select("link").filter { it.attr("rel").startsWith("http://opds-spec.org/acquisition") }`，`href = absUrl`。
  - `coverUrl = entry.selectFirst("link[rel=http://opds-spec.org/image/thumbnail]")?.absUrl("href") ?: entry.selectFirst("link[rel=http://opds-spec.org/image]")?.absUrl("href")`。
  - `navigationUrl`：有 acquisition 时为 null；否则取 `link[rel=subsection]` 的 href，或第一个 `type` 含 `atom+xml` 的 link href。

- [ ] **Step 5: 运行测试**

Run: `gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.model.opds.OpdsFeedParserTest"`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/io/legado/app/model/opds app/src/test/java/io/legado/app/model/opds/OpdsFeedParserTest.kt app/src/test/resources/opds
git commit -m "feat(opds): 解析 Atom feed 与书籍条目"
```

---

### Task 4: OPDS 网络客户端

**Files:**
- Create: `app/src/main/java/io/legado/app/model/opds/OpdsClient.kt`
- Create: `app/src/test/java/io/legado/app/model/opds/OpdsClientTest.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/import/remote/ServerConfigDialog.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/import/remote/ServerConfigViewModel.kt`
- Modify: `app/src/main/res/menu/server_config.xml`

**Interfaces:**
- Consumes: `OpdsError`、`OpdsException`（Task 3）。
- Produces:
  - `open class OpdsClient { open suspend fun getFeed(...); open suspend fun download(...) }`
  - `OpdsClient.getFeed(url: String, authorization: String): OpdsFeed`
  - `OpdsClient.download(url: String, authorization: String): DownloadedFile`
  - `data class DownloadedFile(val stream: InputStream, val fileName: String?)`
  - `OpdsClient.parseFileName(contentDisposition: String?): String?`
  - `ServerConfigViewModel.testConnection(server: Server, onResult: (Boolean, String) -> Unit)`

- [ ] **Step 1: 写失败测试**

用 `ktor-server-test-host` 起本地服务：

```kotlin
class OpdsClientTest {
    @Test fun `sends basic auth and parses feed`() = runBlocking {
        // server 断言 Authorization: Basic dTpw，返回 root.xml
        val feed = OpdsClient().getFeed(url, Credentials.basic("u", "p"))
        assertEquals(4, feed.entries.size)
    }
    @Test fun `401 maps to unauthorized`() = runBlocking {
        // server 返回 401
        val e = assertThrows(OpdsException::class.java) { runBlocking { client.getFeed(url, auth) } }
        assertEquals(OpdsError.UNAUTHORIZED, e.error)
    }
    @Test fun `403 maps to forbidden`() { /* ... */ }
    @Test fun `404 maps to not found`() { /* ... */ }
    @Test fun `download reads rfc5987 filename`() {
        assertEquals("中文 书名.epub",
            OpdsClient.parseFileName("attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87%20%E4%B9%A6%E5%90%8D.epub"))
    }
    @Test fun `redirect that drops auth surfaces as unauthorized`() = runBlocking {
        // server: /opds -> 302 /opds/，/opds/ 在无 Authorization 时返回 401
        val e = assertThrows(OpdsException::class.java) { runBlocking { client.getFeed(url, auth) } }
        assertEquals(OpdsError.UNAUTHORIZED, e.error)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.model.opds.OpdsClientTest"`
Expected: 编译失败。

- [ ] **Step 3: 实现**

- `getFeed`：`Request.Builder().url(url).header("Authorization", auth).header("Accept", "application/atom+xml,application/xml;q=0.9,*/*;q=0.8").get()`，用 `okHttpClient.newCallStrResponse(retry = 1)`。
- 状态码映射：401 → `UNAUTHORIZED`，403 → `FORBIDDEN`，404 → `NOT_FOUND`，5xx → `SERVER`；`IOException` → `NETWORK`。
- 2xx 交给 `OpdsFeedParser.parse(body, response.url)`；解析抛 `NOT_OPDS` 时原样传递。
- 不记录 URL 之外的请求头；日志里不出现 Authorization。
- `download`：返回 `body.byteStream()` 与 `parseFileName(response.header("Content-Disposition"))`；调用方负责关闭。
- `parseFileName`：优先解析 `filename*=UTF-8''...`（`URLDecoder.decode(value, "UTF-8")`），其次 `filename="..."`，去掉引号。

- [ ] **Step 4: 运行测试**

Run: `gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.model.opds.OpdsClientTest"`
Expected: PASS。

- [ ] **Step 5: 服务器配置增加“测试连接”**

- `server_config.xml` 增加 `R.id.menu_test_connection`（`showAsAction="never"`）。
- `ServerConfigDialog.onMenuItemClick` 用当前输入构造 `Server`，调用 `viewModel.testConnection(server) { ok, message -> toast }`。
- `ServerConfigViewModel.testConnection(server, onResult)`：
  ```kotlin
  fun testConnection(server: Server, onResult: (Boolean, String) -> Unit)
  ```
  对 OPDS：`OpdsUrl.normalize(config.url)` → `OpdsClient().getFeed(url, Credentials.basic(username, password, UTF_8))`；成功返回 `true` 与根分类数；失败按 `OpdsError` 映射为字符串资源。对 WEBDAV 保持提示“仅 OPDS 支持测试连接”。
- `values/strings.xml` 与 `values-zh/strings.xml` 增加：`test_connection`、`connection_success`、`connection_failed`、`auth_failed`、`download_forbidden`、`not_opds`。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/io/legado/app/model/opds \
  app/src/main/java/io/legado/app/ui/book/import/remote/ServerConfigViewModel.kt \
  app/src/test/java/io/legado/app/model/opds/OpdsClientTest.kt
git commit -m "feat(opds): 接入 Basic Auth 网络层与错误映射"
```

---

### Task 5: RemoteBookOpds

**Files:**
- Modify: `app/src/main/java/io/legado/app/model/remote/RemoteBook.kt`
- Modify: `app/src/main/java/io/legado/app/model/remote/RemoteBookManager.kt`
- Create: `app/src/main/java/io/legado/app/model/remote/RemoteBookOpds.kt`
- Create: `app/src/test/java/io/legado/app/model/remote/RemoteBookOpdsTest.kt`

**Interfaces:**
- Consumes: Task 2、3、4。
- Produces:
  - `RemoteBook(filename, path, size, lastModify, contentType, isOnBookShelf, links, coverUrl, coverHeaders, author)`
  - `data class RemoteBookLink(val href: String, val mimeType: String?, val length: Long?)`
  - `RemoteBookManager.supportsSearch: Boolean`、`search(key)`、`loadNextPage(path)`
  - `RemoteBookOpds(rootUrl, authorization, client, isOnShelf)`

- [ ] **Step 1: 写失败测试**

```kotlin
class RemoteBookOpdsTest {
    @Test fun `maps navigation entry to directory`() = runBlocking {
        val manager = fakeManager(feedWith(navEntry))
        val list = manager.getRemoteBookList("https://host/opds")
        assertTrue(list.first().isDir)
        assertEquals("https://host/opds/category", list.first().path)
    }
    @Test fun `maps book entry to file with all formats`() = runBlocking {
        val book = fakeManager(feedWith(bookEntry)).getRemoteBookList("x").first()
        assertEquals("示例 书名", book.filename)
        assertEquals("EPUB", book.contentType)
        assertEquals(2, book.links.size)
        assertEquals("示例作者", book.author)
    }
    @Test fun `marks on shelf when any candidate name matches`() {
        // isOnShelf 对 "示例 书名.pdf" 返回 true
        assertTrue(fakeManager(feedWith(bookEntry)) { it == "示例 书名.pdf" }
            .getRemoteBookList("x").first().isOnBookShelf)
    }
    @Test fun `next page returns next url entries`() = runBlocking {
        // 第一次 feed 的 rel=next 指向 page2
        assertEquals("第二页书名", manager.loadNextPage("x").first().filename)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.model.remote.RemoteBookOpdsTest"`
Expected: 编译失败。

- [ ] **Step 3: 扩展 RemoteBook 与 Manager**

`RemoteBook` 增加四个带默认值的字段（`links`、`coverUrl`、`coverHeaders`、`author`），WebDAV 构造不变。`RemoteBookManager` 增加 open 成员，默认实现为空/不支持，WebDAV 不需要改。

- [ ] **Step 4: 实现 RemoteBookOpds**

```kotlin
class RemoteBookOpds(
    private val rootUrl: String,
    private val authorization: Authorization,
    private val client: OpdsClient = OpdsClient(),
    private val isOnShelf: (String) -> Boolean = { LocalBook.isOnBookShelf(it) },
) : RemoteBookManager()
```

- `getRemoteBookList(path)`：`client.getFeed(path, auth)`；记录 `nextUrls[path] = feed.nextUrl`；映射 entry。
- `loadNextPage(path)`：取 `nextUrls[path]`，请求后更新，返回映射结果；没有 next 返回空。
- `search(key)`：先请求 `OpdsUrl.search(rootUrl, key)`（路径形式）；若抛 `NOT_FOUND`，回退一次 `"${rootUrl}/search?query=..."`。请求成功后记录 next。
- `downloadRemoteBook`：`client.download(remoteBook.path, auth)` → `LocalBook.saveBookFile(stream, fileName ?: fallbackName(remoteBook))`。
- `upload`/`delete`：抛 `UnsupportedOperationException("OPDS 不支持此操作")`。
- `OpdsClient` 是 open class、方法为 open，测试用匿名子类返回固定 feed，不启网络。
- `toRemoteBook`：
  - 目录：`isDir = true`，`path = navigationUrl`，`filename = title`。
  - 书籍：首选格式按 EPUB → MOBI → AZW3 → PDF → TXT → 其它；`path = 首选 href`，`contentType = 首选 label/扩展名`，`links = 全部`。
  - 候选文件名：链接 href 文件名、`title + "." + 扩展名`、清洗后的名称，任一 `isOnShelf` 即标记。
- 中文书名清洗复用 `Book.getExportFileName` 的规则；不要引入新的正则。

- [ ] **Step 5: 运行测试**

Run: `gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.model.remote.RemoteBookOpdsTest"`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/io/legado/app/model/remote app/src/test/java/io/legado/app/model/remote/RemoteBookOpdsTest.kt
git commit -m "feat(opds): 实现 RemoteBookOpds 目录与下载"
```

---

### Task 6: ViewModel 按服务器类型创建 manager

**Files:**
- Modify: `app/src/main/java/io/legado/app/ui/book/import/remote/RemoteBookViewModel.kt`
- Modify: `app/src/main/java/io/legado/app/model/localBook/LocalBook.kt`（仅在必要时暴露候选文件名判断）
- Create: `app/src/test/java/io/legado/app/ui/book/import/remote/RemoteBookViewModelContractTest.kt`

**Interfaces:**
- Consumes: `RemoteBookWebDav`、`RemoteBookOpds`、`Server.getOpdsConfig()`。
- Produces:
  - `RemoteBookViewModel.manager: RemoteBookManager?`
  - `RemoteBookViewModel.isOpds: Boolean`
  - `RemoteBookViewModel.search(key: String)`
  - `RemoteBookViewModel.loadMore()`

- [ ] **Step 1: 写失败契约测试**

沿用项目现有“读源码断言”的方式：

```kotlin
class RemoteBookViewModelContractTest {
    private val source = readProjectFile(
        "src/main/java/io/legado/app/ui/book/import/remote/RemoteBookViewModel.kt"
    )
    @Test fun `creates opds manager for opds server`() {
        assertTrue(source.contains("RemoteBookOpds("))
        assertTrue(source.contains("getOpdsConfig()"))
    }
    @Test fun `opds does not write webdav origin`() {
        val block = source.substringAfter("fun addToBookshelf(")
            .substringBefore("fun updateCallBackFlow")
        assertTrue(block.contains("if (manager is RemoteBookWebDav)"))
    }
    @Test fun `opds sorting keeps server order`() {
        assertTrue(source.contains("if (isOpds) list else"))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.ui.book.import.remote.RemoteBookViewModelContractTest"`
Expected: FAIL。

- [ ] **Step 3: 改造 ViewModel**

- 把 `remoteBookWebDav` 换成 `manager: RemoteBookManager?`，`isOpds` 标志。
- `initData`：
  ```kotlin
  appDb.serverDao.get(AppConfig.remoteServerId)?.let { server ->
      when (server.type) {
          Server.TYPE.OPDS -> server.getOpdsConfig()?.let {
              manager = RemoteBookOpds(
                  OpdsUrl.normalize(it.url),
                  Authorization(it.username, it.password, StandardCharsets.UTF_8)
              )
              isOpds = true
          }
          else -> server.getWebDavConfig()?.let {
              manager = RemoteBookWebDav(it.url, Authorization(it), server.id)
          }
      }
  }
  ```
  默认 WebDAV 分支保持原样。
- `dataFlow` 排序：`if (isOpds) list else <现有排序>`。
- `addToBookshelf`：只有 `manager is RemoteBookWebDav` 时写 `webDavTag`；OPDS 不覆盖 origin。
- `loadMore` / `search`：调用 manager 的对应方法，走 `dataCallback.addItems` / `setItems`。
- `initData` 失败时保留现有 toast。

- [ ] **Step 4: 运行契约测试与现有回归**

Run: `gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.ui.book.import.remote.RemoteBookViewModelContractTest" --tests "io.legado.app.ui.book.import.ImportSelectionCountResetTest" --tests "io.legado.app.model.remote.RemoteBookWebDavUploadTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/io/legado/app/ui/book/import/remote/RemoteBookViewModel.kt \
  app/src/test/java/io/legado/app/ui/book/import/remote/RemoteBookViewModelContractTest.kt
git commit -m "feat(opds): 按服务器类型创建远程书库管理器"
```

---

### Task 7: 列表显示封面、作者与格式

**Files:**
- Modify: `app/src/main/java/io/legado/app/ui/book/import/remote/RemoteBookAdapter.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/import/remote/RemoteBookActivity.kt`
- Modify: `app/src/main/res/layout/item_import_book.xml`（仅在复用 `ivIcon` 无法显示封面时）

**Interfaces:**
- Consumes: `RemoteBook.coverUrl`、`RemoteBook.coverHeaders`、`RemoteBook.author`。
- Produces: 无新公开接口。

- [ ] **Step 1: 改布局与 Adapter**

`item_import_book.xml` 在现有 FrameLayout 内增加 `iv_cover`（`60dp × 60dp`、`scaleType="centerCrop"`、默认 `gone`、不加 `tint`）；`iv_icon` 保留给文件夹和占位图标，因为它的 `app:tint` 会把封面染色。

Adapter：

- 有 `coverUrl`：`ivCover.visible()`、`ivIcon.gone()`，`Glide.with(context).load(GlideUrl(coverUrl, GlideHeaders(headers))).into(ivCover)`。
- 没有封面：`ivCover.gone()`，保持现有文件夹/书本图标逻辑。
- `coverHeaders` 由 `RemoteBook` 的字段提供，OPDS 写入 `Authorization`。
- `tvTag.text = contentType`；作者放在 `tvDate`（该 TextView 有 `ellipsize`），没有作者时仍显示日期。

- [ ] **Step 2: 改路径栏**

`RemoteBookActivity.upPath()`：

- OPDS：`tvPath.text = dirList.joinToString(" / ") { it.filename }`；搜索态显示 `"搜索：$key"`。
- WebDAV：保持现有 `"books" + File.separator` 逻辑。

错误态：

- `RemoteBookViewModel` 增加 `errorLiveData: MutableLiveData<String>`，加载失败时 post 文案，并保留当前列表。
- Activity observe 后：`tvEmptyMsg.text = message; tvEmptyMsg.visible()`；点击 `tvEmptyMsg` 重新调用 `upPath()`。
- 成功加载后清空错误态。

- [ ] **Step 3: 手工验收**

连接 `https://calibre.liaocl.top`，进入「分类 → 三 → 三国」，确认列表出现封面、书名、`TXT · 作者`；封面加载失败时不崩溃。

- [ ] **Step 4: 跑回归**

Run: `gradlew.bat :app:testAppDebugUnitTest --tests "io.legado.app.ui.book.import.*"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/io/legado/app/ui/book/import/remote/RemoteBookAdapter.kt \
  app/src/main/java/io/legado/app/ui/book/import/remote/RemoteBookActivity.kt \
  app/src/main/res/layout/item_import_book.xml
git commit -m "feat(opds): 列表显示封面、作者与格式"
```

---

### Task 8: 服务端搜索与分页

**Files:**
- Modify: `app/src/main/java/io/legado/app/ui/book/import/BaseImportBookActivity.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/import/remote/RemoteBookActivity.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/import/remote/RemoteBookViewModel.kt`

**Interfaces:**
- Consumes: `RemoteBookViewModel.search`、`loadMore`。
- Produces:
  - `BaseImportBookActivity.open fun onSearchTextSubmit(newText: String?)`
  - `RemoteBookViewModel.exitSearch()`

- [ ] **Step 1: 加搜索提交钩子**

`BaseImportBookActivity.initSearchView`：

```kotlin
override fun onQueryTextSubmit(query: String?): Boolean {
    onSearchTextSubmit(query)
    return false
}
open fun onSearchTextSubmit(newText: String?) {}
```

- [ ] **Step 2: OPDS 搜索**

`RemoteBookActivity`：

- `onSearchTextSubmit`：`if (viewModel.isOpds && !query.isNullOrBlank()) viewModel.search(query)`。
- `onSearchTextChange`：OPDS 下不调用 `updateCallBackFlow`；WebDAV 保持原行为。
- 返回键：`if (viewModel.isSearching) viewModel.exitSearch() else if (!goBackDir()) finish()`。
- 搜索态路径栏由 Task 7 的 `tvPath` 逻辑处理。

`RemoteBookViewModel`：

- `isSearching`、`searchKey`。
- `search(key)`：调用 `manager.search(key)`，`dataCallback.setItems`。
- `exitSearch()`：清空标志，`loadRemoteBookList(currentPath)`。
- `loadMore()`：`if (!isOpds || isLoadingMore || nextPage == null) return`；成功后 `addItems`；失败只提示一次。

- [ ] **Step 3: 滚动分页**

`RemoteBookActivity` 给 `binding.recyclerView` 加 `OnScrollListener`：当 `lastVisible >= itemCount - 5` 且 `!isLoadingMore` 时调用 `viewModel.loadMore()`。

`loadMore` 内部用 `AtomicBoolean`/协程标志防重入；`upPath()` 和 `search()` 重置分页状态。契约测试补两条断言：

```kotlin
assertTrue(source.contains("var isLoadingMore"))
assertTrue(source.contains("if (isLoadingMore) return"))
```

- [ ] **Step 4: 手工验收**

- 最新列表滚动到底，追加第二页，不出现重复项。
- 搜索“三体”，结果正确；返回后回到原分类。
- 搜索“刘慈欣”，能命中作者的书（calibre-web 搜索覆盖作者）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/io/legado/app/ui/book/import/BaseImportBookActivity.kt \
  app/src/main/java/io/legado/app/ui/book/import/remote/RemoteBookActivity.kt \
  app/src/main/java/io/legado/app/ui/book/import/remote/RemoteBookViewModel.kt
git commit -m "feat(opds): 支持服务端搜索与分页加载"
```

---

### Task 9: 多格式选择与下载

**Files:**
- Modify: `app/src/main/java/io/legado/app/ui/book/import/remote/RemoteBookViewModel.kt`
- Modify: `app/src/main/java/io/legado/app/ui/book/import/remote/RemoteBookActivity.kt`
- Modify: `app/src/main/java/io/legado/app/model/remote/RemoteBookOpds.kt`

**Interfaces:**
- Consumes: `RemoteBook.links`。
- Produces:
  - `RemoteBookViewModel.pendingFormatChoice: LiveData<List<RemoteBook>>`
  - `RemoteBookViewModel.addToBookshelf(remoteBooks: HashSet<RemoteBook>, forcedFormat: String? = null, finally: () -> Unit)`
  - `RemoteBookOpds.formats(remoteBook: RemoteBook): List<String>`

- [ ] **Step 1: 格式选择流程**

`onClickSelectBarMainAction`：

1. 若 `isOpds` 且存在 `links.size > 1` 且未指定格式 → `viewModel.requestFormatChoice(adapter.selected)`。
2. Activity observe `pendingFormatChoice`，用 `selector(R.string.select_format, formats)` 弹一次。
3. 用户选择后调用 `viewModel.addToBookshelf(selected, format) { ... }`。
4. 单格式或 WebDAV 直接走原有下载。

- [ ] **Step 2: 下载时替换 path**

`addToBookshelf` 对每本书：若 `forcedFormat != null`，取 `links.first { it.label == forcedFormat }`，用 `remoteBook.copy(path = link.href, contentType = forcedFormat)` 下载；失败计入 `failed`。

批量串行，`finally` 里统一 toast：`成功 N 本，失败 M 本`。权限失败（`OpdsError.FORBIDDEN`）单独计数并在文案中说明。

取消：ViewModel 持有下载协程；用户取消时停止队列中的后续项，当前项完成或失败后不再继续，并删除当前项未导入的临时文件。第一版不强制中断正在写入的文件。

- [ ] **Step 3: 已存在提示**

`RemoteBook.isOnBookShelf` 为 true 时，点击不进入选择；长按保留现有“重新加入书架”提示。

- [ ] **Step 4: 手工验收**

- 下载一本只有 TXT 的书，文件名来自 `Content-Disposition`，导入成功后列表标记“已入书架”。
- 下载一本有多格式的书，弹一次格式选择。
- 批量选择 3 本（含多格式），只弹一次格式选择，串行完成。
- 在 calibre-web 关闭匿名下载后，下载提示“没有下载权限”，不影响浏览。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/io/legado/app/ui/book/import/remote \
  app/src/main/java/io/legado/app/model/remote/RemoteBookOpds.kt
git commit -m "feat(opds): 支持多格式选择与批量下载"
```

---

### Task 10: 文案、备份与完整回归

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Verify: `app/src/main/java/io/legado/app/help/storage/Backup.kt`（`remoteServerId` 已在备份列表，无需改动）
- Verify: `app/src/main/java/io/legado/app/ui/main/bookshelf/*`（菜单文案）

- [ ] **Step 1: 补齐文案**

新增/确认：网络书库、OPDS 地址、连接成功、认证失败、没有下载权限、地址不是 OPDS、返回内容不是 OPDS、此分类没有书籍、没有找到匹配的书、选择下载格式、下载失败、批量下载结果。

英文与中文都要有；其它语言可后续补。

- [ ] **Step 2: 检查备份**

确认 `Backup.kt` 的 `PreferKey.remoteServerId` 仍在列表；`servers` 表本身已在备份范围，不需要新增字段。

- [ ] **Step 3: 跑全量单测**

Run: `gradlew.bat :app:testAppReleaseUnitTest`
Expected: PASS。

- [ ] **Step 4: 手工回归 WebDAV**

- 选择一个 WebDAV 服务器，浏览、下载、删除、重新上传正常。
- 在 WebDAV 与 OPDS 之间切换，返回后进入正确类型。
- 书架菜单进入网络书库不崩溃。

- [ ] **Step 5: 手工回归 OPDS**

`https://calibre.liaocl.top`：

- 根 → Recently added → 滚动两页。
- 根 → Categories → 三 → 三国 → 下载 TXT。
- 根 → Alphabetical Books → 三 → 下载 EPUB。
- 搜索“三体”“刘慈欣”。
- 错误密码 → 认证失败；关闭 OPDS → 地址不是 OPDS。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml
git commit -m "feat(opds): 补齐网络书库文案"
```

---

## 自检记录

- 规格覆盖：Server 类型与配置（Task 1）、地址规范化（Task 2）、feed 解析（Task 3）、认证与错误（Task 4）、浏览与下载（Task 5）、ViewModel 分层（Task 6）、UI（Task 7）、搜索分页（Task 8）、多格式（Task 9）、文案回归（Task 10）。
- 明确不做：在线阅读、进度回写、上传/删除/编辑、OPDS 2.0、作者入口抓取。
- 类型一致性：`OpdsError`、`OpdsFeed`、`OpdsEntry`、`OpdsLink`、`RemoteBookLink` 在 Task 3/4/5 中名称一致。
