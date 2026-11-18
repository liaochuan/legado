# Legado 对接 KOReader / CrossPoint Sync 双向进度同步设计

- 日期：2026-11-17
- 状态：待确认
- 范围：Legado Android 客户端；本地 TXT / EPUB；KOReader 兼容同步服务器（当前部署为 crosspoint-sync）

## 1. 背景与目标

Legado 现有阅读进度同步只支持 WebDAV：把 `BookProgress` 序列化为 JSON 写到 `bookProgress/<书名_作者>.json`，并比较章节索引/章节内位置决定上传还是拉取。

用户同时使用 KOReader / CrossPoint 阅读同一批本地 TXT、EPUB 文件，希望 Legado 能接入 KOReader 兼容同步服务器，实现双向续读：

- 在 KOReader / CrossPoint 上读到某处，打开 Legado 能落到同一段/同一屏；
- 在 Legado 上读到某处，打开 KOReader / CrossPoint 能落到同一段/同一屏；
- 只处理本地文件，且两端文件字节完全一致；
- 不做网络书、不做文件名不同的书、不做 PDF/MOBI 等其它格式。

### 成功标准

1. 本地 TXT 和 EPUB 在 Legado 与 KOReader / CrossPoint 之间双向同步，落点误差不超过同一段/同一屏。
2. 两端都开启 WebDAV 和 KOReader 同步时：
   - KOReader 负责拉取和冲突判断；
   - WebDAV 只上传，不参与正常拉取；
   - KOReader 拉取失败时回退一次 WebDAV 拉取，并提示 KOReader 拉取失败。
3. 冲突规则完全按 KOReader：服务器时间戳最新者胜，不再使用“章节更靠前者胜”。
4. 检测到 crosspoint-sync 时，保留启动时批量拉取所有书进度的能力。
5. 原有仅 WebDAV 的行为不变。

## 2. 非目标

- 不支持网络书、在线书源、不同文件副本、文件名匹配模式。
- 不支持 PDF、MOBI、AZW3、UMD、漫画等格式。
- 不实现 Legado 作为 KOSync 服务端。
- 不实现书签、高亮、批注、阅读统计、书籍文件同步；这些继续由 WebDAV 或其它模块负责。
- 不写入 CrossPoint 扩展 `position` 对象；只读取它作为可选辅助信息。Legado 写标准 KOSync 字段，保证任何 KOSync 客户端都能读。
- 不做账号注册流程；用户已在 KOReader / CrossPoint 注册账号并在 Legado 中登录。可提供“测试连接”。

## 3. 约束

- 本地文件必须字节一致；`document` 使用 KOReader 的 partial MD5（二进制模式）。
- KOReader 默认使用 Binary 文档匹配；CrossPoint 也支持 Binary/Filename。Legado 只实现 Binary，文档中明确要求用户在阅读器端选择 Binary。
- Legado 的 `parse` 结果、替换规则、章节切分可能改变页面文本，位置映射必须允许失败并回退。
- 目标设备可能无法 seek `content://` URI；partial MD5 需要 `ParcelFileDescriptor`/`FileChannel`，失败时该书记录错误并跳过同步，不能阻塞其它书。
- 现有 WebDAV 数据模型 `BookProgress` 继续保留；KOReader 进度需要额外的服务端时间戳与文档 ID 缓存。
- 不把 md5 后的 auth key 写入日志。

## 4. 用户已确认的决策

| 主题 | 决策 |
| --- | --- |
| 文件范围 | 仅本地 TXT / EPUB，两端文件字节完全一致 |
| 落点精度 | 同一段 / 同一屏即可 |
| 冲突规则 | 完全按 KOReader：服务器时间戳最新者胜 |
| 同步触发 | 与现有同步保持一致：打开拉取、退出/暂停上传、手动同步 |
| WebDAV + KOReader 同时开启 | KOReader 负责拉取和判断；WebDAV 只上传 |
| KOReader 拉取失败 | 回退到 WebDAV 拉取一次，并提示 KOReader 拉取失败 |
| 服务器 | 自建 crosspoint-sync |
| crosspoint-sync 扩展接口 | 检测到就使用 `/api/v1/progress` 做启动批量拉取 |

## 5. 服务端协议

### 5.1 标准 KOSync 接口

所有请求带：

```text
Accept: application/vnd.koreader.v1+json
x-auth-user: <username>
x-auth-key: <md5(plain password), lowercase hex>
```

- `POST /users/create`：不实现注册，保留给未来。
- `GET /users/auth`：测试连接。
- `PUT /syncs/progress`：

```json
{
  "document": "<32 hex partial md5>",
  "progress": "<KOReader XPointer>",
  "percentage": 0.4867,
  "device": "Legado",
  "device_id": "<AppConst.androidId>"
}
```

