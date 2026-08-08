from __future__ import annotations

import random
from dataclasses import dataclass, replace


@dataclass(frozen=True)
class TaskChoice:
    label: str
    correct: bool = False
    action_url: str = ""
    action_kind: str = ""
    feedback: str = ""


@dataclass(frozen=True)
class SweetTask:
    key: str
    prompt: str
    choices: tuple[TaskChoice, ...]
    success_animation: str
    success_line: str
    wrong_animation: str
    wrong_line: str
    timeout_animation: str
    timeout_line: str
    duration_seconds: float = 10.0
    scene: str = "日常"
    periods: tuple[str, ...] = ()
    weathers: tuple[str, ...] = ()
    day_types: tuple[str, ...] = ()
    activities: tuple[str, ...] = ()
    task_type: str = "sweet"


SWEET_TASKS: tuple[SweetTask, ...] = (
    SweetTask(
        key="red_packet",
        prompt="今天想收到一点仪式感，你会怎么表示？",
        choices=(
            TaskChoice("准备一个小红包 🧧", True),
            TaskChoice("口头说句下次一定"),
            TaskChoice("迅速转移话题"),
        ),
        success_animation="jump",
        success_line="仪式感收到啦！其实最开心的是你愿意认真回应我。",
        wrong_animation="pout",
        wrong_line="仪式感不只在金额，也在你有没有认真回答呀。",
        timeout_animation="cry",
        timeout_line="我先把这个小愿望存起来，等你忙完再说。",
    ),
    SweetTask(
        key="strawberry_cake",
        prompt="下午想吃点甜的，你帮我选一个？",
        choices=(
            TaskChoice("草莓蛋糕 🍰", True),
            TaskChoice("原味苏打饼干"),
            TaskChoice("什么都不选"),
        ),
        success_animation="eat",
        success_line="就选它！等忙完这一阵，我们一起去吃。",
        wrong_animation="pout",
        wrong_line="今天真的很想吃甜的，再给你一次理解我的机会。",
        timeout_animation="cry",
        timeout_line="选择困难暂时搁置，等你有空我们再一起决定。",
    ),
    SweetTask(
        key="milk_tea",
        prompt="下午茶二选一，你觉得我今天会选哪杯？",
        choices=(
            TaskChoice("少糖奶茶 🧋", True),
            TaskChoice("超浓黑咖啡"),
            TaskChoice("今天先不喝"),
        ),
        success_animation="eat",
        success_line="猜对啦！看来你真的有认真记住我的口味。",
        wrong_animation="stomp",
        wrong_line="这个答案和我的口味差得有点远哦。",
        timeout_animation="pout",
        timeout_line="没关系，我先把想喝的记进我们的下次清单。",
    ),
    SweetTask(
        key="cuddle",
        prompt="现在有三分钟空档，你想怎么陪我？",
        choices=(
            TaskChoice("抱一会儿，听你说说话 🤗", True),
            TaskChoice("只发表情包"),
            TaskChoice("继续假装很忙"),
        ),
        success_animation="hug",
        success_line="好，那就安静抱一会儿。你不用急着说什么。",
        wrong_animation="pout",
        wrong_line="我想要的是一点认真陪伴，不是一句敷衍哦。",
        timeout_animation="cry",
        timeout_line="你先忙，我把这三分钟留到你有空的时候。",
    ),
    SweetTask(
        key="compliment",
        prompt="今天可以认真夸我一句吗？",
        choices=(
            TaskChoice("今天的你超级可爱 ✨", True),
            TaskChoice("今天和平时差不多"),
            TaskChoice("暂时想不到"),
        ),
        success_animation="heart",
        success_line="这句我收下了。作为交换，我也要认真夸夸你。",
        wrong_animation="angry",
        wrong_line="夸人要具体一点才显得有诚意，重新组织一下语言吧。",
        timeout_animation="pout",
        timeout_line="没等到也没关系，晚一点记得补上一句。",
    ),
    SweetTask(
        key="date",
        prompt="忙完这一小段，我们去做什么好呢？",
        choices=(
            TaskChoice("牵手散步看晚霞 🌇", True),
            TaskChoice("继续各自忙工作"),
            TaskChoice("到时候再临时决定"),
        ),
        success_animation="happy",
        success_line="说定啦！我已经开始期待和你一起慢慢走一会儿。",
        wrong_animation="stomp",
        wrong_line="这个计划里好像少了我们两个人的相处时间。",
        timeout_animation="cry",
        timeout_line="那我先留着这个问题，等你空下来再认真计划。",
    ),
    SweetTask(
        key="attention",
        prompt="我来找你聊天了，你会用哪句话开场？",
        choices=(
            TaskChoice("今天过得怎么样？💕", True),
            TaskChoice("有什么事快说"),
            TaskChoice("等我忙完再聊"),
        ),
        success_animation="hug",
        success_line="这句开场我喜欢。那就从今天最开心的事聊起吧。",
        wrong_animation="pout",
        wrong_line="语气可以再温柔一点，我是真的想和你聊聊天。",
        timeout_animation="cry",
        timeout_line="你先专心忙，等你回头时记得主动来找我。",
    ),
    SweetTask(
        key="morning_stretch",
        prompt="新的一天开始啦，先一起做什么？",
        choices=(
            TaskChoice("伸个懒腰，再慢慢进入状态 ☀️", True),
            TaskChoice("立刻坐着不动一整天"),
            TaskChoice("直接跳过早晨"),
        ),
        success_animation="stretch",
        success_line="好舒服！肩膀放松以后，今天也会顺利一点。",
        wrong_animation="pout",
        wrong_line="早晨还是要给身体一点醒来的时间呀。",
        timeout_animation="pout",
        timeout_line="我先替你记着，忙完这一下要起来活动哦。",
        scene="清晨",
        periods=("morning",),
    ),
    SweetTask(
        key="breakfast_choice",
        prompt="早餐时间，你觉得怎样搭配更舒服？",
        choices=(
            TaskChoice("热饮加一份认真吃完的早餐 ☕", True),
            TaskChoice("只看菜单不吃"),
            TaskChoice("空着肚子硬撑"),
        ),
        success_animation="sip",
        success_line="这才对嘛，吃饱了才有力气照顾今天的心情。",
        wrong_animation="stomp",
        wrong_line="空腹可不是提高效率的办法，再选一次。",
        timeout_animation="pout",
        timeout_line="早餐不能一直等你，记得给自己留几分钟。",
        scene="早餐时间",
        periods=("morning",),
    ),
    SweetTask(
        key="lunch_break",
        prompt="到饭点了，今天的午休怎么安排？",
        choices=(
            TaskChoice("好好吃饭，再散步十分钟 🍚", True),
            TaskChoice("继续盯着屏幕"),
            TaskChoice("用零食代替正餐"),
        ),
        success_animation="eat",
        success_line="批准！吃饭的时候就先把工作放到一边。",
        wrong_animation="angry",
        wrong_line="身体已经在提醒你了，不能一直假装没听见。",
        timeout_animation="pout",
        timeout_line="我先把午休提醒放在这里，记得回来兑现。",
        scene="午间",
        periods=("day",),
    ),
    SweetTask(
        key="afternoon_eye_break",
        prompt="下午容易犯困，现在最适合做什么？",
        choices=(
            TaskChoice("看看远处，活动一下肩颈 👀", True),
            TaskChoice("再把屏幕调亮一点"),
            TaskChoice("保持同一个姿势"),
        ),
        success_animation="stretch",
        success_line="很好，眼睛和肩膀都收到休息时间啦。",
        wrong_animation="pout",
        wrong_line="我可是在认真提醒你，不要拿身体和进度交换。",
        timeout_animation="cry",
        timeout_line="你又忙得没看见我……下一次一定要回应哦。",
        scene="午后休息",
        periods=("day",),
    ),
    SweetTask(
        key="evening_walk",
        prompt="傍晚的光很好，我们给今天加个什么结尾？",
        choices=(
            TaskChoice("一起散步，顺便聊聊今天 🌇", True),
            TaskChoice("各自继续刷手机"),
            TaskChoice("把计划留给下个月"),
        ),
        success_animation="happy",
        success_line="说定啦！我最喜欢这种不赶时间的散步。",
        wrong_animation="stomp",
        wrong_line="这么好的傍晚，当然要给我们留一点时间。",
        timeout_animation="pout",
        timeout_line="晚霞快过去了，不过我们的散步还可以补上。",
        scene="傍晚",
        periods=("evening",),
    ),
    SweetTask(
        key="night_goodnight",
        prompt="已经很晚了，睡前最后一件事选什么？",
        choices=(
            TaskChoice("抱一下，说晚安，然后好好休息 🌙", True),
            TaskChoice("再熬两个小时"),
            TaskChoice("躺下继续刷屏"),
        ),
        success_animation="hug",
        success_line="晚安抱抱收到。今天辛苦了，剩下的明天再做。",
        wrong_animation="angry",
        wrong_line="不可以继续透支睡眠，我会认真监督你的。",
        timeout_animation="cry",
        timeout_line="你是不是已经困得没看到我了？那就快去睡吧。",
        scene="深夜",
        periods=("night",),
    ),
    SweetTask(
        key="rain_umbrella",
        prompt="外面在下雨，出门前最重要的是什么？",
        choices=(
            TaskChoice("带好伞，我们走慢一点 ☔", True),
            TaskChoice("假装雨不存在"),
            TaskChoice("一路冲过去"),
        ),
        success_animation="umbrella",
        success_line="伞准备好啦。你靠近一点，我们一起走。",
        wrong_animation="pout",
        wrong_line="淋湿感冒可不好玩，还是乖乖带伞吧。",
        timeout_animation="cry",
        timeout_line="雨还在下，我先替你守着这把伞。",
        scene="雨天",
        weathers=("rain",),
    ),
    SweetTask(
        key="rain_cozy_time",
        prompt="雨声很适合慢下来，你想和我做什么？",
        choices=(
            TaskChoice("找本书，一起安静待一会儿 📖", True),
            TaskChoice("各自戴耳机不说话"),
            TaskChoice("继续追着工作跑"),
        ),
        success_animation="read",
        success_line="好呀，看到喜欢的句子记得念给我听。",
        wrong_animation="pout",
        wrong_line="雨天这么安静，正适合分一点陪伴给彼此。",
        timeout_animation="cry",
        timeout_line="那我先翻一页，给你留好旁边的位置。",
        scene="雨天宅家",
        weathers=("rain",),
    ),
    SweetTask(
        key="sunny_walk",
        prompt="今天阳光不错，空下来最适合去哪儿？",
        choices=(
            TaskChoice("去公园走走，顺便晒晒太阳 🌤️", True),
            TaskChoice("一直拉着窗帘"),
            TaskChoice("继续坐在原地"),
        ),
        success_animation="happy",
        success_line="好！阳光和散步都已经加入今天的计划。",
        wrong_animation="pout",
        wrong_line="偶尔出去透透气，回来反而会更有精神。",
        timeout_animation="pout",
        timeout_line="阳光不会一直等，不过下一次我们要抓住它。",
        scene="晴天",
        periods=("day", "evening"),
        weathers=("sunny",),
    ),
    SweetTask(
        key="sunny_photo",
        prompt="光线这么好，你想留下什么照片？",
        choices=(
            TaskChoice("拍一张我们的今日纪念照 📷", True),
            TaskChoice("只拍桌面"),
            TaskChoice("等到天黑再说"),
        ),
        success_animation="heart",
        success_line="记住要把我拍得可爱一点，这张我要收藏。",
        wrong_animation="stomp",
        wrong_line="好天气当然要留下有我们两个人的纪念呀。",
        timeout_animation="pout",
        timeout_line="我先把这个瞬间记在心里，照片下次再补。",
        scene="晴日留影",
        weathers=("sunny",),
    ),
    SweetTask(
        key="cloudy_read",
        prompt="阴天很安静，适合挑一种慢节奏活动——",
        choices=(
            TaskChoice("泡杯热饮，一起读几页书 📚", True),
            TaskChoice("不停刷新消息"),
            TaskChoice("什么都不做只发呆"),
        ),
        success_animation="read",
        success_line="这个安排刚刚好，今天就慢一点也没关系。",
        wrong_animation="pout",
        wrong_line="阴天也可以很舒服，别让信息把时间全部占走。",
        timeout_animation="cry",
        timeout_line="我把书签放好了，等你回来一起继续。",
        scene="多云",
        weathers=("cloudy",),
    ),
    SweetTask(
        key="cold_warm_drink",
        prompt="今天有点冷，哪一种照顾最及时？",
        choices=(
            TaskChoice("递一杯热饮，再提醒我加件衣服 ☕", True),
            TaskChoice("让我喝冰水"),
            TaskChoice("说忍一忍就过去了"),
        ),
        success_animation="sip",
        success_line="暖起来了。你认真照顾我的样子特别可靠。",
        wrong_animation="angry",
        wrong_line="这种天气不能硬扛，保暖要放在第一位。",
        timeout_animation="pout",
        timeout_line="我先把杯子捧热，也给你留一杯。",
        scene="寒冷天气",
        weathers=("cold",),
    ),
    SweetTask(
        key="cold_cuddle",
        prompt="手有点凉，借我什么最合适？",
        choices=(
            TaskChoice("把手给你，再抱一会儿 🤗", True),
            TaskChoice("借你一张便签"),
            TaskChoice("让你自己想办法"),
        ),
        success_animation="hug",
        success_line="这下暖和多了。冬天的抱抱要比平时久一点。",
        wrong_animation="pout",
        wrong_line="我想借的是你的温度，不是办公用品啦。",
        timeout_animation="cry",
        timeout_line="没关系，我先把手藏进袖子里等你。",
        scene="降温",
        weathers=("cold",),
    ),
    SweetTask(
        key="hot_cooldown",
        prompt="天气很热，我们怎么舒服地降降温？",
        choices=(
            TaskChoice("喝点清凉饮品，再去阴凉处休息 🧊", True),
            TaskChoice("在太阳下继续暴走"),
            TaskChoice("完全不补水"),
        ),
        success_animation="sip",
        success_line="清爽多了！你也要记得及时喝水。",
        wrong_animation="stomp",
        wrong_line="高温天可不能逞强，补水和休息都很重要。",
        timeout_animation="pout",
        timeout_line="我先把水放在手边，你看到就喝几口。",
        scene="炎热天气",
        weathers=("hot",),
    ),
    SweetTask(
        key="snow_photo",
        prompt="如果窗外下雪了，第一件浪漫的小事是什么？",
        choices=(
            TaskChoice("一起看雪，再拍张合照 ❄️", True),
            TaskChoice("假装没看见"),
            TaskChoice("只讨论路况"),
        ),
        success_animation="happy",
        success_line="那就说好啦，第一场雪要和喜欢的人一起看。",
        wrong_animation="pout",
        wrong_line="实用问题要考虑，浪漫也不能完全省略呀。",
        timeout_animation="cry",
        timeout_line="雪景先替你留在这里，等你抬头的时候一起看。",
        scene="下雪",
        weathers=("snow",),
    ),
    SweetTask(
        key="snow_warm_cup",
        prompt="看完雪回到室内，最想捧住什么？",
        choices=(
            TaskChoice("一杯热饮，还有你的手 ☕", True),
            TaskChoice("一杯冰块"),
            TaskChoice("继续站在门外"),
        ),
        success_animation="sip",
        success_line="热气和你的手都很暖，这个答案满分。",
        wrong_animation="angry",
        wrong_line="刚看完雪当然要好好暖回来。",
        timeout_animation="pout",
        timeout_line="杯子已经温好了，等你回来一起喝。",
        scene="雪后",
        weathers=("snow",),
    ),
    SweetTask(
        key="weekend_plan",
        prompt="周末想留一段只属于我们的时间，选哪种？",
        choices=(
            TaskChoice("睡到自然醒，再一起慢慢出门 💕", True),
            TaskChoice("把日程排满工作"),
            TaskChoice("到周日晚再考虑"),
        ),
        success_animation="happy",
        success_line="这才像周末嘛，我已经开始期待了。",
        wrong_animation="stomp",
        wrong_line="周末也要给生活和我们留一点空白。",
        timeout_animation="pout",
        timeout_line="我先把时间空出来，你想好以后来找我。",
        scene="周末",
        day_types=("weekend",),
    ),
    SweetTask(
        key="weekend_reading",
        prompt="周末下午不赶时间，我们可以——",
        choices=(
            TaskChoice("各挑一本书，靠在一起看 📖", True),
            TaskChoice("各忙各的直到深夜"),
            TaskChoice("不停处理通知"),
        ),
        success_animation="read",
        success_line="安静待在一起也很幸福，读累了就聊两句。",
        wrong_animation="pout",
        wrong_line="周末不用把每一分钟都变成任务。",
        timeout_animation="cry",
        timeout_line="我先给你留好靠过来的位置。",
        scene="周末午后",
        periods=("day",),
        day_types=("weekend",),
    ),
    SweetTask(
        key="weekday_encouragement",
        prompt="工作日进行到这里，你最想收到哪句话？",
        choices=(
            TaskChoice("你已经做得很好了，慢一点也没关系 ✨", True),
            TaskChoice("这还不够快"),
            TaskChoice("继续加任务"),
        ),
        success_animation="heart",
        success_line="这句话也送给你。认真生活的人值得被好好鼓励。",
        wrong_animation="pout",
        wrong_line="今天已经够辛苦了，鼓励应该温柔一点。",
        timeout_animation="cry",
        timeout_line="没等到回答也没关系，我还是要先夸夸你。",
        scene="工作日",
        day_types=("weekday",),
    ),
    SweetTask(
        key="focus_checkpoint",
        prompt="看你正在{activity_label}，下一小段想用哪种节奏？",
        choices=(
            TaskChoice("专注 20 分钟，再伸个懒腰 ⏱", True),
            TaskChoice("一边做一边刷消息"),
            TaskChoice("硬撑到完全没力气"),
        ),
        success_animation="stretch",
        success_line="好，我先安静陪你。完成这一小段，我们再一起休息。",
        wrong_animation="pout",
        wrong_line="专注和休息都要有边界，别把自己困在混乱里。",
        timeout_animation="read",
        timeout_line="你继续专心，我把这次休息提醒替你记下。",
        scene="专注支线",
        activities=("coding", "writing", "spreadsheet", "design"),
    ),
    SweetTask(
        key="save_progress",
        prompt="{activity_label}进行到这里，最稳妥的小动作是什么？",
        choices=(
            TaskChoice("保存进度，再给自己一个小小肯定 💾", True),
            TaskChoice("完全不保存继续冲"),
            TaskChoice("把所有窗口一起关掉"),
        ),
        success_animation="photo_pose",
        success_line="进度保存，努力也存档。来，给今天认真的你拍张纪念照。",
        wrong_animation="stomp",
        wrong_line="先保护好进度，灵感和耐心才不会白费呀。",
        timeout_animation="pout",
        timeout_line="我不打断你，不过看到这里记得按一下保存。",
        scene="进度存档",
        activities=("coding", "writing", "spreadsheet", "design"),
    ),
    SweetTask(
        key="meeting_reset",
        prompt="一场会议之后，怎样把注意力接回来？",
        choices=(
            TaskChoice("喝口水，记下三条结论再继续 📝", True),
            TaskChoice("立刻无缝进入下一场"),
            TaskChoice("假装什么都没听见"),
        ),
        success_animation="sip",
        success_line="整理好了就轻松多啦。剩下的事情我们一件件来。",
        wrong_animation="pout",
        wrong_line="给大脑半分钟收尾，反而会更快进入下一件事。",
        timeout_animation="read",
        timeout_line="你先处理，我把“整理会议结论”放在旁边提醒你。",
        scene="会议之后",
        activities=("meeting",),
    ),
    SweetTask(
        key="browse_break",
        prompt="网页越开越多了，接下来怎么做更舒服？",
        choices=(
            TaskChoice("留下真正需要的，其余先关掉 🌿", True),
            TaskChoice("再开十个标签页"),
            TaskChoice("让通知一直闪"),
        ),
        success_animation="happy",
        success_line="屏幕清爽一点，脑袋也会跟着松一口气。",
        wrong_animation="stomp",
        wrong_line="信息太多会偷走注意力，先给自己腾一点空间吧。",
        timeout_animation="pout",
        timeout_line="没关系，等你查完这一条再慢慢收拾标签页。",
        scene="浏览支线",
        activities=("browsing",),
    ),
    SweetTask(
        key="music_share",
        prompt="听到喜欢的歌时，想怎么把这一刻留下来？",
        choices=(
            TaskChoice("收藏下来，晚点分享给彼此 🎵", True),
            TaskChoice("听完马上忘掉"),
            TaskChoice("立刻切到下一百首"),
        ),
        success_animation="heart",
        success_line="好呀，一首歌也可以变成我们今天的小小共同记忆。",
        wrong_animation="pout",
        wrong_line="喜欢的瞬间值得多停留几秒。",
        timeout_animation="sip",
        timeout_line="你先听，我就在旁边跟着节拍轻轻晃一会儿。",
        scene="音乐支线",
        activities=("music",),
    ),
    SweetTask(
        key="game_break",
        prompt="这一局结束以后，怎样休息最舒服？",
        choices=(
            TaskChoice("站起来活动一下，再决定要不要继续 🎮", True),
            TaskChoice("马上连开下一局"),
            TaskChoice("一直坐到忘记时间"),
        ),
        success_animation="stretch",
        success_line="输赢先放一边，肩颈舒服才是长期快乐。",
        wrong_animation="stomp",
        wrong_line="先起来走两步嘛，下一局状态会更好。",
        timeout_animation="pout",
        timeout_line="我等你这局结束，记得兑现休息约定。",
        scene="游戏间隙",
        activities=("gaming",),
    ),
    SweetTask(
        key="reading_rest",
        prompt="看了好一会儿文字，眼睛想收到哪种照顾？",
        choices=(
            TaskChoice("看看远处，慢慢眨眼二十秒 👀", True),
            TaskChoice("把字调得更小"),
            TaskChoice("继续盯着不动"),
        ),
        success_animation="gaze",
        success_line="很好，远处也看到了。现在再回来读，会舒服很多。",
        wrong_animation="pout",
        wrong_line="眼睛也需要换换焦点，不要一直让它加班。",
        timeout_animation="read",
        timeout_line="这一页看完就休息一下，我陪你记着。",
        scene="阅读间隙",
        activities=("reading",),
    ),
    SweetTask(
        key="hydration_check",
        prompt="杯子就在附近，现在最值得做的小事是？",
        choices=(
            TaskChoice("喝几口水，再继续手上的事 💧", True),
            TaskChoice("等口渴到不行再说"),
            TaskChoice("只看一眼杯子"),
        ),
        success_animation="sip",
        success_line="这几口水算我监督成功。照顾自己也算今天的进度。",
        wrong_animation="pout",
        wrong_line="别和自己的身体讨价还价，先喝一口嘛。",
        timeout_animation="sip",
        timeout_line="我先示范一下，你忙完这一句就跟上。",
        scene="轻提醒",
    ),
    SweetTask(
        key="posture_reset",
        prompt="肩膀有点紧的时候，选哪个三十秒动作？",
        choices=(
            TaskChoice("放下肩膀，抬头伸展一下 🙆", True),
            TaskChoice("继续缩着不动"),
            TaskChoice("把椅子坐得更歪"),
        ),
        success_animation="stretch",
        success_line="呼——这样好多了。舒服地工作，比硬撑更厉害。",
        wrong_animation="stomp",
        wrong_line="这个姿势看起来就很累，听我的，动一下。",
        timeout_animation="pout",
        timeout_line="我把伸懒腰留到下一段落，不许再忘啦。",
        scene="身体支线",
    ),
    SweetTask(
        key="desk_tidy",
        prompt="桌面有点乱，先收拾哪一种东西最有成就感？",
        choices=(
            TaskChoice("只整理眼前这一小块 ✨", True),
            TaskChoice("一次收拾整个房间"),
            TaskChoice("继续把东西往上堆"),
        ),
        success_animation="happy",
        success_line="小范围完成也很棒，清爽一点就多一点好心情。",
        wrong_animation="pout",
        wrong_line="目标太大容易不想开始，我们只整理一小块就好。",
        timeout_animation="read",
        timeout_line="先不催你，等这件事结束再收拾三样东西。",
        scene="桌面支线",
    ),
    SweetTask(
        key="mood_check",
        prompt="如果给此刻的心情选一个照顾方式，你会选？",
        choices=(
            TaskChoice("先承认感受，再做一件小事照顾自己 💗", True),
            TaskChoice("假装什么都没发生"),
            TaskChoice("责怪自己不够开心"),
        ),
        success_animation="hug",
        success_line="无论现在是什么心情，都可以在我这里放一会儿。",
        wrong_animation="cry",
        wrong_line="难过和疲惫都不是错误，不要站到自己的对立面。",
        timeout_animation="heart",
        timeout_line="不想回答也没关系，我先给你一个安静的拥抱。",
        scene="心情支线",
    ),
    SweetTask(
        key="camera_pose",
        prompt="突然想给今天的你留张纪念照，要摆什么 pose？",
        choices=(
            TaskChoice("一起比个小小的耶 📷", True),
            TaskChoice("背对镜头假装没看见"),
            TaskChoice("等到永远不拍"),
        ),
        success_animation="photo_pose",
        success_line="三、二、一——笑一个！这张就叫《认真生活的我们》。",
        wrong_animation="pout",
        wrong_line="偶尔记录普通的一天，以后回看也会觉得很珍贵。",
        timeout_animation="photo_pose",
        timeout_line="没关系，我先拍一张自然状态，等你有空再补 pose。",
        scene="拍照时间",
    ),
    SweetTask(
        key="weekend_recipe",
        prompt="周末想一起做点吃的，哪种计划最有参与感？",
        choices=(
            TaskChoice("选一道简单菜，一人负责一半 🥣", True),
            TaskChoice("全交给一个人忙"),
            TaskChoice("只负责站在旁边点评"),
        ),
        success_animation="eat",
        success_line="成交！做得漂不漂亮不重要，一起忙活才好玩。",
        wrong_animation="stomp",
        wrong_line="一起做饭的重点就是“一起”呀。",
        timeout_animation="sip",
        timeout_line="菜单先保留，等你饿的时候我们再认真研究。",
        scene="周末厨房",
        day_types=("weekend",),
    ),
    SweetTask(
        key="evening_memory",
        prompt="今天快收尾了，想把哪一种瞬间放进记忆盒子？",
        choices=(
            TaskChoice("一件微小但真实的开心事 🌙", True),
            TaskChoice("只记住没做完的事"),
            TaskChoice("把今天全部否定"),
        ),
        success_animation="heart",
        success_line="小事也值得收藏。以后想起今天，会有一盏小灯亮着。",
        wrong_animation="pout",
        wrong_line="今天不只由遗憾组成，再找找那一点点好的地方。",
        timeout_animation="read",
        timeout_line="想不到也没关系，平安走到这里本身就值得记住。",
        scene="晚间回忆",
        periods=("evening", "night"),
    ),
)


