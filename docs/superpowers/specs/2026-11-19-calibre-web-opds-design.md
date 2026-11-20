# Legado 对接 Calibre-Web / OPDS 书库设计

- 日期：2026-11-19
- 状态：待确认
- 范围：Legado Android 客户端；OPDS 1.2（Atom）服务端，calibre-web 为主要目标

## 1. 背景与目标

用户自建 calibre-web，个人书库约几千本，日常习惯按分类、作者逐级翻找，而不是只知道书名再去搜。希望 Legado 能直接连接这个书库：填写域名、用户名、密码，就能在书库里逐级浏览，选中书籍下载到本地书架，用 Legado 的阅读、替换规则、TTS 等现有能力阅读。

calibre-web 原生提供 OPDS 1.2 目录服务，认证方式为 HTTP Basic，账号密码与 Web 界面一致。因此不需要针对 calibre-web 写私有接口，按标准 OPDS 实现即可。

### 成功标准

1. 在服务器管理中新增 OPDS 类型，只需填地址、用户名、密码；保存时可测试连接并给出明确结果。
2. 从书架菜单进入「网络书库」，按服务端根 feed 实际提供的入口逐级浏览。当前实例提供「Alphabetical Books / Recently added Books / Categories / Shelves」，不提供 Authors 入口；客户端不得假设某个入口一定存在。
3. 任意层级的列表滚动到底部可加载下一页。
4. 点书籍可选择一个可用格式下载，下载完成后自动导入本地书架；已下载的书在列表中标记。
5. 支持服务端搜索（`/opds/search/...`），搜索结果同样可分页；作者维度通过搜索兜底。
6. 401、403、404、非 OPDS 响应、超时都有可理解的中文提示，不出现原始异常堆栈。
7. 现有 WebDAV 远程书籍流程行为不变。

### 非目标

- 不在线阅读 calibre-web 的网页阅读器（`/read/<id>`）；只下载文件。
- 不回写阅读进度、书签、批注到 calibre-web。
- 不实现上传、删除、编辑元数据、格式转换、Send to Kindle。
- 不实现 calibre 桌面版 Content Server 的专有 API；只走标准 OPDS。
- 不实现 OPDS 2.0（JSON）；只支持 OPDS 1.2（Atom）。
- 不做整库离线缓存或后台同步；目录按需加载。
- 不做封面墙、书架视图；第一版是带封面的列表。
- 不做自定义 CA / 自签证书导入；第一版要求服务端证书受系统信任。
- 不做 calibre-web 的用户注册、多用户切换。

## 2. 用户已确认的决策

| 主题 | 决策 |
| --- | --- |
| 服务端 | 自建 calibre-web，个人书库 |
| 规模 | 约几千本 |
| 主路径 | 按分类、作者逐级浏览，搜索只是兜底 |
| 连接方式 | 域名（可含端口/路径）+ 用户名 + 密码 |
| 结果 | 下载到本地书架，之后按本地书阅读 |
| 认证 | HTTP Basic，与 calibre-web Web 账号一致 |
| 测试实例 | `https://calibre.liaocl.top`，OPDS 当前允许匿名浏览与下载 |
| 作者维度 | 该实例没有 Authors 入口（`/opds/author` 404）；接受现状：按分类/书名/最新/书架浏览，找作者时用搜索 |

## 3. 约束

