# SweetPet Pack Protocol v2

## 发布边界

- 桌宠运行程序发布 APK，桌宠资源包发布 `.petpack`；
- 两者通过 `io.sweetpet.pack/2.x` JSON 协议交互；
- 资源包不得携带可执行代码，高级能力只能使用运行程序登记的声明式扩展点；
- 运行程序不认识的可选扩展可忽略，不认识的必需扩展必须拒绝安装。

## 容器结构

`.petpack` 是 ZIP 容器，`pack.json` 位于归档根目录：

```text
pack.json
checksums.json
preview.png
character/animations.json
character/game-kit.json
character/animations/<action>/<frame>.png
dialogue/zh-CN.json
dialogue/rules.json
behavior/default.json
tasks/tasks.json
settings/schema.json
```

## 主清单与扩展协商

```json
{
  "schemaVersion": 2,
  "protocol": {"id": "io.sweetpet.pack", "version": "2.0", "minRuntime": "0.5.0"},
  "id": "sample-character",
  "name": "示例角色",
  "version": "1.0.0",
  "author": "Author",
  "preview": "preview.png",
  "integrity": "checksums.json",
  "entrypoints": {
    "animations": "character/animations.json",
    "dialogue": "dialogue/zh-CN.json",
    "behavior": "behavior/default.json",
    "tasks": "tasks/tasks.json"
  },
  "capabilities": ["animation", "dialogue", "behavior", "tasks", "game-modes"],
  "extensions": [
    {"id": "io.sweetpet.pack-settings", "apiVersion": 1, "entrypoint": "settings/schema.json", "required": false},
    {"id": "io.sweetpet.dialogue-rules", "apiVersion": 1, "entrypoint": "dialogue/rules.json", "required": false},
    {"id": "io.sweetpet.game-kit", "apiVersion": 1, "entrypoint": "character/game-kit.json", "required": false}
  ]
}
```

扩展由 `id`、整数 `apiVersion`、包内 JSON `entrypoint` 和 `required` 四项协商。v0.5.0 支持：

| 扩展 | API | 用途 |
|---|---:|---|
| `io.sweetpet.pack-settings` | 1 | 动态生成 `boolean`、`integer`、`choice` 设置 |
| `io.sweetpet.dialogue-rules` | 1 | 按事件、时间、星期、天气、温度、概率和包设置筛选台词 |
| `io.sweetpet.game-kit` | 1 | 头像裁切、玩法白名单和游戏主题色 |

PetPack v2 必须显式提供 `protocol.minRuntime`，不能依赖默认值；只有 schema v1 旧包保留兼容默认行为。`pack.version` 与 `protocol.minRuntime` 必须是严格的稳定三段数字版本 `x.y.z`；不接受预发布后缀、构建元数据、缺少段、前导零、非数字或超过 32 位整数的版本分量。`protocol.version` 必须匹配 `2.<非负整数>`。APK 与 PetPack 构建工具使用同一规则，避免预发布包遮蔽稳定内置包。

## 动作运动元数据

每个动作可以包含 `motion`。旧资源包省略时使用安全默认值。

```json
{
  "fps": 16,
  "loop": true,
  "motion": {
    "defaultFacing": "right",
    "supportsHorizontalMirror": true,
    "rotationPolicy": "align-velocity",
    "groundAnchor": [0.5, 0.92],
    "sceneTags": ["ground", "border", "arcade", "pathfinding"]
  },
  "frames": ["animations/run/frame_00.png"]
}
```

- `defaultFacing`：`front`、`left` 或 `right`；
- `supportsHorizontalMirror`：运行时是否可安全水平镜像；
- `rotationPolicy`：`upright`、`align-surface` 或 `align-velocity`；
- `groundAnchor`：归一化落脚锚点，运行时用于碰撞、边框贴合、旋转中心和动作切换时的落脚点对齐；
- `sceneTags`：动作适用场景提示；v0.5.0 会校验并保留这些标签，供后续动作选择器和扩展协商使用。

