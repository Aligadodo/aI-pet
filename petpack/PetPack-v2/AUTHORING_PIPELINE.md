# PetPack v2 制作流水线

## 原流程的低效点

1. `validate/build` 只负责协议和安全，初始化目录、切帧与联系表依赖人工或角色专用脚本。
2. 旧 `build` 会在作者目录内重写 `checksums.json`，失败也可能留下工作区改动。
3. 已有像素预算校验，但没有跨帧主体尺寸、落脚锚点、循环闭合和位置跳变指标。
4. 动作/玩法的基础引用已校验，但设置项取值、运行时占位符、重复文案和孤立帧没有统一 lint。
5. ZIP 时间戳固定，却没有自动执行“双构建比较”，也没有机器报告、联系表和 hash sidecar。

## 稳定命令

```powershell
python .\tools\petpack.py new <目录> --id <id> --name <名称> [--version 0.1.0]
python .\tools\petpack.py qa <目录> [--reports <目录>] [--strict]
python .\tools\petpack.py release <目录> [--output <文件>] [--reports <目录>] [--strict]
python .\tools\petpack.py publish <目录> [--output <文件>] [--reports <目录>] --serial <adb-serial> [--allow-physical-device]
python .\tools\petpack.py install-gate <已有.petpack> --serial <adb-serial> [--report <文件>] [--allow-physical-device]
```

`init` 和 `pipeline` 分别保留为 `new` 和 `release` 的兼容别名。

## release 阶段

1. 将输入复制到私有快照；拒绝把产物或报告写进源包。
2. `safe` 归一化只处理画布尺寸或像素模式不合规的 PNG/WebP，已合规文件保持原始字节。
3. 复用 `petpack.validate_pack` 做协议、安全、入口、玩法、位图预算和交叉引用校验。
4. 语义 lint 检查已知占位符、设置 key/值、重复台词和未引用动作帧。
5. 帧 QA 记录 bbox、落脚差值、主体尺寸范围、相邻帧中心位移、可见面积比和 alpha 变化。
6. 输出 JSON、Markdown 与透明棋盘背景动作联系表；error 阻断，warning 可用 `--strict` 阻断。
7. 在独立快照上生成 SHA-256 清单，使用固定 ZIP 元数据连续构建两次并逐字节比较。
8. 仅在复现通过后生成候选 `.petpack` 与 `<文件>.sha256`；`release` 产物仍不是面向用户的正式发布包。

## publish Android 安装门禁

`publish` 是正式交付资源包的唯一入口。它先把候选构建在私有临时目录，再把候选的精确 SHA-256 传给独立的 Android instrumentation，并复用生产代码执行：

1. `ContentPackInstaller.inspect()` 完整预检。
2. 首次安装与安装回执校验。
3. 新建 `ContentPackLoader`，验证全新加载器实例、版本和全部声明动作帧。
4. 加载任务库，验证运行时引用。
5. 对同一归档再次预检和安装，要求识别重复内容并返回幂等结果。
6. 清理隔离安装目录；任何检查或清理失败都阻断发布。

门禁通过后才原子替换 `dist` 中的正式文件；失败时保留上一个已发布文件。没有在线模拟器/真机时只能生成 `work` 候选，不得把 `release` 产物交付给用户。
`publish` 默认把 warning 也视为阻断；只有已人工审阅并记录为有意设计时，才可显式添加 `--allow-warnings`。
门禁默认只接受专用 Android 模拟器，避免测试脚本静默覆盖个人手机上的 APK；确需在真机执行时必须同时指定序列号与 `--allow-physical-device`。

## QA 信号

- `frame.edge-touch`：可见像素碰到画布边缘，可能被裁切。
- `frame.below-anchor`：地面动作越过声明落脚锚点（error）。
- `frame.air-gap`：地面动作离锚点超过 24 px 或画布高度 5%。
- `frame.size-pop`：同一动作主体 bbox 相对中位数偏差超过 40%。
- `frame.position-jump`：相邻帧中心移动超过画布长边 20%。
- `frame.area-pop`：相邻帧可见面积变化超过 2 倍。
- `copy.*` / `reference.*`：文案占位符、重复内容、设置引用和值域、孤立帧问题。

阈值是跨角色的保守信号，不会默认阻断 warning；发布团队可在 CI 中启用 `--strict`。

## 回归与无损基线

```powershell
python -m unittest discover -s .\tools -p "test_*.py" -v
python .\tools\petpack.py release .\packs\jk-beach-summer `
  --output .\work\jk-beach-summer.repro.petpack `
  --reports .\work\jk-beach-summer-report
python .\tools\petpack.py install-gate .\dist\jk-beach-summer-1.0.0.petpack `
  --serial emulator-5554 `
  --report .\reports\jk-beach-summer-1.0.0\android-install-gate.json
```

测试固定验证 JK 海边夏日包的源树不被修改，并要求新产物与现有
`dist/jk-beach-summer-1.0.0.petpack` 逐字节一致。