- minSdk 23，遵循项目现有协程、ViewBinding、`EventBus` 约定。
- 复用现有「远程书籍」浏览界面与服务器管理界面，不新造一套导航。
- `Server` 表结构不新增列，只在 `Server.TYPE` 中增加 `OPDS`；`config` 继续存 JSON。因此不需要数据库版本迁移，也不需要新增 schema。
- OPDS 目录层级不固定（calibre-web 常见 2–3 级），浏览器必须支持任意深度，不能假设只有两级。
- 一个书籍 entry 可能同时有 EPUB、AZW3、MOBI、PDF 等多个 acquisition 链接，需要让用户选择。
- calibre-web 需要在「管理 → 基本配置 → 功能配置」中开启 OPDS，且账号具备下载权限，否则只能浏览不能下载。
- HTTP Basic 凭据会随每个请求发送；必须提示用户优先使用 HTTPS 或可信局域网。
- 反向代理把 `/opds` 301 到 `/opds/` 时可能丢失 `Authorization`，需要在错误提示中给出这一排查方向。
- calibre-web 0.6.24 存在 OPDS Basic Auth 回归问题（服务端 bug），错误提示中说明「可先升级或检查服务端日志」。
- 不同 calibre-web 部署的 OPDS 根入口不同（版本、配置、模板都会影响）。客户端必须按 feed 渲染，不硬编码 `/opds/author`、`/opds/series` 等路径；当前实例的 `/opds/author` 返回 404。

## 4. 服务端协议

### 4.1 入口与认证

- 用户可填 `http://host:8083`、`https://host/calibre`、`http://host:8083/opds` 等形式。
- 规范化规则：
  - 去掉首尾空白与末尾 `/`；
  - 若路径已以 `/opds` 结尾，原样使用；
  - 否则拼接 `/opds`。
- 请求头：
  ```text
  Authorization: Basic base64(username:password)
  Accept: application/atom+xml,application/xml;q=0.9,*/*;q=0.8
  ```
- 凭据使用 UTF-8 编码（calibre-web 允许非 ASCII 用户名/密码）。复用 `okhttp3.Credentials.basic(username, password, UTF_8)`。
- 连接测试：GET 规范化后的入口，要求 2xx 且响应体包含 `<feed`（或 `content-type` 含 `atom+xml`）。

### 4.2 feed 结构

响应为 Atom XML：

- 根 `feed` 下是若干 `entry`；
- 分页链接在 `feed/link[rel=next]`；
- 每个 `entry` 至少包含 `title`、`id`、`updated`；
- 导航 entry：
  - 二级及更深 feed 通常带 `link[rel=subsection]`；
  - 根 feed 可能**没有** `rel`，只有 `link[type="application/atom+xml;profile=opds-catalog"]`；
  - 两种都要识别。
- 书籍 entry：
  - 带 `link[rel^=http://opds-spec.org/acquisition]`，`type` 为具体 MIME；
  - 可能带 `link[rel=http://opds-spec.org/image]`、`.../image/thumbnail`；
  - 可能带 `dc:language`、`dc:issued`、`author/name`、`category`。

判断顺序：

1. 有 acquisition 链接 → 书籍；
2. 有 `link[rel=subsection]` → 目录；
3. 有 `link[type*=atom+xml]` 且没有 acquisition → 目录（覆盖根 feed）；
4. 其它 → 忽略。

### 4.2.1 当前实例的结构要点

已用 `https://calibre.liaocl.top` 验证：

- 根 feed 的 4 个入口都没有 `rel="subsection"`：
  ```xml
  <entry>
    <title>Categories</title>
    <link href="/opds/category" type="application/atom+xml;profile=opds-catalog"/>
    <id>/opds/category</id>
  </entry>
  ```
- 二级 feed（`/opds/books`、`/opds/category`）的字母入口带 `rel="subsection"`。
- 书籍 entry 的 `id` 是 `urn:uuid:...`，**不是 URL**，不能当路径或下载地址。
- 每页 60 条，由服务端配置决定，客户端不写死。
- 分页链接形如 `/opds/new?offset=60`。
- 书籍可能只有 TXT，也可能只有 EPUB，或多格式；格式标签在 acquisition 的 `title` 属性（`TXT`、`EPUB`）。
- 下载响应带 `Content-Disposition: attachment; filename*=UTF-8''...`，可直接用于本地文件名。

### 4.3 分页

- 使用 `feed/link[rel=next]` 的 `href`。
- 相对地址以当前 feed 的最终 URL 为 base 解析。
- 列表滚动到接近底部时自动加载；加载中不重复请求；没有 next 时停止。
- 下拉刷新只重置到第一页。

