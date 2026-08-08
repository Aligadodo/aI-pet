# 统一迭代流水线

`scripts/sweetpet.cmd` 是 Windows 上 Android、桌面端和 PetPack v2 的统一本地编排入口，不受 PowerShell 脚本执行策略影响。`scripts/sweetpet.ps1` 保留为可选入口；Linux/macOS 与 CI 直接调用 `python scripts/sweetpet.py`。三种入口共享同一实现、阶段依赖、运行记录、报告、制品哈希和发布边界。

## 1. 环境准备与诊断

在仓库根目录创建流水线专用 Python 环境。`requirements-iteration.txt` 固定了 PetPack 工具、桌面端测试和 PyInstaller 打包所需版本；它不替代 Android 所需的 JDK 17 与 Android SDK。

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-iteration.txt
.\scripts\sweetpet.cmd doctor --strict
```

入口会优先使用 `$env:SWEETPET_PYTHON`，其次使用根目录 `.venv`，最后尝试系统 `python` 或 `py -3.11`。建议在 CI 和发布机执行 `doctor --strict`，让缺失或版本不合要求的依赖直接失败；`doctor --json` 可输出机器可读结果。

## 2. 命令总览

```text
scripts/sweetpet.cmd doctor
scripts/sweetpet.cmd iterate
scripts/sweetpet.cmd pack
scripts/sweetpet.cmd deploy
scripts/sweetpet.cmd intake
scripts/sweetpet.cmd status
```

常用示例：

```powershell
# 查看 quick 实际会运行哪些阶段、命令和工作目录
.\scripts\sweetpet.cmd iterate --profile quick --dry-run

# 运行完整 CI 反馈链
.\scripts\sweetpet.cmd iterate --profile ci

# 只选择 PetPack 组件；必需依赖仍会自动展开
.\scripts\sweetpet.cmd iterate --profile full --component petpack --pack jk-beach-summer

# 回看某次运行
.\scripts\sweetpet.cmd status <run-id>
```

`--component all|petpack|android|desktop` 过滤 profile 的目标阶段，阶段依赖会自动补齐。`--stage <stage-name>` 可覆盖 profile 根阶段，适合维护者定位问题；常规迭代应优先使用 profile。默认遇到失败立即停止；`--keep-going` 允许无依赖关系的后续阶段继续，依赖失败阶段的任务仍会标记为跳过。

## 3. Profile 与阶段依赖

| Profile | 根阶段 | 用途 | 发布属性 |
| --- | --- | --- | --- |
| `quick` | `petpack-qa`、`android-test`、`desktop-audit` | 最快的本地反馈 | 不产出正式制品 |
| `ci` | `petpack-qa`、`android-test`、`desktop-test` | 与源码/协议 CI 对齐的测试 | 不产出正式制品 |
| `full` | `petpack-candidate`、`android-build`、`desktop-build` | 完整候选构建与制品检查 | 候选制品，不可发布 |
| `release` | `petpack-publish`、`android-build`、`desktop-build` | 完整发布门禁 | PetPack 发布需要设备序列号 |

流水线按依赖拓扑执行，而不是简单串行拼接命令。例如 PetPack 候选构建会先运行工具测试与源包校验；Android 构建会先运行 Android 测试。`release` 中的 PetPack 正式发布需要 `--serial <adb-serial>`。同时构建 Windows 桌面端时应在 Windows 上运行。

## 4. PetPack：QA、候选与正式发布

### QA

```powershell
.\scripts\sweetpet.cmd pack qa jk-beach-summer
```

`pack qa` 依次运行 PetPack 工具测试、全部源包协议校验和所选资源包 QA。它不生成可交付 `.petpack`。

### Candidate

```powershell
.\scripts\sweetpet.cmd pack candidate jk-beach-summer
```

`pack candidate` 在工具测试和源包校验之后生成确定性候选包，用于内部检查；它不执行真实 Android 安装门禁，因此无论文件名或内容多完整，都不是正式发布物，不能交付用户。

### Publish

```powershell
# 正式门禁通过，制品仅保存在本次运行目录
.\scripts\sweetpet.cmd pack publish jk-beach-summer --serial emulator-5554