- `GET /syncs/progress/{document}`：返回最新进度；不存在返回 `200 {}`，部分实现返回 404。两者都按“没有远端进度”处理。
- 响应中的 `timestamp` 是 Unix 秒，作为冲突判断依据。

crosspoint-sync 的 `PUT /syncs/progress` 成功响应已确认为：

```json
{"document": "a1b2c3d4e5f60718293a4b5c6d7e8f90", "timestamp": 1752345678}
```

`timestamp` 是服务器写入时间，Unix 秒。实现要求：

1. 上传成功后用响应中的 `timestamp` 更新 `book.kosyncTime`。
2. 如果服务器返回 2xx 但没有 `timestamp`（兼容其它实现）：
   - 立即 `GET /syncs/progress/{document}` 一次；
   - 取到 `timestamp` 就写入；
   - 仍取不到时保持 `book.kosyncTime` 不变，并记录一次日志；下一次打开书时以 GET 结果为准，不能把刚刚上传的位置误判为“远端更新”。
3. 不把本地时钟当作 `kosyncTime`，避免多设备时钟漂移。

### 5.2 crosspoint-sync 扩展接口

仅在检测到 crosspoint-sync 时使用：

- `GET /api/v1/progress?limit=...&cursor=...`：列出账号下所有文档的最新进度，用于启动批量拉取。
- `GET /api/v1/progress/{document}`：可选，读取所有设备的记录和 `position`；正常拉取仍优先使用标准 `GET /syncs/progress/{document}`。
- 不调用 `PUT /api/v1/progress`，避免写入语义不匹配的 CrossPoint `position`；统一使用标准 `PUT /syncs/progress`。

`GET /api/v1/progress` 的响应结构已确认：

```json
{
  "items": [
    {
      "document": "25f8abb4f4f5594f02f361726814fea1",
      "title": "Foundryside",
      "author": "Robert Jackson Bennett",
      "filename": "Foundryside - Robert Jackson Bennett.epub",
      "percentage": 0.2853,
      "progress": "/body/DocFragment[16]/body/div[1]/p[143]",
      "device_id": "crosspoint-reader",
      "device": "CrossPoint",
      "timestamp": 1783913361
    }
  ],
  "next": "1783913361:25f8abb4f4f5594f02f361726814fea1"
}
```

分页规则：

- `limit` 默认 100，最大 500；
- 按 `timestamp` 从新到旧排序；
- `next` 为 `null` 表示最后一页；否则把 `next` 原样作为 `cursor` 传回；
- `next` 是不透明字符串，不要解析或自行构造；
- 每个 `document` 一行，已经是该文档所有设备中的最新进度；
- `title`/`author`/`filename` 可能为 `null`，Legado 不依赖它们匹配书籍，只用 `document`。

`GET /api/v1/progress/{document}` 的 `devices` 数组只作为可选增强信息（例如 CrossPoint 的 `position.para`/`spine`），不参与主要冲突判断。

检测方式：

1. 带认证调用 `GET /api/v1/progress?limit=1`；
2. `200` 视为支持扩展接口；
3. `401/403` 视为认证失败，不视为“不支持”；
4. `404`、HTML、其它错误视为普通 KOSync 服务器；
5. 结果按“服务器 URL + auth key”缓存，配置变化后重新检测。

## 6. 总体架构

```text
ReadBook / ReadManga / ViewModel / BookController
                    │
                    ▼
        ProgressSyncCoordinator
        │          │          │
        │          │          └── WebDavProgressProvider（上传；失败回退时拉取）
        │          └───────────── KosyncProgressProvider（标准 KOSync）
        └──────────────────────── PositionMapper
                                   ├── TxtPositionMapper
                                   └── EpubPositionMapper
```

### 6.1 新增/调整的组件

| 组件 | 职责 |
| --- | --- |
| `ProgressSyncProvider` | 进度同步后端接口：`pull(book)`、`push(book, progress)`、`pullAll(books)` |
| `ProgressSyncCoordinator` | 决定使用哪个后端、冲突处理、回退、提示 |
| `WebDavProgressProvider` | 包装现有 `AppWebDav`，保持原行为 |
| `KosyncProgressProvider` | 标准 KOSync + crosspoint-sync 扩展检测/列表 |
| `KosyncClient` | HTTP：auth、GET/PUT progress、扩展列表 |
| `KosyncDocumentId` | KOReader partial MD5 |
| `XPointer` | XPointer 解析/序列化工具 |
| `TxtPositionMapper` | TXT 绝对字节偏移 ↔ XPointer |
| `EpubPositionMapper` | EPUB 章节/位置 ↔ XPointer |
| `KosyncCapabilities` | 扩展接口检测结果缓存 |

### 6.2 后端选择矩阵

设：