### 4.4 搜索

- 默认使用路径形式：`{opdsRoot}/search/{query}`，query 做 URL 编码。
- 若服务端返回 404，回退一次 `{opdsRoot}/search?query={query}`。
- 搜索结果按普通 feed 处理，同样支持分页。
- 搜索状态与浏览栈分开：退出搜索回到进入搜索前的目录，不污染 `dirList`。

### 4.5 封面

- 优先 `rel=http://opds-spec.org/image/thumbnail`，其次 `rel=http://opds-spec.org/image`。
- 封面请求同样需要 Basic Auth，使用 `GlideUrl(url, GlideHeaders(mapOf("Authorization" to ...)))`。
- 加载失败显示项目现有封面占位，不阻塞列表。

### 4.6 下载

- 从书籍 entry 的 acquisition 链接中选择：
  1. `application/epub+zip`
  2. `application/x-mobipocket-ebook`
  3. `application/vnd.amazon.ebook`
  4. `application/pdf`
  5. `text/plain`
  6. 其它扩展名在 `AppPattern.bookFileRegex` / `archiveFileRegex` 内的格式
- 若只有一个可选格式，直接下载；多个时弹出格式选择，默认选 EPUB。
- 第一版不记忆上次选择，也不为此新增配置项。
- 批量下载时，若多本书存在多格式，只弹一次「统一使用哪种格式」；单格式书直接下载。
- 下载请求带同一个 `Authorization` 头，处理 302（OkHttp 同 host 会保留头；跨 host 重定向需重新加）。
- 文件名优先级：`Content-Disposition` 的 `filename*`（RFC 5987）→ `filename` → 链接 `href` 的文件名 → `title + "." + 扩展名`；再做与现有 WebDAV 下载一致的文件名清洗与长度截断。
- 下载完成后走 `LocalBook.importFiles(uri)`，与 `RemoteBookWebDav.downloadRemoteBook` 一致。
- 未设置 `defaultBookTreeUri` 时，复用现有「选择书籍目录」提示流程。

## 5. 总体架构

```text
BookshelfFragment（网络书库菜单）
        │
        ▼
RemoteBookActivity
        │  RemoteBookViewModel
        ▼
RemoteBookManager（抽象）
   ├── RemoteBookWebDav（现有）
   └── RemoteBookOpds（新增）
          ├── OpdsClient       HTTP + Basic Auth + 重定向/错误
          ├── OpdsFeedParser   Atom XML → OpdsFeed
          └── OpdsUrl         入口规范化、相对地址解析
```

`RemoteBookViewModel` 目前直接持有 `RemoteBookWebDav`，需要改为持有 `RemoteBookManager`，并在初始化时按 `Server.type` 选择实现。这是本次改动里唯一涉及现有 WebDAV 路径的重构，必须保持其行为不变。

### 5.1 新增/调整的组件

| 组件 | 职责 |
| --- | --- |
| `Server.TYPE.OPDS` | 服务器类型 |
| `Server.OpdsConfig` | `url`、`username`、`password` |
| `RemoteBookOpds` | 实现 `RemoteBookManager`，把 OPDS feed 映射为 `RemoteBook` |
| `OpdsClient` | 取 feed、下载文件、错误映射、认证头 |
| `OpdsFeedParser` | 用 jsoup XML 解析 Atom feed |
| `OpdsUrl` | 入口规范化、相对链接解析、搜索 URL 构造 |
| `OpdsModels` | `OpdsFeed`、`OpdsEntry`、`OpdsLink` |

## 6. 数据模型

### 6.1 Server

```kotlin
enum class TYPE { WEBDAV, OPDS }

@Parcelize
data class OpdsConfig(
    var url: String,
    var username: String,
    var password: String
) : Parcelable
```

- `Server.getOpdsConfig()` 与 `getWebDavConfig()` 并列。
- 不新增数据库列，不升级数据库版本，不新增 schema 文件。

### 6.2 RemoteBook 扩展