def choose_task(
    *,
    exclude_key: str = "",
    part: str = "",
    weather: str = "",
    weekend: bool | None = None,
    activity: str = "",
    context: dict[str, object] | None = None,
    rng: random.Random | None = None,
) -> SweetTask:
    rng = rng or random
    day_type = "weekend" if weekend else "weekday"
    pool: list[SweetTask] = []
    weights: list[int] = []
    for task in SWEET_TASKS:
        if task.key == exclude_key:
            continue
        if task.periods and part not in task.periods:
            continue
        if task.weathers and weather not in task.weathers:
            continue
        if task.day_types and (weekend is None or day_type not in task.day_types):
            continue
        if task.activities and activity not in task.activities:
            continue
        weight = 1
        if task.periods:
            weight += 5
        if task.weathers:
            weight += 8
        if task.day_types:
            weight += 4
        if task.activities:
            weight += 9
        pool.append(task)
        weights.append(weight)
    if not pool:
        pool = [task for task in SWEET_TASKS if task.key != exclude_key] or list(SWEET_TASKS)
        weights = [1] * len(pool)
    task = rng.choices(pool, weights=weights, k=1)[0]
    choices = list(task.choices)
    rng.shuffle(choices)
    values = {"activity_label": "手上的事情"}
    values.update(context or {})

    def render(text: str) -> str:
        try:
            return text.format_map(values)
        except (KeyError, ValueError):
            return text

    rendered_choices = tuple(
        replace(choice, label=render(choice.label), feedback=render(choice.feedback))
        for choice in choices
    )
    return replace(
        task,
        prompt=render(task.prompt),
        choices=rendered_choices,
        success_line=render(task.success_line),
        wrong_line=render(task.wrong_line),
        timeout_line=render(task.timeout_line),
    )


def correct_choice_count(task: SweetTask) -> int:
    return sum(choice.correct for choice in task.choices)
