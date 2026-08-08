# SweetPet 全量制品归档 · 2026-08-08

这是仓库首次导入时的完整制品归档。附件按 SHA-256 去重，共 41 个有效制品、约 990 MB；构建缓存、虚拟环境、临时复现包和内容完全相同的重复副本未上传。

## 当前迭代

- Android v0.5.4 QA APK：`92250bccb3ea74238851f92416100bc8c22d8f0cae63ba153a1649bd3a8732c4`
- Windows v1.2.4 运行包（已净化并补齐第三方许可）：`9f84cc94b9bc15edeb1795476721923f20a405ba8221bac6b79e3f0bdb6324de`
- Windows v1.2.4 源码归档（已移除缓存并补齐第三方许可）：`e10aef65dce66f1328a5ece3fb400a0d504beeac06335a323dc20eb294131243`
- JK·海边夏日 PetPack v1.0.0：`fe71fd0232f65d4604e2ee6974da9e3a4a4edb13530c608629e6ef989724514b`

## 自动化验证

- Android JVM tests、Lint 和 Debug 构建。
- PetPack Python 工具测试、协议校验、确定性构建和 QA。
- API 36 模拟器安装门禁：Vivo `Invalid column last modified` 兼容回归、预检、安装、重复安装、动作/任务解析及冷加载。
- Windows 桌面端单元测试。

完整文件名、大小、状态、源路径、去重别名和 SHA-256 位于附件 `release-manifest.json`，仓库内也保留了 [制品索引](https://github.com/Aligadodo/aI-pet/blob/main/docs/ARTIFACTS.md)。

> Android APK 均为历史迭代或当前 QA 用 Debug 签名包，不是应用商店生产签名包。本归档标记为 Pre-release。