# 门禁通过后，同时更新 canonical dist 与正式报告
.\scripts\sweetpet.cmd pack publish jk-beach-summer --serial emulator-5554 --promote
```

`pack publish` 执行工具测试、协议校验、资源 QA、Android JVM 测试/Lint、确定性构建、资源包预检、安装、重复安装与冷加载验证。它是唯一的正式 PetPack 发布入口。

- 不带 `--promote`：正式门禁制品与报告只写入 `outputs/pipeline/<run-id>`，不会修改仓库中的 canonical 目录。
- 带 `--promote`：门禁全部成功后才更新 `petpack/PetPack-v2/dist/<id>-<version>.petpack` 和 `petpack/PetPack-v2/reports/<id>-<version>`，并把制品复制到本次运行目录。
- 失败时不会把半成品提升到 canonical dist；已经存在的运行目录也不会被覆盖或“续跑”。修复原因后应使用新的 run-id 重跑。

## 5. 精确 warning 白名单

可接受提示记录在 `sweetpet.pipeline.json` 的资源包配置中，并按 `(code, location)` 精确比较：

- QA 出现白名单之外的新提示，流水线失败。
- 白名单中某条提示已经消失，说明配置变陈旧，流水线同样失败。
- Error 永远不能通过 warning 白名单放行。

当前 `jk-beach-summer` 只接受以下 4 条已审查提示：

| Code | Location |
| --- | --- |
| `copy.duplicate` | `tasks/tasks.json.tasks[0].options[1].label, tasks/tasks.json.tasks[9].options[1].label` |
| `copy.duplicate` | `tasks/tasks.json.tasks[5].options[2].label, tasks/tasks.json.tasks[17].options[2].label` |
| `frame.size-pop` | `photo_pose` |
| `frame.size-pop` | `shell_pick` |

底层发布器在确有已审查提示时仍需要 `--allow-warnings`，但统一入口会在调用前后校验上述精确集合。因此日常 QA 与发布应使用 `scripts/sweetpet.cmd pack ...`，不要直接使用一个笼统开关跳过提示审查。

## 6. Android 与 PetPack 部署

安装并启动本次流水线构建的 Android Debug APK：

```powershell
.\scripts\sweetpet.cmd deploy android --serial emulator-5554
```

该命令会自动展开 Android 测试/构建依赖，使用 `adb install -r` 安装 APK，并启动主 Activity。默认只允许明确指定的模拟器；如确需物理设备，必须同时提供 `--allow-physical-device`。

把已有 PetPack 暂存到手机导入目录：

```powershell
.\scripts\sweetpet.cmd deploy petpack --serial emulator-5554 --archive .\path\to\character.petpack
```

该命令把文件推送至 `/sdcard/Download/PetPacks/<name>` 并生成 `reports/petpack-stage.json`，不会在应用内静默安装。用户仍需在桌宠应用中查看来源、版本和校验信息并二次确认，重复包由应用安装流程处理。

## 7. 运行目录、缓存与失败语义

每次非 dry-run 的 `iterate`、`pack` 或 `deploy` 都创建独立目录：

```text
outputs/pipeline/<run-id>/
├─ .sweetpet-run
├─ logs/
├─ reports/
├─ artifacts/
├─ staging/
├─ summary.json
├─ summary.md
├─ state.json
└─ artifacts-manifest.json
```

默认 run-id 由 UTC 时间和进程号组成，也可通过 `--run-id <id>` 显式指定。流水线拒绝复用已有运行目录，以免旧日志、缓存或制品污染新结论。`artifacts-manifest.json` 为本次制品记录 SHA-256；Android Debug APK、AndroidTest APK、桌面端 ZIP 和 PetPack 等阶段制品都从这里追溯。

阶段只复用各原生工具自身的安全缓存（例如 Gradle 缓存），不把历史运行目录当作输入。默认 fail-fast；使用 `--keep-going` 时，独立分支可继续收集诊断，但任何失败都令整次运行失败，并阻止依赖阶段和正式提升。流水线不自动回滚源码；正式 PetPack 只有在全部门禁通过后由 `--promote` 事务性更新 canonical 目录。

`--dry-run` 仅打印解析后的阶段顺序、参数、命令行与工作目录，不创建 `outputs/pipeline/<run-id>`，也不安装、构建或修改 canonical 制品。建议在切换 profile、设备或资源包前先执行一次：

```powershell
.\scripts\sweetpet.cmd iterate --profile release --pack jk-beach-summer --serial emulator-5554 --dry-run
```

## 8. 下一角色的 intake 流程

新角色不要直接复制现有包。先创建不参与构建的 intake 工作区：

```powershell
.\scripts\sweetpet.cmd intake summer-traveler --title "夏日旅伴" --pack-class game-compatible
```

`intake` 根据 `petpack/PetPack-v2/templates/intake-v1` 创建 `petpack/PetPack-v2/work/intake/<id>`。它是策划、素材审查和身份锁定区，不是流水线运行目录，也不是 PetPack；原始素材只放在 `sources/raw`，不会提交或打进资源包。

必须按以下顺序推进：

1. `collecting`：登记来源、候选图、动作/交互覆盖和缺口。
2. `reviewing`：完成素材质量、相似度、隐私与发布范围审查。
3. `identity-locked`：锁定人物身份、服装、比例、色板、镜头/画布、朝向和可见脚基线；同时锁定权利归属、授权范围与公开发布同意。
4. `scaffold-ready`：只有身份与 rights lock 都完成后，才允许创建协议包骨架。

也就是：`intake → identity/rights lock → petpack.py new`。进入 `scaffold-ready` 后执行：

```powershell
cd petpack\PetPack-v2
python tools\petpack.py new packs\summer-traveler `
  --id summer-traveler `
  --name "夏日旅伴" `
  --version 0.1.0
```

随后再补齐 manifest、动作帧、锚点、台词、任务和 game-kit 元数据，并从 `pack qa` 开始进入统一门禁。当前 intake 模板只开放已经完整参数化的 `game-compatible` 类型，避免静态或轻量包误继承全运动/全玩法验收项；未来增加其他类型时会同时提供对应模板和门禁。

## 9. 与 GitHub Actions 的关系

本地 `quick`/`ci` 用于提交前反馈，GitHub Actions 仍是合并门禁：

- `Source and protocol CI` 覆盖 Android JVM 测试/Lint、PetPack 工具测试与全包校验，以及 Windows 桌面端测试。
- `PetPack Android install gate` 在 API 35 模拟器上覆盖 Vivo `Invalid column last modified` 兼容回归、预检、安装、重复安装和冷加载。

CI 通过不等于已经发布。对用户可交付的 `.petpack` 仍必须来自 `pack publish`；需要更新仓库 canonical dist 时，必须显式使用 `--promote`。
