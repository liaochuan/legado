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