现有：

```kotlin
data class RemoteBook(
    val filename: String,
    val path: String,
    val size: Long,
    val lastModify: Long,
    var contentType: String = "folder",
    var isOnBookShelf: Boolean = false
)
```

新增可选字段：

```kotlin
val links: List<RemoteBookLink> = emptyList(),
val coverUrl: String? = null,
val author: String? = null,
```

```kotlin
data class RemoteBookLink(
    val href: String,
    val mimeType: String?,
    val length: Long?,
)
```

- WebDAV 构造时 `links` 为空，行为不变。
- OPDS 目录：`contentType = "folder"`，`path` 为该目录 feed 的绝对 URL。
- OPDS 书籍：`contentType` 为首选格式扩展名，`path` 为首选 acquisition 链接的绝对 URL（可直接下载），`links` 保留全部格式。
- 不使用 entry 的 `id` 作为路径：calibre-web 的书籍 `id` 是 `urn:uuid:...`，不是可访问地址。

### 6.3 已入书架判断

- 不使用单一「书名.首选扩展名」匹配，而是为 entry 生成一组候选文件名：
  1. 每个 acquisition 链接 `href` 的文件名；
  2. `title + 各格式扩展名`；
  3. 经过与下载时相同清洗规则处理后的上述名称。
- 用 `BookDao.hasFile(name)` 逐个检查，任一命中即标记为已入书架。
- 只做文件名近似匹配，不做 calibre id 映射（需要下载后才能确认）。
- 若用户曾下载 PDF、现在列表首选 EPUB，仍能正确标记；标题含 `:`、`/` 等字符时用清洗后的名称匹配。

### 6.4 导入后的 Book.origin

- OPDS 下载的书按本地书处理：`RemoteBookViewModel.addToBookshelf` 只在服务器类型为 WEBDAV 时写 `BookType.webDavTag`；OPDS 分支不覆盖 `origin`，保留 `LocalBook.importFiles` 生成的 `BookType.localTag`。
- 原因：OPDS 的 entry `id` 不是可直接下载的 URL。若写成 `webDavTag`，`WebDav.fromPath` 会读取 `serverID` 并调用 `Server.getWebDavConfig()`，对 OPDS 服务器返回 null，导致「刷新书籍」「跨设备补下载」直接抛 `WebDavException`。
- 因此 OPDS 书第一版不支持「从源刷新」「跨设备自动补下载」；需要重新获取时回书库重新下载。
- 不新增 `BookType.opdsTag`，避免影响 `Book.isLocal`、`BookCacheInfo`、清理与迁移等既有分支。

## 7. 解析与映射

### 7.1 XML 解析

- 使用项目已有 jsoup：`Jsoup.parse(xml, "", Parser.xmlParser())`。
- 选择器按标签名匹配，不依赖命名空间前缀：
  - `feed > entry`
  - `entry > title`
  - `entry > id`
  - `entry > updated`
  - `entry > author > name`
  - `entry > link`
  - `feed > link[rel=next]`
- `link` 的 `rel` 可能为完整 URI，用前缀匹配处理 acquisition。
- `title` 可能含 HTML 实体，依赖 jsoup 解码。
- 根节点不是 `feed`（例如 HTML 登录页、错误页）才抛 `OpdsException("返回内容不是 OPDS 目录")`。
- 合法 `<feed>` 但当前页没有 `entry` 视为空目录，返回空列表，由 UI 显示「此分类没有书籍」。

### 7.2 时间

- `updated` 按 RFC 3339 解析；失败填 `0`，只影响排序显示，不影响功能。

### 7.3 URL

- `OpdsUrl.resolve(base, href)` 使用 `HttpUrl.resolve`，保证相对路径、绝对路径、带 query 的 next 链接都正确。
- 不使用字符串拼接。
- 入口规范化只在保存配置和发起请求时做，不改变用户填写的原值。

### 7.4 缓存

