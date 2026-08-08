# PetPack QA：JK·海边夏日

- ID / 版本：`jk-beach-summer` / `1.0.0`
- 输入指纹：`4debedd76c9a861da176acc5eb388dc66d4c014b0eaf051b03906bd415ff17a3`
- 动作 / 声明帧 / 唯一帧：10 / 72 / 48
- 归一化帧：0
- 诊断：0 errors，4 warnings
- 确定性复建：通过
- 产物：`jk-beach-summer-1.0.0.petpack`（4371316 bytes）
- 产物 SHA-256：`fe71fd0232f65d4604e2ee6974da9e3a4a4edb13530c608629e6ef989724514b`

## 动作 QA

| 动作 | 帧/唯一帧 | 尺寸范围 | 落脚差值 | 最大中心步进 |
|---|---:|---|---|---:|
| `idle` | 16/4 | [147, 154] × [461, 461] | [0, 0] | 5.5 px |
| `walk` | 8/8 | [167, 282] × [452, 464] | [0, 0] | 22.55 px |
| `run` | 8/8 | [218, 330] × [353, 396] | [0, 8] | 28.43 px |
| `wave` | 4/4 | [159, 178] × [459, 460] | [0, 0] | 19.5 px |
| `photo_pose` | 4/4 | [127, 313] × [438, 448] | [0, 0] | 16.568 px |
| `sea_breeze` | 16/4 | [165, 188] × [462, 463] | [0, 0] | 12.01 px |
| `shell_pick` | 4/4 | [174, 264] × [317, 465] | [0, 0] | 41.785 px |
| `splash_jump` | 4/4 | [221, 273] × [319, 410] | [0, 100] | 99.193 px |
| `sunset_flower` | 4/4 | [124, 171] × [451, 461] | [0, 0] | 27.613 px |
| `sleepy_pose` | 4/4 | [127, 128] × [473, 475] | [0, 0] | 10.5 px |

## 诊断

- **WARNING** `copy.duplicate`：same copy appears 2 times（`tasks/tasks.json.tasks[0].options[1].label, tasks/tasks.json.tasks[9].options[1].label`）
- **WARNING** `copy.duplicate`：same copy appears 2 times（`tasks/tasks.json.tasks[5].options[2].label, tasks/tasks.json.tasks[17].options[2].label`）
- **WARNING** `frame.size-pop`：subject bounds vary by more than 40% within action（`photo_pose`）
- **WARNING** `frame.size-pop`：subject bounds vary by more than 40% within action（`shell_pick`）
