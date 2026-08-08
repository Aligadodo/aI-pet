# aI-pet / 甜蜜桌宠

这是“我家女友·甜蜜桌宠”的统一项目仓库，包含桌面端、Android 端以及可独立发布的 PetPack v2 角色与交互资源包工具链。

## 仓库结构

- `desktop/SweetGirlfriendDesktopPet`：Windows 桌面端 v1.2.4 权威源码与动作资源。
- `android/SweetGirlfriendPetAndroid`：Android v0.5.4 权威源码。目录沿用早期工作区名称，但应用版本以 Gradle 配置为准。
- `petpack/PetPack-v2`：PetPack v2 协议、schema、模板、制作/质量门禁工具、资源包源码与正式 QA 报告。
- `docs`：发布架构和制品索引。

历史 APK、Windows 运行包、源码归档和 `.petpack` 没有写入 Git 历史，而是集中放在 [GitHub Releases](https://github.com/Aligadodo/aI-pet/releases)，便于下载并避免仓库被重复二进制文件撑大。每个制品都在 `docs/ARTIFACTS.md` 中记录大小与 SHA-256。

## 快速开始

### 统一一键迭代

流水线使用仓库根目录的独立 Python 环境；`requirements-iteration.txt` 固定了 PetPack、桌面端测试与打包所需依赖：

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-iteration.txt
.\scripts\sweetpet.cmd doctor --strict
```

先用 dry-run 检查阶段、命令和工作目录，不会创建运行目录，也不会构建或安装制品：

```powershell
.\scripts\sweetpet.cmd iterate --profile quick --dry-run
.\scripts\sweetpet.cmd iterate --profile ci
```

统一入口提供 `doctor`、`iterate`、`pack`、`deploy`、`intake` 和 `status` 六组命令；`quick`、`ci`、`full`、`release` 四个 profile 分别覆盖快速反馈、完整测试、候选构建和正式发布。每次实际流水线运行都写入 `outputs/pipeline/<run-id>`，包含日志、摘要、状态、报告、制品及其哈希清单。详细命令、阶段依赖、失败语义和发布边界见 [统一迭代流水线](docs/ITERATION_PIPELINE.md)。

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

日常质量检查、候选构建和正式发布优先使用根目录统一入口：

```powershell
.\scripts\sweetpet.cmd pack qa jk-beach-summer
.\scripts\sweetpet.cmd pack candidate jk-beach-summer
.\scripts\sweetpet.cmd pack publish jk-beach-summer --serial emulator-5554
```

`qa` 只执行质量门禁，`candidate` 生成可复现的内部候选包；两者都不是正式可交付资源包。只有 `publish` 会执行完整协议、内容、动画质量、确定性构建和 Android 真机/模拟器安装门禁。默认情况下，正式制品保存在本次 `outputs/pipeline/<run-id>` 中；只有显式增加 `--promote`，才会更新 `petpack/PetPack-v2/dist` 与 `petpack/PetPack-v2/reports` 中的 canonical 正式制品和报告。

当前 JK 包的 4 条已审查提示由 `sweetpet.pipeline.json` 按 `code + location` 精确列入白名单：新增提示或已经消失的旧提示都会令门禁失败。不要把底层 `--allow-warnings` 当作通用放行开关。

## 自动化质量门禁

- Windows 本地统一入口为 `scripts/sweetpet.cmd doctor|iterate|pack|deploy|intake|status`；它不依赖 PowerShell 脚本执行策略。`sweetpet.ps1` 保留为可选入口。运行记录集中在 `outputs/pipeline/<run-id>`，可用 `status <run-id>` 回看。
- `Source and protocol CI` 会在提交和拉取请求中运行 Android JVM 测试与 Lint、PetPack 工具测试/全包校验，以及 Windows 桌面端测试。
- `PetPack Android install gate` 会在资源包或 Android 运行时变更的拉取请求中启动 API 35 模拟器，执行 Vivo `Invalid column last modified` 兼容回归、资源包预检、安装、重复安装和冷加载验证。
- 正式 `.petpack` 只能来自 `pack publish`；`pack candidate` 和单纯压缩目录都不属于可发布制品。只有 `publish --promote` 会更新 canonical dist。

## 发布状态

- Android：v0.5.4，`versionCode 9`，用于当前测试迭代的 APK 为 Android Debug 签名。
- Windows：v1.2.4。
- PetPack：`girlfriend-classic` v1.2.1、`jk-beach-summer` v1.0.0。

本仓库不包含用户提供的原始摄影 JPG、开发机 SDK 路径、虚拟环境、构建缓存、临时复现包或签名私钥。

人物与摄影衍生素材的使用边界，以及 Debug APK 的发布属性见 [NOTICE.md](NOTICE.md)。