- 第一版只做内存缓存：`feedUrl → OpdsFeed`，容量按页面数限制（例如最近 20 个 feed），返回上级目录时可直接渲染。
- 不做磁盘缓存，避免书架/权限变化后展示过期数据。

## 8. UI 与交互

### 8.1 入口

- 书架菜单现有 `menu_remote`（远程书籍）继续使用，文案改为「网络书库」。
- 进入后：
  - 若 `AppConfig.remoteServerId` 指向有效服务器，直接进入该服务器；
  - 若为 `DEFAULT_WEBDAV_ID`，走现有默认 WebDAV；
  - 若配置无效，提示并打开服务器选择对话框。
- 服务器选择对话框复用 `ServersDialog`，列表中同时显示 WEBDAV 与 OPDS 服务器。

### 8.2 服务器配置

- `ServerConfigDialog` 的 `spType` 增加 `OPDS`，`arrays.xml` 的 `server_type` 增加一项。
- OPDS 与 WEBDAV 使用相同的三个字段：`url`、`username`、`password`；不需要额外字段。
- 菜单增加「测试连接」：
  - 保存前可用当前输入测试；
  - 成功提示「连接成功，共发现 N 个分类」；
  - 失败按第 10 节映射提示。
- 密码输入框保持现有 `password` 类型；保存后再次编辑时显示掩码，不额外回显明文。

### 8.3 浏览页

- 复用 `RemoteBookActivity` 的目录栈（`dirList`）、返回键、路径栏。
- 列表项：
  - 目录：文件夹图标 + 标题；
  - 书籍：封面缩略图 + 书名 + 作者（若有）+ 格式标签 + 已入书架标记。
- 顶部操作：
  - 返回上一级；
  - 搜索；
  - 刷新当前层；
  - 多选下载（沿用现有选择模式）。
- 路径栏显示可读标题栈（例如 `作者 / 刘慈欣`），不显示完整 URL。
- OPDS 下隐藏客户端排序菜单，列表严格按服务端返回顺序展示；分页只追加，不重排已加载项。现有「按名称/按时间」排序只对 WebDAV 生效，避免翻页后列表跳变。
- 空目录显示「此分类没有书籍」。
- 加载失败显示重试按钮，不退出当前层级。

### 8.4 搜索

- OPDS 下搜索框只做服务端搜索：输入过程中不逐字过滤，回车触发一次请求。
- 搜索结果替换当前列表，路径栏显示「搜索：关键词」，滚动到底继续加载下一页。
- 返回键回到搜索前的目录，恢复该目录已加载的列表（滚动位置回顶部即可）。
- 搜索无结果显示「没有找到匹配的书」。
- WebDAV 继续使用现有「输入即过滤」行为，不受影响。

### 8.5 下载

- 交互与现有 WebDAV 远程书籍一致：点列表项切换勾选，底部操作栏执行「加入书架」；单本也先勾选，不引入「点一下立即下载」的第二套手势。
- 执行下载时才解析可用格式：单格式直接下载；多格式弹一次格式选择。
- 多选下载串行执行，单个失败不中断其它；结束后汇总「成功 N 本，失败 M 本」，权限失败单独计数。
- 下载中可取消；取消后删除临时文件。
- 已下载的书再次下载时提示「已存在，是否重新下载」。

## 9. 配置与文案

- `PreferKey` 不新增 key；继续使用 `remoteServerId`。备份/恢复自动覆盖。
- `arrays.xml`：`server_type` 增加 `OPDS`。
- `strings.xml` / `values-zh/strings.xml` 新增：
  - 网络书库
  - OPDS 地址
  - 连接成功/失败
  - 认证失败，请检查用户名和密码
  - 账号没有下载权限
  - 地址不是 OPDS 目录，请确认已开启 OPDS 功能
  - 返回内容不是 OPDS 目录
  - 此分类没有书籍
  - 没有找到匹配的书
  - 选择下载格式
  - 已存在，是否重新下载
- 其余语言可后续补；`MissingTranslation` 已关闭。

## 10. 错误处理与提示