- `syncBookProgress`：现有总开关；
- `progressSyncKosync`：KOReader 同步开关；
- `progressSyncWebDav`：WebDAV 进度同步开关，默认 `true`，保持现有用户行为；
- WebDAV 是否可用：`AppWebDav.isOk`。

| `syncBookProgress` | KOReader | WebDAV | 行为 |
| --- | --- | --- | --- |
| 关 | * | * | 不同步 |
| 开 | 关 | 开 | 现有 WebDAV 行为 |
| 开 | 开 | 关 | 只使用 KOReader |
| 开 | 开 | 开 | 正常按 KOReader 拉取/判断；WebDAV 只上传；KOReader 拉取失败时提示并回退 WebDAV 拉取一次 |
| 开 | 开但未配置/认证失败 | 开 | 视为 KOReader 拉取失败，提示后回退 WebDAV |
| 开 | 开但未配置/认证失败 | 关 | 提示失败，使用本地进度，不写远端 |

“KOReader 拉取失败”包括网络错误、超时、401、5xx、响应格式错误。`200 {}` 不算失败，表示远端没有该文档进度。

上表只描述 **KOReader 适用书**（本地 TXT/EPUB 且文档 ID 计算成功）。非适用书（在线书、PDF、MOBI、UMD、漫画等）始终按 WebDAV 原逻辑双向同步，不受 KOReader 开关影响。

已确认：**按书选择后端**。只有“本地 TXT/EPUB 且文档 ID 计算成功”的书使用 KOReader；在线书、PDF、MOBI、UMD、漫画等其它书继续走 WebDAV 双向逻辑。这样 KOReader 开启不会让现有在线书进度同步静默失效。

实现上，协调器入口先判断：

```kotlin
fun isKosyncEligible(book: Book): Boolean =
    book.isLocal && (book.isLocalTxt || book.isEpub)
```

文档 ID 计算失败时，本次按“KOReader 拉取失败”处理：若 WebDAV 可用则回退 WebDAV，否则使用本地进度。

## 7. 文档标识

### 7.1 partial MD5

与 KOReader `util.partialMD5` 一致：

- 偏移：`0, 1024, 4096, 16384, ..., 1073741824`，共 12 个；
- 每个偏移读取最多 1024 字节；
- 读到空/不足时停止；
- 将读到的字节依次拼接到同一个 MD5；
- 输出 32 位小写 hex。

实现：

- 用 `BookHelp.getBookPFD(book)` 获取可 seek 的文件描述符；
- 用 `FileInputStream(fileDescriptor).channel` 按偏移读取；
- 失败或不可 seek 时，该书记录“无法计算文档 ID”，跳过 KOReader 同步；
- 按 `bookUrl + fileSize + lastModified` 做内存缓存；文件变化后失效；
- 不新增数据库字段缓存文档 ID，避免为缓存做迁移；启动批量同步时允许重新计算。

### 7.2 仅 Binary 模式

Legado 不实现 Filename 模式。用户必须在 KOReader / CrossPoint 的 Progress Sync 设置中选择 Binary。文档和 UI 帮助中说明。

## 8. 位置映射

### 8.1 统一内部位置

```kotlin
data class TextSyncPosition(
    val chapterIndex: Int,
    val chapterPos: Int,
    val percentage: Double,
    val paragraphKey: String? = null,
)
```

- `chapterIndex` / `chapterPos` 用于直接调用现有 `ReadBook.setProgress` / `ReadManga.setProgress`。
- `percentage` 用于回退和远端展示。
- `paragraphKey` 是段落级等价键：
  - TXT：该位置所在的行/段落索引；
  - EPUB：该位置所在块级元素在 spine 中的路径（例如 `DocFragment[8]/body/div[2]/p[4]`）；
  - 无法确定时为 `null`。
- “位置相同”的判定：
  1. 两端都有 `paragraphKey`：同一章且 `paragraphKey` 相同即视为相同；
  2. 只有一端有 `paragraphKey`：同一章且 `percentage` 差值小于 `0.001` 视为相同；
  3. 两端都没有：同一章且 `percentage` 差值小于 `0.001` 视为相同。
- “远端在本地后面”的判定：
  - `paragraphKey` 不同且远端 `percentage` 小于本地 `percentage - 0.001`；
  - 或同一章内远端 `chapterPos` 明显小于本地（差值大于一个段落）。
- 阈值集中在一个 `PositionEquality` 对象中，便于测试和调整；不允许在协调器里散落魔法数字。

### 8.2 TXT

Legado 的 TXT 章节有 `chapter.start` / `chapter.end` 字节偏移；`durChapterPos` 是经过 `TextFile.getContent()` 处理后的章节文本字符偏移。

计算绝对字节偏移：

```text
raw = readBytes(chapter.start, chapter.end)
text = decode(raw, charset).replace(padRegex, "　　")
prefixBytes = text.substring(0, durChapterPos).toByteArray(charset).size
absoluteByte = chapter.start + prefixBytes
```

