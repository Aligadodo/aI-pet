# aI-pet / 甜蜜桌宠

这是“我家女友·甜蜜桌宠”的统一项目仓库，包含桌面端、Android 端以及可独立发布的 PetPack v2 角色与交互资源包工具链。

## 仓库结构

- `desktop/SweetGirlfriendDesktopPet`：Windows 桌面端 v1.2.4 权威源码与动作资源。
- `android/SweetGirlfriendPetAndroid`：Android v0.5.4 权威源码。目录沿用早期工作区名称，但应用版本以 Gradle 配置为准。
- `petpack/PetPack-v2`：PetPack v2 协议、schema、模板、制作/质量门禁工具、资源包源码与正式 QA 报告。
- `docs`：发布架构和制品索引。

历史 APK、Windows 运行包、源码归档和 `.petpack` 没有写入 Git 历史，而是集中放在 [GitHub Releases](https://github.com/Aligadodo/aI-pet/releases)，便于下载并避免仓库被重复二进制文件撑大。每个制品都在 `docs/ARTIFACTS.md` 中记录大小与 SHA-256。

## 快速开始

### Android

```powershell
cd android\SweetGirlfriendPetAndroid
.\gradlew.bat :app:assembleDebug
```

需要本机安装 JDK 17 和 Android SDK；请自行创建未提交的 `local.properties`。

### Windows 桌面端

```powershell
cd desktop\SweetGirlfriendDesktopPet
py -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-build.txt
.\.venv\Scripts\python.exe app.py
```

### PetPack

```powershell
cd petpack\PetPack-v2
python .\tools\petpack.py qa .\packs\jk-beach-summer
python .\tools\petpack.py publish .\packs\jk-beach-summer --allow-warnings --android-project ..\..\android\SweetGirlfriendPetAndroid
```

`publish` 是正式资源包发布入口：它会执行协议、内容、动画质量、确定性构建和 Android 安装门禁，并以事务方式提交制品、哈希和报告。示例中的 `--allow-warnings` 是对当前 JK 包 4 条已审查视觉提示的显式接受，不会忽略错误。

## 自动化质量门禁

- `Source and protocol CI` 会在提交和拉取请求中运行 Android JVM 测试与 Lint、PetPack 工具测试/全包校验，以及 Windows 桌面端测试。
- `PetPack Android install gate` 会在资源包或 Android 运行时变更的拉取请求中启动 API 36 模拟器，执行 Vivo `Invalid column last modified` 兼容回归、资源包预检、安装、重复安装和冷加载验证。
- 正式 `.petpack` 仍必须由 `petpack.py publish` 生成；单纯压缩目录不属于可发布制品。

## 发布状态

- Android：v0.5.4，`versionCode 9`，用于当前测试迭代的 APK 为 Android Debug 签名。
- Windows：v1.2.4。
- PetPack：`girlfriend-classic` v1.2.1、`jk-beach-summer` v1.0.0。

本仓库不包含用户提供的原始摄影 JPG、开发机 SDK 路径、虚拟环境、构建缓存、临时复现包或签名私钥。

人物与摄影衍生素材的使用边界，以及 Debug APK 的发布属性见 [NOTICE.md](NOTICE.md)。