| 情况 | 提示 | 处理 |
| --- | --- | --- |
| 401 | 认证失败，请检查用户名和密码 | 停止重试，保留当前页 |
| 403 | 账号没有下载权限，请在 calibre-web 中授权 | 浏览可继续；不做提前探测，下载时提示并计入批量失败汇总 |
| 404 | 地址不是 OPDS 目录，请确认已开启 OPDS 功能 | 提供「检查地址」入口 |
| 301/302 后 401 | 反向代理可能丢失认证头，请检查代理配置 | 记录最终 URL 便于排查 |
| 响应非 XML | 返回内容不是 OPDS 目录 | 不写入缓存 |
| 超时/断网 | 网络请求超时，请检查地址和网络 | 保留当前页，可重试 |
| 5xx | 书库服务异常（HTTP xxx） | 保留当前页，可重试 |
| XML 解析失败 | 目录格式无法识别 | 记录原始响应到调试日志（不含凭据） |
| 下载失败 | 下载失败：<原因> | 删除临时文件，不影响其它任务 |

提示去重：自动加载失败时同一层级只提示一次；手动刷新每次提示。

## 11. 安全

- 凭据保存在 `servers.config`，与现有 WebDAV 一致（JSON 明文）。这是已知取舍：不为此单独引入加密与迁移。
- 不把 `Authorization`、用户名、密码写入 `AppLog`、HTTP 日志或崩溃信息。
- 封面、feed、下载请求的 URL 日志需要去掉 userinfo（若用户把账号密码写进 URL）。
- 首次保存时在界面上提示：OPDS 使用 HTTP Basic，请优先使用 HTTPS 或仅在可信局域网使用。

## 12. 测试计划

### 12.0 真实样本

已从 `https://calibre.liaocl.top` 取得以下匿名可访问的 feed：

| 样本 | 用途 |
| --- | --- |
| `/opds` | 根入口，验证无 `rel=subsection` 的目录 |
| `/opds/new` | 书籍列表、author、封面、acquisition、分页 |
| `/opds/books` | 字母索引 |
| `/opds/category` | 分类字母索引 |
| `/opds/category/67` | 分类下书籍列表 |
| `/opds/search/三` | 中文搜索、路径编码 |
| `/opds/shelfindex` | 空 feed |

入库前必须脱敏：替换书名、作者、简介、封面 ID 为占位值，只保留结构与标签。不要把用户书库内容提交到仓库。

### 12.1 单元测试

1. `OpdsUrl`
   - `http://host:8083` → `http://host:8083/opds`
   - `http://host:8083/` → `http://host:8083/opds`
   - `http://host/calibre` → `http://host/calibre/opds`
   - 已带 `/opds`、`/opds/` 不重复拼接
   - 相对链接、绝对链接、带 query 的 `next`
2. `OpdsFeedParser`
   - 用真实 calibre-web 根 feed、作者列表、书籍列表、搜索结果样本
   - 导航 entry 与书籍 entry 判断
   - 多格式 acquisition、无 EPUB、只有 PDF
   - `author/name`、`dc:language`、缺失 `updated`
   - 空 `<feed>`、当前页无 entry → 返回空列表；根节点不是 feed、HTML 登录页 → 抛「不是 OPDS」
   - `rel=next` 提取
3. 错误映射
   - 401/403/404/500/超时 → 对应 `OpdsException`
4. `RemoteBook` 映射
   - 目录 → `isDir`
   - 书籍 → `filename`、`contentType`、`links`、`coverUrl`

### 12.2 集成测试

- 用 `ktor-server-test-host`（项目已依赖）起本地 HTTP 服务：
  - 返回固定 Atom XML；
  - 校验 `Authorization` 头；
  - 401、403、404、分页、搜索、下载字节流。