反向映射：

1. 根据 `absoluteByte` 找到 `chapter.start <= absoluteByte < chapter.end` 的章节；
2. 读取章节原始字节并按同样规则处理；
3. 计算偏移对应的字符位置，得到 `durChapterPos`；
4. 若定位失败，用 `percentage * fileSize` 估算。

TXT 的 KOReader `progress` 是 crengine XPointer，不是百分比。`TxtPositionMapper` 负责：

- `toXPointer(absoluteByte)`；
- `fromXPointer(xpointer)`。

实现前必须先做一次真实抓包：

1. 用同一账号在 KOReader / CrossPoint 打开一个测试 TXT；
2. 跳到已知行/段，推送进度；
3. 调用 `GET /syncs/progress/{document}` 记录 `progress`、`percentage`；
4. 对同一位置在 Legado 计算绝对偏移；
5. 建立“行/段索引 → XPointer”的对应关系。

预计形态为 `/body/DocFragment/body/p[N]/text().M`（单 fragment 可能省略索引），但必须以实测为准。若无法稳定生成 XPointer：

- 拉取方向：优先解析 `progress`；失败用 `percentage` 估算绝对偏移，再落到最近段落。
- 上传方向：跳过本次上传并记录日志，不能用无效字符串覆盖 KOReader 的进度；保留本地进度，下次成功再传。

### 8.3 EPUB

#### 拉取（KOReader → Legado）

1. 解析 XPointer，取 `DocFragment[N]`，`N` 为 spine 序号（1-based）。
2. 通过 `EpubFile` 新增的 API 取得第 N 个 spine 资源：
   - `getSpineHrefs(): List<String>`
   - `getSpineIndex(href: String): Int`
   - `getSpineBody(href: String): Element?`
3. 按 XPointer 的步骤在 jsoup DOM 中查找节点：
   - 完全解析成功：取目标元素或文本节点的父块元素；
   - 路径因 crengine 包装节点/索引差异失败：逐级去掉最后一步，取最深的可解析元素，作为段落锚点；
   - 仍失败：使用 `percentage`。
4. 用锚点元素的文本在 Legado 章节中查找：
   - 先按 spine href 过滤章节；
   - 去掉 HTML 标签，得到纯文本；
   - 查找锚点文本（取前 20–40 个字符，去空白）；
   - 多个匹配时选择位置最接近 `percentage` 的那个；
   - 得到该段落在章节文本中的字符偏移，写入 `chapterPos`。
5. 找不到锚点文本：用 `percentage * chapterSize` 估算章节，`chapterPos = 0`，记录警告。

#### 上传（Legado → KOReader）

1. 取当前页第一行非空文本作为锚点：
   - `ReadBook.curTextChapter?.getPage(pageIndex)?.lines`；
   - 去掉首尾空白，取前 20–40 个字符；
   - 页面只有图片时，退化为 `percentage`。
2. 通过当前章节的 `chapter.url` / `startFragmentId` 找到 spine href 和 spine 序号。
3. 从 `EpubFile.getSpineBody(href)` 的原始 DOM 中查找锚点文本：
   - 在文本节点中查找；
   - 找到后向上找最近的块级元素（`p`、`div`、`h1`–`h6`、`li`、`blockquote`、`section` 等）；
   - 生成从 `<body>` 到该元素的 XPointer，步骤使用 1-based 同名兄弟索引；
   - 输出 `/body/DocFragment[N]/body/.../p[k]`。
4. 如果锚点找不到：
   - 不写入无效 XPointer；
   - 记录日志并跳过本次 KOReader 上传；
   - WebDAV 仍按当前进度上传。
5. 如果页面文本被替换规则改写导致匹配失败，允许用当前章节在 spine 中的起始块作为降级锚点；该降级策略需在设置中默认关闭，避免把用户送回章首。

### 8.4 百分比

`percentage` 计算：

- TXT：`absoluteByte / fileSize`；
- EPUB：优先用 `EpubFile` 的 spine/字符位置估算全书的字节或字符比例；没有可靠数据时用 `chapterIndex / totalChapterNum`。

百分比只用于：

- 服务端展示；
- XPointer 解析失败时的回退；
- 启动批量同步时无法解析 XPointer 的兜底。

不把百分比作为上传方向的主要位置，因为 KOReader 拉取可重排文档时只使用 `progress`。

## 9. 同步流程

### 9.1 打开书 / 手动拉取

