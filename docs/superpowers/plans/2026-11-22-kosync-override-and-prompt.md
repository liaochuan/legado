# KOSync：覆盖云端进度与云端更新提示

## 目标

1. 只有「覆盖云端进度」（阅读界面 → 菜单 → 覆盖云端进度）允许把云端进度改到更靠前的位置之外的方向，即允许后退。
2. 普通同步（自动/手动同步、退出阅读、后台推送）不允许把云端进度改小；云端进度更靠前时，上传应被跳过而不是假装成功。
3. 本地进度小于云端进度时，提示「云端进度超过当前进度，是否同步？」，确认后才跳转，行为与 WebDAV 一致。

## 背景

`crosspoint-sync` 的 `PUT /syncs/progress` 是只进不退的：新 `percentage` 小于当前值会被静默丢弃，但仍返回 200 与新的时间戳（`src/routes/kosync.ts` 的 `writeDeviceProgress`）。向后移动必须走 `PUT /api/v1/progress`（`src/routes/v1/progress.ts`）。旧实现两种情况都走 `/syncs/progress`，于是出现「提示上传成功，但云端进度没变」。

## 改动

- `KosyncClient`：抽出公共请求体，新增 `putProgressExtended()` 调 `PUT /api/v1/progress`。
- `ProgressSyncProvider.push()`：新增 `overrideCloud` 参数（默认 `false`），WebDAV 实现忽略。
- `KosyncProgressProvider.push()`：
  - `overrideCloud = true` → 走扩展接口；404 时回退 `/syncs/progress`（官方 kosync 本身接受后退）。
  - 普通上传：先按**阅读位置**判断云端是否更靠前，更靠前则直接跳过并返回 `null`，不写入、不推进 `kosyncTime`。
- `ProgressSyncCoordinator`：把 `overrideCloud` 透传到 KOSync 上传；上传返回 `null` 视为未确认成功，不触发「上传成功」。
- `AppProgressSync.push()` / `ReadBook.uploadProgress()`：新增 `overrideCloud`。
- `ReadBookActivity`：「覆盖云端进度」菜单传 `overrideCloud = true`，其余调用保持默认。
- `ReadBookViewModel.syncBookProgress()`：云端更靠前时改为调用 `ReadBook.callBack.sureNewProgress()` 弹确认框，而不是静默跳转。

## 测试

- `KosyncClientTest`：`putProgressExtended` 使用 `/api/v1/progress`。
- `KosyncProgressProviderTest`：普通上传遇到云端更靠前时跳过；`overrideCloud` 走扩展接口；扩展接口 404 回退。
- `ProgressSyncCoordinatorTest`：`pull`/`push` 签名更新；`overrideCloud` 透传。

## 补充（2026-11-23）：按位置判断云端领先

现象：`kosyncTime` 已被旧版本推到云端时间戳之后时，即使云端位置更靠前也不会提示，仍然走「上传」，KOSync 跳过、WebDAV 成功，于是显示「上传成功」。

修正：

- `PositionEquality.remoteIsAhead()`：用章节与段落位置判断云端是否领先，不再依赖服务器时间戳。
- `ProgressSyncCoordinator.syncKosync()`：
  - 位置相同 → 只更新 `kosyncTime`；
  - 云端位置更靠前 → 调用 `onApplyRemote`（界面确认框），不推进 `kosyncTime` 到更低值（用 `maxOf`）；
  - 本地更靠前 → 上传（`plus` 模式下先询问）。
- `KosyncProgressProvider.push()`：按位置判断云端是否领先；位置是前进但百分比低于云端（不同客户端百分比模型不同）时改走 `/api/v1/progress`，避免被服务端按百分比丢弃。