- 真实实例联调（`https://calibre.liaocl.top`，当前匿名可读写，不需要账号）：
  - 根 → 分类字母 → 分类 → 书籍 → 下载一本约 400KB 的 EPUB → 导入书架；
  - 根 → 最新 → 滚动分页（每页 60 条）；
  - 搜索中文书名；
  - 下载 TXT，验证 `Content-Disposition` 的中文文件名；
  - 若临时关闭匿名访问，再验证 401 与错误密码提示。

### 12.3 回归

- WebDAV 远程书籍：选择服务器、浏览目录、下载、删除、上传不受影响。
- `remoteServerId` 在 WEBDAV 与 OPDS 之间切换后，再次进入能进入正确类型。
- 书架菜单、返回键、搜索过滤、多选模式正常。

## 13. 实施顺序

1. **模型与配置**
   - `Server.TYPE.OPDS`、`OpdsConfig`、`arrays.xml`
   - `ServerConfigDialog` 类型分支与测试连接
2. **协议层**
   - `OpdsUrl`、`OpdsFeedParser`、`OpdsClient`
   - 单元测试（先有真实 feed 样本）
3. **浏览层**
   - `RemoteBookManager` 抽象化，`RemoteBookViewModel` 按类型创建
   - `RemoteBookOpds.getRemoteBookList` / `downloadRemoteBook`
   - 分页、内存缓存
4. **UI**
   - 列表封面、作者、格式、已入书架
   - 服务端搜索、空态、错误态
5. **下载与导入**
   - 多格式选择、进度、取消、多选串行、失败汇总
6. **文案、备份检查、回归测试**

## 14. 风险与待验证点

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| calibre-web 不同版本的 feed 字段差异 | 解析失败或分类缺失 | 只用 OPDS 标准字段；用多个版本样本回归 |
| 反代重定向丢 Authorization | 401 且难以自查 | 错误提示给出代理排查方向；记录最终 URL |
| calibre-web 0.6.24 Basic Auth bug | 永远 401 | 提示升级/检查服务端；测试连接区分「401」与「非 OPDS」 |
| 几千本时作者/分类列表很长 | 首屏慢 | 只加载首页，滚动分页；不预抓整棵树 |
| 封面请求量大 | 流量与卡顿 | 只加载可见项缩略图；失败占位；不做预加载 |
| `RemoteBook` 扩展影响 WebDAV | 现有功能回归 | `links`/`coverUrl` 默认空；WebDAV 路径不改逻辑；补回归测试 |
| 账号密码明文存库 | 设备被读取时泄露 | 与现有 WebDAV 一致；UI 提示使用 HTTPS；不在日志输出 |
| 重名书 | 已入书架标记误判 | 第一版按文件名近似判断；后续可用 calibre id 精确匹配 |
| 服务端限制下载次数/并发 | 多选下载失败 | 串行下载；失败汇总；不自动重试多次 |
| OPDS 书误写成 `webDavTag` | 刷新/补下载抛 `WebDavException` | 导入时按服务器类型分支，OPDS 保留 `localTag`；补回归测试 |
| 客户端排序与分页混用 | 翻页后列表跳变 | OPDS 隐藏排序，按服务端顺序追加 |
| 根入口因部署而异 | 硬编码作者/系列入口会 404 | 按 feed 动态渲染，只使用服务端给出的链接 |

## 15. 留给实现者的决定

以下由实现者按代码现状决定，不需要用户再确认：

- `OpdsClient` 用 `okHttpClient` 还是独立 client，以及超时、重试次数。
- 内存缓存的具体数据结构与容量。
- 封面是否使用 `GlideHeaders`，以及缩略图尺寸。
- 多格式选择的交互形式（对话框 / 底部弹层）。
- 下载进度展示形式（通知 / 对话框），与现有导入流程保持一致即可。
- `RemoteBookManager` 接口如何最小化改动以兼容 WebDAV。
- 分页预加载阈值（例如距底部 5 项）。
- 书名与文件名的清洗规则（复用现有 `getExportFileName` 即可）。
- 隐藏排序菜单的具体方式（复用现有菜单做条件隐藏，不新增布局）。
- 批量格式选择弹窗的文案与默认值。