```text
if (!syncBookProgress) return
if (kosync enabled) {
    try {
        remote = kosync.pull(book)
        if (remote == null) {
            pushLocal(book, toKosync = true, toWebDav = webdavEnabled)
        } else if (remote.timestamp > book.kosyncTime) {
            pos = mapRemote(remote) ?: percentageFallback(remote)
            if (pos == null) {
                log; (可选) 回退 WebDAV
            } else if (!positionsEqual(pos, local)) {
                if (syncBookProgressPlus && isBehind(pos, local)) {
                    promptUser(pos)   // 复用 sureNewProgress
                } else {
                    apply(pos)
                }
            }
            book.kosyncTime = remote.timestamp
            if (webdavEnabled) webdav.push(currentProgress) // 让 WebDAV 跟上
        } else {
            // 本地更新或相同
            if (!positionsEqual(remote, local)) pushKosync(book)
            if (webdavEnabled) webdav.push(local)
        }
    } catch (e) {
        log; toast("KOReader 拉取失败")
        if (webdavEnabled) webdav.pullAndApply(book)
    }
} else if (webdavEnabled) {
    existingWebDavPullAndDecision(book)
}
```

说明：

- `book.kosyncTime` 是新字段，存服务器返回的 Unix 秒；默认 0。
- 远端时间戳更大且位置相同：只更新 `kosyncTime`，不跳转。
- 远端时间戳更大且位置不同：按 KOReader 语义应用远端。
- 远端时间戳更小或相等：认为本地更新，上传本地；不覆盖远端。
- `syncBookProgressPlus` 只用于“远端更新但位置在本地后面”的情况，复用现有 `sureNewProgress` 弹窗，避免用户丢失当前页；非 plus 模式按 KOReader 语义直接应用。
- 应用远端后，WebDAV 上传“应用后的当前进度”，保证 WebDAV 备份不落后。

### 9.2 退出 / 暂停 / 手动上传

沿用现有触发点：

- `ReadBookActivity.onPause()`；
- `ReadBook.syncProgress()`；
- `ReadManga` 对应逻辑；
- `ReadBookActivity` 手动同步菜单；
- `ReadView` 的手动同步动作。

行为：

1. 若 KOReader 启用：`PUT /syncs/progress` 写入当前进度；
2. 若 WebDAV 启用：`AppWebDav.uploadBookProgress` 上传当前进度；
3. 两者都启用时顺序可以是并行，但 KOReader 失败不影响 WebDAV 上传；
4. 上传成功后把服务器返回的 `timestamp` 写入 `book.kosyncTime`。

### 9.3 启动批量拉取

条件：

- `syncBookProgress && progressSyncKosync && KosyncCapabilities.supportsDocumentList`。

流程：

1. 在 IO 协程中调用 `GET /api/v1/progress?limit=500`，按 `cursor` 翻页；
2. 建立 `document -> remote progress` 映射；
3. 遍历本地 TXT/EPUB 书籍（`book.isLocal && (book.isLocalTxt || book.isEpub)`）；
4. 计算每本书的 partial MD5；
5. 若远端存在且 `remote.timestamp > book.kosyncTime`：
   - 映射位置；
   - 应用到 `book` 的 `durChapterIndex` / `durChapterPos` / `durChapterTitle` / `durChapterTime` / `kosyncTime`；
   - `book.update()`；
6. 不在此阶段向 KOReader 推送本地较新的进度，避免启动时大量写请求；
7. 不弹窗；失败只写日志；
8. 如果扩展接口不可用，跳过 KOReader 批量，退回“打开书时拉取”。

与 WebDAV 批量共存：

- 现有 `AppWebDav.downloadAllBookProgress()` 只用于**非 KOReader 书**（在线书、PDF、MOBI、UMD、漫画等）；
- 给 `downloadAllBookProgress` 增加一个 `shouldSync: (Book) -> Boolean` 过滤参数；KOReader 开启时传入 `!isKosyncEligible(book)`；
- 这样启动时两类书各走各的，不会出现 WebDAV 旧进度覆盖 KOReader 新进度；
- KOReader 开启但扩展接口不可用时，非 KOReader 书仍执行 WebDAV 批量，KOReader 书等打开时逐本拉取。

注意：批量拉取不移动当前正在阅读的界面；与现有 `downloadAllBookProgress()` 一样只更新数据库。若当前有打开的书，可跳过该书。

## 10. 配置与 UI

### 10.1 新增配置项

`PreferKey` / `AppConfig`：

| key | 默认 | 说明 |
| --- | --- | --- |
| `progressSyncKosync` | `false` | KOReader 同步开关 |
| `progressSyncWebDav` | `true` | WebDAV 进度同步开关，保持现有行为 |
| `kosyncServerUrl` | 空 | 例如 `http://192.168.1.10:8080` 或 `https://sync.example.com` |
| `kosyncUsername` | 空 | 用户名 |
| `kosyncAuthKey` | 空 | md5 后的密码，不存明文 |
| `kosyncDeviceName` | `Build.MODEL` | 写入 `device` |
| `kosyncBatchOnStartup` | `true` | 检测到扩展接口时启动批量拉取 |

`syncBookProgress` 仍是总开关。

