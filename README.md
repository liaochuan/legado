> [!IMPORTANT]
> 本仓库的 `liaocl` 分支是基于上游 [gedoor/legado](https://github.com/gedoor/legado) 的自定义修改分支，不是官方发布版本。与上游的差异见下方「[相较上游的修改](#相较上游的修改)」。
>
> 本分支会不定时同步上游提交，同步后清单可能与实际代码短暂不一致；以代码和提交记录为准。
>
> 这些改动主要由 AI 辅助开发，尚未经过完整的代码审查和系统性测试，因此没有向上游提交 PR，以免污染上游代码。需要稳定版本请直接使用上游仓库；遇到问题时请先在上游版本复现，确认由本分支引入后再反馈。
>
> 维护要求：本分支新增或调整任何与上游不同的行为时，必须在同一次提交中更新下方清单；新增界面入口时必须写明具体操作路径。

## 相较上游的修改

### 新增功能

| 功能 | 说明 | 操作路径 |
| --- | --- | --- |
| KOReader / CrossPoint 进度同步（KOSync） | 本地 TXT / EPUB 与 KOReader、CrossPoint 双向同步阅读进度（要求各设备上的文件字节一致）；支持配置设备名、启动时批量同步和连接测试；与 WebDAV 进度同步共存时，WebDAV 只上传进度、不读取。 | 我的 → 备份与恢复 → KOReader 同步 |
| KOSync 同步反馈与封面地址 | 阅读时自动同步并显示同步中提示；同步失败只提示，不再打断阅读；同步时上报书名等元数据与封面地址，便于 KOReader / CrossPoint 展示。 | 无独立入口，阅读时自动触发 |
| calibre-web / OPDS 网络书库 | 服务器类型新增 OPDS（HTTP Basic 认证），支持目录浏览、服务端搜索、分页、格式选择、单本 / 批量下载和书籍详情页；下载后回填书名、作者、简介、分类，并生成本地持久封面；简介保留段落与首行缩进；只有 WebDAV 来源的书才会显示「同时删除 WebDAV 同名文件」；服务器列表可直接选用当前 WebDAV 配置。 | 书架 → 右上角「⋮」→ 网络书库 → 右上角「+」添加服务器（类型选 OPDS，或直接选「当前 WebDAV」）→ 浏览分类 / 搜索 → 书籍详情 → 加入书架；长按条目可多选后批量下载 |

### 修复与内部调整

- **本地书籍封面**：本地文件已提取到封面时优先使用本地封面，避免被网络封面覆盖（EPUB、MOBI、PDF、UMD 等本地书籍）。
- **KOSync 混淆修复**：为 KOSync 的 JSON 字段补充 R8 keep 规则，避免 release 包混淆后同步字段丢失。
- **阅读进度同步容错**：自动同步失败只记录并提示，不再打断阅读流程。
- **数据库迁移**：`books` 表新增 `kosyncTime` 字段，数据库版本 113 → 114。
- **HTTP 日志脱敏**：日志脱敏规则补充 `xauthkey`、`xauthuser`，避免 KOSync 凭据进入日志。
- **移除 Firebase**：删除 `firebase-analytics`、`firebase-perf`、`google-services` 插件及 `google-services.json`，清理 CI 引用，隐私政策同步去掉 Firebase 说明。
- **本地打包**：新增 `packaging/build-liaocl.ps1` / `.bat`，支持 `customApplicationId`、`customAbiFilters` 覆盖，默认产物输出到 `dist/`；详见下方「[本地打包](#本地打包)」。
- **工程文档**：新增 `AGENTS.md`，以及 `docs/superpowers/` 下的设计与计划文档（工作流产物）。
- **测试**：为 KOSync、OPDS、本地封面和数据库迁移补充单元测试。

[![icon_android](https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/icon_android.png)](https://play.google.com/store/apps/details?id=io.legado.play.release)
<a href="https://jb.gg/OpenSourceSupport" target="_blank">
<img width="24" height="24" src="https://resources.jetbrains.com/storage/products/company/brand/logos/jb_beam.svg?_gl=1*135yekd*_ga*OTY4Mjg4NDYzLjE2Mzk0NTE3MzQ.*_ga_9J976DJZ68*MTY2OTE2MzM5Ny4xMy4wLjE2NjkxNjMzOTcuNjAuMC4w&_ga=2.257292110.451256242.1669085120-968288463.1639451734" alt="idea"/>
</a>

<div align="center">
  
Legado 是一款免费的 Android 平台开源小说阅读器。
</div>

## 构建

- JDK 21、Android SDK（compileSdk 36），使用仓库内的 Gradle Wrapper
- 调试包：`./gradlew :app:assembleAppDebug`
- 单元测试：`./gradlew :app:testAppReleaseUnitTest`

## 本地打包

`packaging/build-liaocl.ps1` 用于生成与官方包名隔离的个人安装包：包名 `top.liaocl.legado`、仅 `arm64-v8a`、release 签名。

1. 复制 `packaging/liaocl-build.example.json` 为 `packaging/liaocl-build.local.json`，填写 Android SDK、JDK 21、keystore 等字段；
2. 运行 `packaging\build-liaocl.bat`，产物默认输出到 `dist/`（可用 `-OutputDir` 指定其他目录）。

`liaocl-build.local.json` 含明文密码，已被 `.gitignore` 忽略，不要提交。

## 隐私

应用没有服务端，不收集用户信息；书源、WebDAV 同步与更新检查都直接访问用户指定的服务，不做崩溃或性能数据上报。详见 [`privacyPolicy.md`](app/src/main/assets/privacyPolicy.md)。

# Sponsor

> 感谢 [PiPio 中转站](https://pipio.io/offer/miaogongzi) 对项目的赞助支持。

# Grateful-感谢 [![](https://img.shields.io/badge/-Grateful-F5F5F5.svg)](#Grateful-感谢-)
> * org.jsoup:jsoup
> * cn.wanghaomiao:JsoupXpath
> * com.jayway.jsonpath:json-path
> * com.github.gedoor:rhino-android
> * com.squareup.okhttp3:okhttp
> * com.github.bumptech.glide:glide
> * org.nanohttpd:nanohttpd
> * org.nanohttpd:nanohttpd-websocket
> * cn.bingoogolapple:bga-qrcode-zxing
> * com.jaredrummler:colorpicker
> * org.apache.commons:commons-text
> * io.noties.markwon:core
> * io.noties.markwon:image-glide
> * com.hankcs:hanlp
> * com.positiondev.epublib:epublib-core
> * com.github.Moriafly:LyricViewX
> * io.github.rosemoe:editor