人形走路/跑步素材在四边巡游时应优先使用 `align-surface`：运行时会让脚底贴合当前边框并保证身体朝屏内，再通过水平镜像表达沿边移动方向。`align-velocity` 更适合箭头、飞行物或本身就应朝速度方向旋转的非人形素材，否则在上边框可能出现人物头朝屏外的问题。

所有同一动作的帧应使用相同画布、人物比例和落脚锚点，透明区必须清理干净。

## 行为配置

`behavior` 入口声明不同交互风格的动作权重，以及资源包级兜底行为：

```json
{
  "schemaVersion": 1,
  "profiles": {
    "daily": {"idleWeight": 40, "walkWeight": 30, "runWeight": 10, "socialWeight": 20},
    "sweet": {"idleWeight": 28, "walkWeight": 22, "runWeight": 12, "socialWeight": 38},
    "quiet": {"idleWeight": 80, "walkWeight": 15, "runWeight": 0, "socialWeight": 5}
  },
  "fallbackAction": "idle",
  "manualPlacementRestSeconds": 300
}
```

- 四种权重均为 `0..1000` 的整数，每个配置的总权重必须大于 0；
- `fallbackAction` 必须引用 `animations` 中存在的动作；
- `manualPlacementRestSeconds` 控制用户主动拖放后暂停自动移动的时长，范围为 5 秒到 24 小时；
- 应用保留全局交互频次、显示层级、背景/遮罩和节能设置；资源包行为配置不能扩大系统权限。

## Game Kit

```json
{
  "schemaVersion": 1,
  "avatar": {
    "source": "animations/idle/frame_00.png",
    "crop": [0.25, 0.045, 0.75, 0.46],
    "shape": "circle"
  },
  "supportedModes": ["GRAVITY", "BORDER_WALK", "BORDER_RUN", "HIDE_SEEK", "BOMBER", "SNAKE"],
  "accentColor": "#C9577D",
  "foodColor": "#FFC3D8",
  "bombColor": "#6D5360"
}
```

玩法白名单是资源包能力声明。未声明的玩法不会强行启动；头像缺失时也不会把真实桌面图标当作游戏素材。

## 动态台词与任务

`dialogue/rules.json` 的条件支持：

- `hourStart` / `hourEnd`，并支持跨午夜时段；
- `dayOfWeek`（1=星期一，7=星期日）；
- `weatherEquals` / `weatherIn`；
- `temperatureMin` / `temperatureMax`；
- `chance`（0.0–1.0）；
- `settingKey` / `settingEquals`。

台词可使用 `{city}`、`{temperature}`、`{timePeriod}`、`{weekday}` 占位符。运行时会把规则命中台词与事件基础台词合并，随机选取并避开最近 4 句。

任务可添加：

```json
{
  "when": {"hourStart": 18, "hourEnd": 22, "weatherIn": ["clear", "cloudy"]},
  "options": [
    {"id": "play", "label": "开始", "response": "出发！", "action": "run", "playMode": "BORDER_RUN"}
  ]
}
```

合法 `playMode` 为 `GRAVITY`、`BORDER_WALK`、`BORDER_RUN`、`HIDE_SEEK`、`BOMBER`、`SNAKE`。是否允许随机邀请由应用基础设置控制。

## 安全限制

- 归档最大 96 MB、最多 2000 个文件、展开后最大 256 MB、单文件最大 32 MB；
- 拒绝绝对路径、`..`、反斜杠路径和目录越界；
- 拒绝 APK、DEX、JAR、CLASS、SO、EXE、DLL 和脚本；
- `checksums.json` 对每个数据/媒体文件执行 SHA-256 校验；
- 完整性清单必须覆盖归档内所有数据/媒体文件，也不得引用不存在的文件；
- 位图安装后会执行真实解码检查，并限制单图 4,194,304 像素、单动作 16,777,216 像素、整包 67,108,864 像素；
- 同 ID 的用户资源包只有在语义版本不低于 APK 内置版本时才会生效；已安装资源一旦被选中，缺失文件不会与内置包混合回退；
- 安装先进入暂存目录，完整校验后原子替换，失败回滚；
- 游戏扩展只能描述素材和参数，不能读取、移动或删除真实桌面图标。

本仓库中的资源包独立项目位于：

```text
../../../petpack/PetPack-v2
```