### 10.2 UI

在 `pref_config_backup.xml` 的 WebDAV 分类下新增“KOReader 同步”分类：

- Switch：启用 KOReader 同步
- EditText：服务器地址
- EditText：用户名
- EditText：密码（输入后计算 md5 存储；摘要显示 `******`）
- EditText：设备名称
- Preference：测试连接（调用 `GET /users/auth`，再检测 `/api/v1/progress`）
- Switch：启动时批量同步（仅扩展接口可用时显示/生效）

文案同步：

- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-zh/strings.xml`
- 其余语言可后续补。

### 10.3 设备标识

- `device`：`kosyncDeviceName`，为空时用 `Build.MODEL`；
- `device_id`：`AppConst.androidId`，保证 Legado 在 crosspoint-sync 中有独立的一行，不覆盖 KOReader/CrossPoint 的记录；
- 不把 `device_id` 写进日志。

## 11. 数据模型与存储

### 11.1 Book 新增字段

```kotlin
@ColumnInfo(defaultValue = "0")
var kosyncTime: Long = 0L,
```

- 存服务器返回的 Unix 秒；
- 与 WebDAV 的 `book.syncTime` 分开，避免 WebDAV 回退时语义冲突；
- 数据库版本从 113 升到 114；
- `DatabaseMigrations` 增加：
  ```sql
  ALTER TABLE books ADD COLUMN kosyncTime INTEGER NOT NULL DEFAULT 0
  ```
- 同步提交 `app/schemas/` 下新 schema JSON。

不新增 `kosyncDocument` 字段；文档 ID 用内存缓存，避免为缓存做额外迁移。

### 11.3 服务器/账号变更

`book.kosyncTime` 只对“同一个服务器 + 同一个账号”有意义。新增配置指纹：

```text
kosyncConfigFingerprint = sha256(kosyncServerUrl + "\n" + kosyncUsername + "\n" + kosyncAuthKey)
```

- 指纹保存在 SharedPreferences（`PreferKey.kosyncConfigFingerprint`）。
- UI 保存 KOReader 配置时比较新旧指纹；不同则：
  1. 更新指纹；
  2. 执行 `UPDATE books SET kosyncTime = 0`；
  3. 清空 `KosyncCapabilities` 缓存和文档 ID 内存缓存。
- 这样切换到新服务器后不会用旧时间戳覆盖新服务器上的合法进度；代价是第一次打开书会以远端为准。
- 不要求用户手动清理数据。

### 11.2 传输模型

```kotlin
data class KosyncProgress(
    val document: String,
    val progress: String,
    val percentage: Double,
    val device: String?,
    val deviceId: String?,
    val timestamp: Long,
    val position: KosyncPosition? = null,
)

