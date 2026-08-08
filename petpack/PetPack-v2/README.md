# SweetPet 桌宠资源包项目

该目录是与 Android 桌宠运行程序并行发布的资源包项目。运行程序发布 APK；本项目发布 `.petpack`，双方只通过 `io.sweetpet.pack/2.x` 声明式协议交互。

## 安全与扩展原则

- 资源包仅包含图片、音频和 JSON 数据，不允许携带或执行 DEX、JAR、SO、脚本或可执行文件；
- `pack.json` 声明协议版本、最低运行时、能力和扩展点；
- 扩展点由 `id + apiVersion + entrypoint + required` 标识；
- 运行程序只处理注册过的扩展。未知可选扩展被忽略，未知必需扩展拒绝安装；
- `checksums.json` 为包内每个文件提供 SHA-256；
- 安装端还会限制归档大小、文件数、展开大小和路径，防止 Zip Slip 与压缩炸弹。

## v2 已注册扩展

| 扩展 ID | API | 用途 |
|---|---:|---|
| `io.sweetpet.pack-settings` | 1 | 在应用设置页动态生成布尔、整数和选项参数 |
| `io.sweetpet.dialogue-rules` | 1 | 根据时段和资源包参数选择动态对话 |
| `io.sweetpet.game-kit` | 1 | 声明头像裁切、玩法白名单和主题色 |

v1.2.0 资源包为每个动作增加朝向、镜像、旋转策略、落脚锚点和场景标签，并扩充时间/星期/天气台词、15 类任务及 6 种高级玩法声明。工具会同时校验这些运动元数据和玩法标识。

## 构建

离线校验需要 Python 3.10+ 和 Pillow（用于读取尺寸、校验容器并实际解码像素流）：

```powershell
python -m pip install Pillow
```

### 一键制作流水线（推荐）

稳定 CLI 分为初始化、只读 QA 和正式发布三步：

```powershell
# 1. 从可编辑的最小 v2 模板创建一个立即可校验的包
python .\tools\petpack.py new .\packs\my-pet --id my-pet --name "我的桌宠" --version 0.1.0

# 2. 不修改源目录，生成 JSON、Markdown 和动作联系表
python .\tools\petpack.py qa .\packs\my-pet --reports .\reports\my-pet-0.1.0

# 3. 一次完成安全归一化、协议/引用/文案 lint、帧 QA、checksum、双构建复现与发布
python .\tools\petpack.py release .\packs\my-pet
```

`release` 默认输出 `dist/<id>-<version>.petpack`、同名 `.sha256`，以及
`reports/<id>-<version>/qa-report.json`、`qa-report.md`、`contact-sheet.png`。
所有归一化和 checksum 更新都发生在私有快照中；源目录保持不变。尺寸和像素模式已合规的
PNG/WebP 不会被重新编码。两次构建必须逐字节一致才会原子发布，任一 error 均以非零状态退出。
需要把 QA warning 也作为门禁时增加 `--strict`；需要完全关闭安全归一化时使用
`--normalization none`。

详细阶段、阈值和迁移说明见 [AUTHORING_PIPELINE.md](./AUTHORING_PIPELINE.md)。

### 兼容的底层命令

```powershell
python .\tools\petpack.py validate .\packs\girlfriend-classic
python .\tools\petpack.py build .\packs\girlfriend-classic .\dist\girlfriend-classic-1.2.0.petpack
```

校验器覆盖行为 profile 权重与回退动作、手动放置休息时长、动作 motion 元数据、game-kit 头像与玩法白名单、任务的动作/玩法交叉引用、动态对话规则，以及单图、单动作和整包的解码像素预算。回归测试可独立运行：

```powershell
python -m unittest discover -s .\tools -p "test_*.py" -v
```

`dist` 产物可以通过 Android 应用的局域网上传网页安装，不需要重新编译 APK。