data class KosyncPosition(
    val pctQ: Long?,
    val spine: Int?,
    val page: Int?,
    val pages: Int?,
    val para: Int?,
    val anchor: String?,
    val xpath: String?,
)
```

`position` 只读取，不写入。

## 12. 文件改动清单

新增：

```text
app/src/main/java/io/legado/app/help/sync/ProgressSyncProvider.kt
app/src/main/java/io/legado/app/help/sync/ProgressSyncCoordinator.kt
app/src/main/java/io/legado/app/help/sync/WebDavProgressProvider.kt
app/src/main/java/io/legado/app/help/sync/KosyncProgressProvider.kt
app/src/main/java/io/legado/app/help/sync/KosyncClient.kt
app/src/main/java/io/legado/app/help/sync/KosyncDocumentId.kt
app/src/main/java/io/legado/app/help/sync/XPointer.kt
app/src/main/java/io/legado/app/help/sync/TxtPositionMapper.kt
app/src/main/java/io/legado/app/help/sync/EpubPositionMapper.kt
```

修改：

```text
app/src/main/java/io/legado/app/data/entities/Book.kt
app/src/main/java/io/legado/app/data/AppDatabase.kt
app/src/main/java/io/legado/app/data/DatabaseMigrations.kt
app/src/main/java/io/legado/app/constant/PreferKey.kt
app/src/main/java/io/legado/app/help/config/AppConfig.kt
app/src/main/java/io/legado/app/help/AppWebDav.kt             仅保留/复用上传逻辑
app/src/main/java/io/legado/app/model/ReadBook.kt
app/src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt
app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt
app/src/main/java/io/legado/app/ui/book/read/page/ReadView.kt
app/src/main/java/io/legado/app/api/controller/BookController.kt
app/src/main/java/io/legado/app/App.kt
app/src/main/java/io/legado/app/model/localBook/EpubFile.kt    暴露 spine / 原始 DOM
app/src/main/java/io/legado/app/model/localBook/TextFile.kt    暴露绝对偏移换算
app/src/main/res/xml/pref_config_backup.xml
app/src/main/java/io/legado/app/ui/config/BackupConfigFragment.kt
app/src/main/res/values/strings.xml
app/src/main/res/values-zh/strings.xml
app/schemas/...
```

`ReadManga.kt` / `ReadMangaViewModel.kt` 不在本设计改动范围：漫画/图片书继续走现有 WebDAV 同步。只有 `ReadBook` 覆盖的本地 TXT/EPUB 接入 KOReader。

## 13. 错误处理与提示

| 情况 | 行为 |
| --- | --- |
| KOReader 未配置/认证失败 | 提示一次；若 WebDAV 启用则回退拉取，否则使用本地 |
| KOReader 网络/超时/5xx | 提示“KOReader 拉取失败，已回退 WebDAV”或“KOReader 拉取失败”；回退逻辑同上 |
| 远端 `200 {}` | 视为无远端进度，上传本地 |
| XPointer 解析失败 | 用 `percentage` 回退；记录日志 |
| XPointer 生成失败 | 跳过 KOReader 上传，仍上传 WebDAV；不写无效进度 |
| partial MD5 失败 | 跳过 KOReader，记录日志；WebDAV 继续 |
| crosspoint-sync 列表失败 | 跳过启动批量；打开书时仍逐本拉取 |
| WebDAV 回退失败 | 使用本地进度，提示同步失败 |
| 401 | 不反复重试；提示用户检查配置 |

提示去重：自动同步时每本书每个 App 会话最多提示一次；手动同步每次提示。

## 14. 测试计划

### 14.1 单元测试

1. `KosyncDocumentId`
   - KOReader 固定向量：
     - `leaves.epub` → `59d481d168cca6267322f150c5f6a2a3`
     - `tall.pdf` → `41cce710f34e5ec21315e19c99821415`
   - 小于 1GiB 的文件、正好 1GiB、空文件、不可 seek。
2. `XPointer`
   - 解析/序列化 round-trip；
   - 单 DocFragment、带索引、`text()[N].M`、元素指针；
   - 路径不存在时的逐级回退。
3. `TxtPositionMapper`
   - UTF-8 / GBK；
   - LF / CRLF；
   - BOM；
   - 空行、缩进、超长行；
   - 绝对偏移 ↔ XPointer 双向。
4. `EpubPositionMapper`
   - 单 spine / 多 spine；
   - 锚点文本在 `<p>`、`<div>`、`<li>`；
   - 重复文本；
   - 替换规则导致匹配失败；
   - XPointer 路径与 crengine 包装差异。
5. `ProgressSyncCoordinator`
   - 只 KOReader；
   - 只 WebDAV；
   - 两者都开；
   - 时间戳新旧；
   - KOReader 失败回退；
   - `syncBookProgressPlus` 弹窗路径；
   - 远端空 `{}`；
   - 401/404/5xx。

### 14.2 集成测试

- 用本地 fake KOSync 服务器（OkHttp MockWebServer 或轻量 `HttpServer`）验证请求头、body、timestamp、401、`{}`。
- 真实 crosspoint-sync：
  - 注册测试账号；
  - Legado 上传 → KOReader/CrossPoint 拉取；
  - KOReader/CrossPoint 上传 → Legado 拉取；
  - 多设备行不互相覆盖；
  - `/api/v1/progress` 列表与启动批量。
- 真实文件：
  - 10 本不同结构 EPUB（单 spine、多 spine、嵌套 div、列表、图片页）；
  - 5 个 TXT（不同编码、换行、段落样式）。

### 14.3 回归

- 仅 WebDAV 的现有行为不变；
- `syncBookProgress` 关闭时无网络请求；
- App 启动不阻塞 UI；
- 数据库迁移 113 → 114 正常。

## 15. 实施顺序

1. **协议与文档 ID**
   - `KosyncClient`、auth、GET/PUT、错误处理；
   - `KosyncDocumentId` + 单元测试；
   - 配置项、测试连接。
2. **协调器与 WebDAV 关系**
   - `ProgressSyncCoordinator`、后端选择、回退、提示；
   - 保持仅 WebDAV 行为不变。
3. **TXT 位置映射**
   - 先做真实 KOReader / CrossPoint 抓包，确认 TXT XPointer 形态；
   - 实现绝对偏移 ↔ XPointer；
   - 接入上传/拉取。
4. **EPUB 位置映射**
   - `EpubFile` 暴露 spine / 原始 DOM；
   - XPointer 解析与逐级回退；
   - 段落锚点生成；
   - 接入上传/拉取。
5. **crosspoint-sync 扩展**
   - 能力检测；
   - 启动批量拉取。
6. **UI、文案、迁移、完整测试。**

## 16. 风险与待验证点

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| TXT 的 crengine XPointer 结构未实测 | 上传方向可能无法生成有效进度 | 实施前用真实设备抓包；无法确认则上传方向跳过并记录，拉取方向用 percentage 回退 |
| EPUB crengine DOM 与原始 XHTML 路径有差异 | 解析/生成可能偏一段 | 逐级回退到可解析元素；用锚点文本匹配；段落级精度已满足需求 |
| 替换规则改写页面文本 | 锚点匹配失败 | 允许跳过 KOReader 上传；WebDAV 仍上传；后续可加“原始文本锚点”缓存 |
| partial MD5 读取 SAF 文件失败 | 该书无法同步 | 捕获异常、记录日志、跳过；不阻塞其它书 |
| crosspoint-sync 扩展接口版本差异 | 启动批量不可用 | 检测失败自动退回标准接口 |
| 时间戳秒级精度、设备时钟 | 冲突判断边界 | 以服务器 timestamp 为准；同一秒冲突时保留本地并记录 |
| 多设备同时写 | 旧进度覆盖新进度 | crosspoint-sync 按设备存行、GET 取最新；Legado 使用独立 device_id |
| WebDAV 回退拉到旧进度 | 覆盖 KOReader 新进度 | 仅在 KOReader 失败时回退；回退后不写 KOReader，等下次成功再推 |

## 17. 留给实现者的决定

以下由实现者按代码现状决定，不需要用户再确认：

- `ProgressSyncProvider` 的具体接口签名、协程调度方式；
- 内存缓存的具体数据结构与失效策略；
- XPointer 解析器的实现细节（是否复用 jsoup、是否引入轻量解析器）；
- 提示文案的具体措辞与去重窗口；
- 测试服务器用 MockWebServer 还是项目内已有测试工具；
- EPUB 块级元素集合的具体范围；
- `percentage` 的精确公式（只要单调、可用于回退即可）。

## 18. 测试环境与已确认事项

### 18.1 已确认

- 测试实例：用户提供的 crosspoint-sync，`GET /healthz` 返回 `0.2.0`，`/api/v1/progress` 可用。
- 账号可登录，初始没有已同步文档。
- 已用临时 document 验证 `PUT /syncs/progress` 返回 `{"document": ..., "timestamp": ...}`，`GET` 返回最新行，`DELETE /api/v1/progress/{document}` 可清理；临时数据已删除。
- 实际阅读客户端：KOReader 和 CrossPoint 都需要能读 Legado 的进度。
- 非本地 TXT/EPUB（在线书、PDF、MOBI、UMD、漫画）继续走 WebDAV 双向；只有本地 TXT/EPUB 且文档 ID 计算成功的书切到 KOReader。

### 18.2 测试文件

当前目录下已放入：

| 文件 | 大小 | 类型 | partial MD5 |
| --- | --- | --- | --- |
| `绍宋 - 榴弹怕水.txt` | 8,109,069 | TXT，GBK，无 BOM | `8c85f772ef139265b52a4ebb7608d495` |
| `快乐的死 - 阿尔贝·加缪.epub` | 2,173,109 | EPUB，13 个 spine item | `dd6779917d581702bd48fe5ad2af90cd` |

说明：

- 这两个哈希由 PowerShell 和 Node 两套独立实现计算并互相对齐，后续再用 App 实现交叉校验。
- TXT 是 GBK，测试必须覆盖非 UTF-8。
- EPUB 有 13 个 spine item，适合验证 `DocFragment[N]`。

### 18.3 已获得的真实进度样本

服务器上已有两条来自 Readest (Windows) 的进度：

| 文件 | document | progress | percentage |
| --- | --- | --- | --- |
| `快乐的死.epub` | `dd6779917d581702bd48fe5ad2af90cd` | `/body/DocFragment[6]/body/p[42]` | 0.43333333333333335 |
| `绍宋 - 榴弹怕水.epub`（不是 TXT） | `69aeec864932e4c3e16ea23d70d40ef0` | `/body/DocFragment[335]/body/p[12]/text().156` | 0.6392553768299295 |

说明：

- 第一条正是本地测试 EPUB，可作为 EPUB 段落级 XPointer 的真实样本。
- 第二条是从 TXT 转出的 EPUB，不是原始 TXT；**原始 TXT 仍没有真实 `progress` 样本**。
- TXT 的行/段模型仍需在 Task 10 用 KOReader/CrossPoint 打开原始 TXT 后校准。
- 如果无法提供原始 TXT 样本，TXT 上传方向按设计中的“生成失败则跳过”处理，拉取方向用 `percentage` 回退。

### 18.4 还需要用户协助

- 请在 KOReader 或 CrossPoint 上打开 `绍宋 - 榴弹怕水.txt` 原始 TXT，推一次进度到 `test` 账号，并记录大致段落。
- 如果两种客户端都会使用，最好各推一次；如果只能推一种，先推 TXT。
- 测试账号密码不写入仓库、不写入日志；联调时由开发者本地使用。

