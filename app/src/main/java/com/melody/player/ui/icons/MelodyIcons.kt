package com.melody.player.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 全部图标都用 [ImageVector] 的路径 DSL 在代码里画出来，工程内不存在任何位图资源。
 *
 * 为什么不用 material-icons-extended：本机 Gradle 缓存里没有这个包，而且它的图标是
 * 预烘焙好的对象，改不了笔画粗细。自己画的好处是：粗细、圆角、留白全部统一可控，
 * 24×24 视口下笔画宽度统一为 2.0，端点一律圆头，风格天然一致。
 *
 * 约定：
 *  - 视口固定 24×24，内容留出 2px 安全边距
 *  - 需要描边的图标用 [strokePath]，需要实心的用 [solidPath]
 *  - 填充色写黑色占位，实际显示时由 `Icon(tint = ...)` 统一染色
 */
object MelodyIcons {

    // ---------------------------------------------------------------- 播放控制

    /** 播放：实心三角。 */
    val Play: ImageVector = build("melody_play") {
        solidPath("triangle") {
            moveTo(7.0f, 4.6f)
            lineTo(19.4f, 12.0f)
            lineTo(7.0f, 19.4f)
            close()
        }
    }

    /** 暂停：两条圆角竖条。 */
    val Pause: ImageVector = build("melody_pause") {
        solidPath("bars") {
            roundedBar(6.4f, 4.8f, 10.2f, 19.2f, 1.7f)
            roundedBar(13.8f, 4.8f, 17.6f, 19.2f, 1.7f)
        }
    }

    /** 下一首：三角 + 竖条。 */
    val SkipNext: ImageVector = build("melody_skip_next") {
        solidPath("triangle") {
            moveTo(5.4f, 5.6f)
            lineTo(15.2f, 12.0f)
            lineTo(5.4f, 18.4f)
            close()
        }
        solidPath("bar") { roundedBar(16.6f, 5.4f, 18.8f, 18.6f, 1.0f) }
    }

    /** 上一首：竖条 + 三角。 */
    val SkipPrevious: ImageVector = build("melody_skip_previous") {
        solidPath("bar") { roundedBar(5.2f, 5.4f, 7.4f, 18.6f, 1.0f) }
        solidPath("triangle") {
            moveTo(18.6f, 5.6f)
            lineTo(8.8f, 12.0f)
            lineTo(18.6f, 18.4f)
            close()
        }
    }

    /** 快进（+10 秒）：双右尖角。 */
    val FastForward: ImageVector = build("melody_fast_forward") {
        strokePath("chevron1", width = 2.2f) {
            moveTo(12.2f, 5.6f); lineTo(18.4f, 12.0f); lineTo(12.2f, 18.4f)
        }
        strokePath("chevron2", width = 2.2f) {
            moveTo(5.2f, 5.6f); lineTo(11.4f, 12.0f); lineTo(5.2f, 18.4f)
        }
    }

    /** 快退（-10 秒）：双左尖角。 */
    val FastRewind: ImageVector = build("melody_fast_rewind") {
        strokePath("chevron1", width = 2.2f) {
            moveTo(11.8f, 5.6f); lineTo(5.6f, 12.0f); lineTo(11.8f, 18.4f)
        }
        strokePath("chevron2", width = 2.2f) {
            moveTo(18.8f, 5.6f); lineTo(12.6f, 12.0f); lineTo(18.8f, 18.4f)
        }
    }

    /** 随机播放：交叉双箭头。 */
    val Shuffle: ImageVector = build("melody_shuffle") {
        strokePath("cross1", width = 1.9f) {
            moveTo(3.4f, 7.0f); lineTo(8.2f, 7.0f); lineTo(15.8f, 17.0f); lineTo(19.4f, 17.0f)
        }
        strokePath("cross2", width = 1.9f) {
            moveTo(3.4f, 17.0f); lineTo(8.2f, 17.0f); lineTo(15.8f, 7.0f); lineTo(19.4f, 7.0f)
        }
        solidPath("headA") {
            moveTo(16.2f, 15.6f); lineTo(20.6f, 17.0f); lineTo(16.2f, 18.4f); close()
        }
        solidPath("headB") {
            moveTo(16.2f, 5.6f); lineTo(20.6f, 7.0f); lineTo(16.2f, 8.4f); close()
        }
    }

    /**
     * 顺序播放：一条直线 + 右向箭头。
     *
     * 与三个「循环系」图标刻意拉开形体差异：没有环、没有回头，只有单向流动，
     * 一眼就能和列表循环/单曲循环区分开。
     */
    val PlayOrder: ImageVector = build("melody_play_order") {
        strokePath("flow", width = 2.0f) { moveTo(3.6f, 12.0f); lineTo(17.6f, 12.0f) }
        solidPath("head") {
            moveTo(15.4f, 7.4f); lineTo(21.0f, 12.0f); lineTo(15.4f, 16.6f); close()
        }
    }

    /**
     * 列表循环：闭合圆角环 + 上边向右、下边向左的两个箭头。
     *
     * 箭头刻意压在**环的边缘线**上（而不是浮在环中间）：同色叠加后就是标准的
     * 「环 + 指向」图形，小尺寸下也不会糊成一团结。上一版把箭头放在环内部，
     * 24dp 时看起来像个蝴蝶结。
     */
    val Repeat: ImageVector = build("melody_repeat") {
        strokePath("loop", width = 1.9f, closed = true) {
            roundedRect(3.6f, 6.2f, 20.4f, 17.8f, 4.4f)
        }
        solidPath("headTop") { moveTo(13.2f, 4.4f); lineTo(17.0f, 6.2f); lineTo(13.2f, 8.0f); close() }
        solidPath("headBottom") { moveTo(10.8f, 16.0f); lineTo(7.0f, 17.8f); lineTo(10.8f, 19.6f); close() }
    }

    /** 单曲循环：与列表循环同构，中心加一个「1」。 */
    val RepeatOne: ImageVector = build("melody_repeat_one") {
        strokePath("loop", width = 1.9f, closed = true) {
            roundedRect(3.6f, 6.2f, 20.4f, 17.8f, 4.4f)
        }
        solidPath("headTop") { moveTo(13.2f, 4.4f); lineTo(17.0f, 6.2f); lineTo(13.2f, 8.0f); close() }
        solidPath("headBottom") { moveTo(10.8f, 16.0f); lineTo(7.0f, 17.8f); lineTo(10.8f, 19.6f); close() }
        // 「1」要比其它图标里的笔画粗一档，否则在 22dp 下会糊成一个点
        strokePath("digit", width = 2.1f) {
            moveTo(10.6f, 11.2f); lineTo(12.5f, 9.8f); lineTo(12.5f, 14.6f)
        }
    }

    // ---------------------------------------------------------------- 内容

    /**
     * 品牌音符：单八分音符，与启动图标是同一套几何。
     *
     * 启动图标画在 108 视口里、按真实乐谱比例推导（长轴 26 / 短轴 19 的符头、
     * 倾角 20°、符干宽 = 符头长轴 13%、符干右缘与符头最右点相切）。这里把同一组
     * 坐标按 `x' = 12 + (x - 54) * 0.28` 缩到 24 视口，图形仍然精确居中于 (12, 12)，
     * 不会因为「另画一版近似的」而在 App 内外长得不一样。
     *
     * 之前这里是「两个符头 + 符梁」的通用音符，跟新启动图标对不上，所以替换掉。
     */
    val MusicNote: ImageVector = build("melody_music_note") {
        // 符头：倾角 -20° 的椭圆，由两段半圆弧闭合
        solidPath("head") {
            moveTo(13.05f, 15.92f)
            arcTo(3.64f, 2.66f, -20f, false, true, 6.21f, 18.41f)
            arcTo(3.64f, 2.66f, -20f, false, true, 13.05f, 15.92f)
            close()
        }
        // 符干
        solidPath("stem") {
            moveTo(12.22f, 4.05f)
            lineTo(13.17f, 4.05f)
            lineTo(13.17f, 17.17f)
            lineTo(12.22f, 17.17f)
            close()
        }
        // 符尾：上缘外弧 + 下缘内弧，末端收尖
        solidPath("flag") {
            moveTo(12.22f, 4.05f)
            curveTo(15.11f, 4.26f, 17.40f, 5.83f, 17.91f, 8.85f)
            curveTo(16.59f, 7.20f, 14.77f, 5.99f, 12.22f, 5.88f)
            close()
        }
    }

    /**
     * 品牌标：五根圆角竖条（声波脉冲），与启动图标、通知图标同一套几何。
     *
     * **App 内凡是要表达"这是 Melody"的地方统一用它**（底栏音乐库、默认封面占位、
     * 关于页），不再用 [MusicNote] —— 那是"音乐"概念的通用符号，不是品牌。
     *
     * 高度与不透明度都照抄启动图标（100/180/262/180/100，alpha 0.78/0.92/1）：
     * 中间最高最深、两侧对称收敛，这个「透明度层次」正是这套 logo 的识别特征，
     * 去掉层次就退化成五根呆板的等宽柱子。tint 染色时 alpha 会保留。
     *
     * 比例取自启动图标：条宽 : 间距 = 44 : 30。视口 24，内容宽 16、高 14，居中于画布。
     */
    val PulseBars: ImageVector = build("melody_pulse_bars") {
        solidPath("barLeft2", alpha = 0.78f) { roundedRect(4.0f, 9.4f, 6.1f, 14.6f, 1.05f) }
        solidPath("barLeft1", alpha = 0.92f) { roundedRect(7.475f, 7.3f, 9.575f, 16.7f, 1.05f) }
        solidPath("barCenter") { roundedRect(10.95f, 5.0f, 13.05f, 19.0f, 1.05f) }
        solidPath("barRight1", alpha = 0.92f) { roundedRect(14.425f, 7.3f, 16.525f, 16.7f, 1.05f) }
        solidPath("barRight2", alpha = 0.78f) { roundedRect(17.9f, 9.4f, 20.0f, 14.6f, 1.05f) }
    }

    /**
     * 联网获取：云 + 下箭头。
     *
     * 云由三个实心圆和一条圆角横杠叠成 —— 同色填充视觉上就是并集，不需要布尔运算。
     * 三个圆**底边取齐**（y = 12.55），再加上横杠就得到平坦的云底；
     * 左右两个小圆故意做得比中间小，否则只剩两个凸起时会读成一颗心。
     *
     * 箭头画在云的下方而不是云里：云是实心的，叠上去就看不见了。
     * 整组图元包围盒 y 4.05~19.75，重心 11.9 与视口中心重合。
     */
    val CloudDownload: ImageVector = build("melody_cloud_download") {
        solidPath("cloud") {
            circle(12.0f, 8.30f, 4.25f)
            circle(7.60f, 9.80f, 2.75f)
            circle(16.40f, 9.80f, 2.75f)
            roundedBar(4.85f, 11.35f, 19.15f, 12.55f, 0.6f)
        }
        strokePath("shaft", width = 2.0f) { moveTo(12.0f, 14.05f); lineTo(12.0f, 18.35f) }
        solidPath("head") {
            moveTo(9.7f, 16.55f); lineTo(12.0f, 19.75f); lineTo(14.3f, 16.55f); close()
        }
    }

    /** 播放队列：三条列表线 + 播放三角。 */
    val QueueList: ImageVector = build("melody_queue") {
        strokePath("lines", width = 1.9f) {
            moveTo(3.4f, 6.4f); lineTo(13.4f, 6.4f)
        }
        strokePath("lines2", width = 1.9f) {
            moveTo(3.4f, 12.0f); lineTo(13.4f, 12.0f)
        }
        strokePath("lines3", width = 1.9f) {
            moveTo(3.4f, 17.6f); lineTo(9.4f, 17.6f)
        }
        solidPath("tri") {
            moveTo(16.2f, 9.6f); lineTo(21.2f, 12.0f); lineTo(16.2f, 14.4f); close()
        }
    }

    /** 歌词：文本行，第二行用实心点强调「当前行」。 */
    val Lyrics: ImageVector = build("melody_lyrics") {
        strokePath("l1", width = 1.9f) { moveTo(3.6f, 5.6f); lineTo(20.4f, 5.6f) }
        solidPath("dot") { circle(4.9f, 12.0f, 1.6f) }
        strokePath("l2", width = 1.9f) { moveTo(8.6f, 12.0f); lineTo(20.4f, 12.0f) }
        strokePath("l3", width = 1.9f) { moveTo(3.6f, 18.4f); lineTo(16.4f, 18.4f) }
    }

    /** 封面：圆角方框 + 光晕 + 山形。 */
    val AlbumArt: ImageVector = build("melody_album_art") {
        strokePath("frame", width = 1.9f, closed = true) {
            roundedRect(3.4f, 3.4f, 20.6f, 20.6f, 3.6f)
        }
        solidPath("sun") { circle(9.0f, 8.8f, 1.7f) }
        strokePath("hill", width = 1.9f) {
            moveTo(4.6f, 17.4f); lineTo(10.2f, 11.0f); lineTo(14.4f, 15.6f); lineTo(16.6f, 13.4f); lineTo(19.4f, 16.6f)
        }
    }

    // ---------------------------------------------------------------- 操作

    /** 搜索：圆 + 手柄。 */
    val Search: ImageVector = build("melody_search") {
        strokePath("lens", width = 2.0f, closed = true) { circle(10.6f, 10.6f, 6.3f) }
        strokePath("handle", width = 2.2f) { moveTo(15.3f, 15.3f); lineTo(20.5f, 20.5f) }
    }

    /** 关闭：两条对角线。 */
    val Close: ImageVector = build("melody_close") {
        strokePath("x1", width = 2.0f) { moveTo(6.0f, 6.0f); lineTo(18.0f, 18.0f) }
        strokePath("x2", width = 2.0f) { moveTo(18.0f, 6.0f); lineTo(6.0f, 18.0f) }
    }

    /** 收起：向下箭头。 */
    val ChevronDown: ImageVector = build("melody_chevron_down") {
        strokePath("v", width = 2.2f) { moveTo(5.6f, 9.0f); lineTo(12.0f, 15.4f); lineTo(18.4f, 9.0f) }
    }

    /** 返回：向左箭头。 */
    val ChevronLeft: ImageVector = build("melody_chevron_left") {
        strokePath("v", width = 2.2f) { moveTo(14.8f, 5.4f); lineTo(8.4f, 12.0f); lineTo(14.8f, 18.6f) }
    }

    /** 更多：三个圆点。 */
    val MoreVertical: ImageVector = build("melody_more_vertical") {
        solidPath("dots") {
            circle(12.0f, 5.4f, 1.85f)
            circle(12.0f, 12.0f, 1.85f)
            circle(12.0f, 18.6f, 1.85f)
        }
    }

    /** 勾选。 */
    val Check: ImageVector = build("melody_check") {
        strokePath("tick", width = 2.2f) { moveTo(4.8f, 12.6f); lineTo(9.8f, 17.6f); lineTo(19.2f, 6.6f) }
    }

    /** 排序：三条递减横线。 */
    val Sort: ImageVector = build("melody_sort") {
        strokePath("l1", width = 1.9f) { moveTo(3.6f, 6.4f); lineTo(20.4f, 6.4f) }
        strokePath("l2", width = 1.9f) { moveTo(3.6f, 12.0f); lineTo(14.4f, 12.0f) }
        strokePath("l3", width = 1.9f) { moveTo(3.6f, 17.6f); lineTo(8.6f, 17.6f) }
    }

    /** 导入本地音乐：下箭头 + 托盘。 */
    val ImportMusic: ImageVector = build("melody_import") {
        strokePath("tray", width = 2.0f) {
            moveTo(4.0f, 14.0f); lineTo(4.0f, 19.0f); lineTo(20.0f, 19.0f); lineTo(20.0f, 14.0f)
        }
        strokePath("shaft", width = 2.0f) { moveTo(12.0f, 3.6f); lineTo(12.0f, 14.2f) }
        strokePath("head", width = 2.0f) { moveTo(7.4f, 9.8f); lineTo(12.0f, 14.4f); lineTo(16.6f, 9.8f) }
    }

    /** 文件夹：用于「从文件选择」。 */
    val Folder: ImageVector = build("melody_folder") {
        strokePath("body", width = 1.9f, closed = true) {
            moveTo(3.6f, 6.2f)
            lineTo(9.2f, 6.2f)
            lineTo(11.0f, 8.6f)
            lineTo(20.4f, 8.6f)
            lineTo(20.4f, 18.4f)
            lineTo(3.6f, 18.4f)
            close()
        }
    }

    /** 重新扫描：四分之三圆弧 + 箭头。 */
    val Refresh: ImageVector = build("melody_refresh") {
        strokePath("arc", width = 2.0f) {
            moveTo(5.4f, 12.0f)
            arcTo(6.6f, 6.6f, 0f, false, true, 12.0f, 5.4f)
            arcTo(6.6f, 6.6f, 0f, false, true, 18.6f, 12.0f)
            arcTo(6.6f, 6.6f, 0f, false, true, 12.0f, 18.6f)
        }
        solidPath("head") {
            moveTo(12.6f, 15.4f); lineTo(8.0f, 18.8f); lineTo(12.6f, 21.0f); close()
        }
    }

    /**
     * 上下切换：两条平行的竖直箭头，一条向上、一条向下。
     *
     * 表示「上下两个方向各自对应一个动作」（上下滑动切歌）；
     * 与 [Refresh] 的环形箭头区分 —— 那个是"回到某个状态"，这个是"两个方向"。
     */
    val SwapVertical: ImageVector = build("melody_swap_vertical") {
        // 左侧：向上箭头（杆 + 头）
        strokePath("upStem", width = 2.0f) { moveTo(8.4f, 18.6f); lineTo(8.4f, 6.2f) }
        strokePath("upHead", width = 2.0f) { moveTo(4.8f, 9.8f); lineTo(8.4f, 6.0f); lineTo(12.0f, 9.8f) }
        // 右侧：向下箭头
        strokePath("downStem", width = 2.0f) { moveTo(15.6f, 5.4f); lineTo(15.6f, 17.8f) }
        strokePath("downHead", width = 2.0f) { moveTo(12.0f, 14.2f); lineTo(15.6f, 18.0f); lineTo(19.2f, 14.2f) }
    }

    /**
     * 设置：齿轮。
     *
     * 拆成「粗描边圆环 + 8 颗实心齿」两层来画 —— 粗描边天然自带中心孔，
     * 齿与圆环同色叠加即为并集，不需要去做路径布尔运算。
     */
    val Settings: ImageVector = build("melody_settings") {
        solidPath("teeth") {
            for (i in 0 until 8) {
                val angle = i * PI / 4.0
                val cx = 12.0f + (cos(angle) * 8.3f).toFloat()
                val cy = 12.0f + (sin(angle) * 8.3f).toFloat()
                circle(cx, cy, 2.05f)
            }
        }
        strokePath("ring", width = 5.1f) { circle(12.0f, 12.0f, 5.75f) }
    }

    /** 信息：圆 + i。 */
    val Info: ImageVector = build("melody_info") {
        strokePath("ring", width = 1.9f, closed = true) { circle(12.0f, 12.0f, 8.6f) }
        solidPath("dot") { circle(12.0f, 7.9f, 1.25f) }
        strokePath("stem", width = 1.9f) { moveTo(12.0f, 11.2f); lineTo(12.0f, 16.6f) }
    }

    /**
     * 主题模式：半明半暗的圆。
     *
     * 描边圆 + 右半实心 —— 「一半亮一半暗」是深浅色切换最省字的图形，
     * 比画个月亮/太阳更中性（月亮会让人以为是「夜间模式开关」而不是「跟随系统」）。
     */
    val Contrast: ImageVector = build("melody_contrast") {
        strokePath("ring", width = 1.9f, closed = true) { circle(12.0f, 12.0f, 8.4f) }
        solidPath("half") {
            moveTo(12.0f, 3.6f)
            arcToRelative(8.4f, 8.4f, 0f, false, true, 0f, 16.8f)
            close()
        }
    }

    /**
     * 主题色：调色板。
     *
     * 圆环里三颗色点，右下角空出来当拇指孔 —— 只留一颗点睛的实心圆会被读成
     * 「单色/取色器」，三点才像调色板。
     */
    val Palette: ImageVector = build("melody_palette") {
        strokePath("ring", width = 1.9f, closed = true) { circle(12.0f, 12.0f, 8.4f) }
        solidPath("dots") {
            circle(9.0f, 8.6f, 1.45f)
            circle(15.0f, 8.6f, 1.45f)
            circle(8.4f, 14.4f, 1.45f)
        }
    }

    /** 时长：小时钟，曲目行里用来表示「时长」列。 */
    val Clock: ImageVector = build("melody_clock") {
        strokePath("ring", width = 1.9f, closed = true) { circle(12.0f, 12.0f, 8.6f) }
        strokePath("hands", width = 1.9f) { moveTo(12.0f, 7.0f); lineTo(12.0f, 12.0f); lineTo(16.2f, 14.4f) }
    }

    /** 删除：垃圾桶。 */
    val Delete: ImageVector = build("melody_delete") {
        strokePath("lid", width = 1.9f) { moveTo(4.4f, 7.0f); lineTo(19.6f, 7.0f) }
        strokePath("handle", width = 1.9f) { moveTo(9.4f, 7.0f); lineTo(9.4f, 4.6f); lineTo(14.6f, 4.6f); lineTo(14.6f, 7.0f) }
        strokePath("body", width = 1.9f) {
            moveTo(6.4f, 7.0f); lineTo(7.4f, 19.4f); lineTo(16.6f, 19.4f); lineTo(17.6f, 7.0f)
        }
    }

    /**
     * 编辑：铅笔。
     *
     * 笔杆 + 笔尖两段折线拼出「斜着的铅笔」轮廓，笔尖与笔杆的交接处留出一小段
     * 分隔线，24dp 下能读出「这是支笔」而不是一根斜杠。
     */
    val Edit: ImageVector = build("melody_edit") {
        // 笔杆：斜向的平行四边形轮廓
        strokePath("body", width = 1.9f, closed = true) {
            moveTo(14.1f, 4.9f); lineTo(19.1f, 9.9f); lineTo(8.6f, 20.4f); lineTo(3.6f, 15.4f); close()
        }
        // 笔尖三角：斜向笔杆末端的小三角
        strokePath("tip", width = 1.6f, closed = true) {
            moveTo(15.6f, 3.4f); lineTo(20.6f, 8.4f); lineTo(18.3f, 10.7f); lineTo(13.3f, 5.7f); close()
        }
    }

    /** 归档：带盖的收纳盒。App 音乐库相关的动作统一用它。 */
    val Archive: ImageVector = build("melody_archive") {
        strokePath("lid", width = 1.9f, closed = true) { roundedRect(3.4f, 4.2f, 20.6f, 8.0f, 1.5f) }
        strokePath("box", width = 1.9f) {
            moveTo(5.1f, 8.1f); lineTo(5.1f, 18.6f); lineTo(18.9f, 18.6f); lineTo(18.9f, 8.1f)
        }
        strokePath("slot", width = 1.8f) { moveTo(9.8f, 12.4f); lineTo(14.2f, 12.4f) }
    }

    /**
     * 取消归档：档案盒里朝上的箭头（把东西从盒子里取出来）。
     *
     * 盒身与 [Archive] 完全同一套几何（盖子 + 盒体），只把盒子中间的横槽换成上箭头 ——
     * 同一族图标必须能一眼看出是同一族，只有中间那笔表示动作方向。
     *
     * 不用挂锁（[LockOpen]）表示这件事：挂锁的意思是「解密/解锁」，
     * 放在曲库行菜单里和「隐藏这首」并排时会读成"解锁这首歌"，与归档毫无关系。
     */
    val ArchiveOff: ImageVector = build("melody_archive_off") {
        strokePath("lid", width = 1.9f, closed = true) { roundedRect(3.4f, 4.2f, 20.6f, 8.0f, 1.5f) }
        strokePath("box", width = 1.9f) {
            moveTo(5.1f, 8.1f); lineTo(5.1f, 18.6f); lineTo(18.9f, 18.6f); lineTo(18.9f, 8.1f)
        }
        // 箭头：杆 + 头。两段分开描边，接点都带圆头，交在一起不会露尖角
        strokePath("arrowStem", width = 1.8f) { moveTo(12.0f, 16.2f); lineTo(12.0f, 11.5f) }
        strokePath("arrowHead", width = 1.8f) {
            moveTo(9.6f, 13.8f); lineTo(12.0f, 11.4f); lineTo(14.4f, 13.8f)
        }
    }

    /** 显示：眼睛。用在「恢复显示」这类动作上，与 [EyeOff] 是同一套几何，成对阅读。 */
    val Eye: ImageVector = build("melody_eye") {
        strokePath("lid", width = 1.9f, closed = true) {
            moveTo(2.9f, 12.0f)
            quadTo(12.0f, 4.6f, 21.1f, 12.0f)
            quadTo(12.0f, 19.4f, 2.9f, 12.0f)
        }
        solidPath("pupil") { circle(12.0f, 12.0f, 2.5f) }
    }

    /** 隐藏：同一只眼睛加一道斜杠。 */
    val EyeOff: ImageVector = build("melody_eye_off") {
        strokePath("lid", width = 1.9f, closed = true) {
            moveTo(3.4f, 12.4f)
            quadTo(12.0f, 6.4f, 20.6f, 12.4f)
            quadTo(12.0f, 18.6f, 3.4f, 12.4f)
        }
        solidPath("pupil") { circle(12.0f, 12.4f, 2.3f) }
        strokePath("slash", width = 2.1f) { moveTo(4.8f, 19.6f); lineTo(19.2f, 4.4f) }
    }

    // ---------------------------------------------------------------- 内部构建工具

    // -------------------------------------------------------- 加密音乐文件

    /**
     * 带音符的文件：加密音乐文件。
     *
     * 文件轮廓用「右上角折角」表达「这是一个文档」，这是文档类图标最不容易认错的形式；
     * 音符压在文档下半部分 —— 涂成实心符头、描边符干，是因为 24dp 下纯描边的音符
     * 会细到看不清，而全实心又跟文档轮廓打架。
     */
    val FileMusic: ImageVector = build("melody_file_music") {
        strokePath("sheet", width = 1.8f, closed = true) {
            moveTo(5.8f, 3.2f)
            lineTo(13.0f, 3.2f)
            lineTo(18.2f, 8.4f)
            lineTo(18.2f, 20.8f)
            lineTo(5.8f, 20.8f)
        }
        strokePath("fold", width = 1.6f) {
            moveTo(13.0f, 3.2f); lineTo(13.0f, 8.4f); lineTo(18.2f, 8.4f)
        }
        solidPath("noteHead") { circle(10.3f, 15.9f, 1.6f) }
        strokePath("noteStem", width = 1.7f) {
            moveTo(11.9f, 15.9f); lineTo(11.9f, 11.5f); lineTo(14.3f, 12.3f)
        }
    }

    /**
     * 打开的挂锁：解密。
     *
     * 「开着」这个状态靠**右侧锁梁悬在锁体上方**表达（左腿插进锁体、右腿停在半空）。
     * 全用直线与二次曲线拼，不用圆弧 —— 圆弧在 24dp 下取整容易左右不对称，
     * 而挂锁一旦不对称看起来就像画歪了。
     */
    val LockOpen: ImageVector = build("melody_lock_open") {
        strokePath("shackle", width = 1.8f) {
            moveTo(8.9f, 10.6f)
            lineTo(8.9f, 7.4f)
            quadTo(8.9f, 4.8f, 11.4f, 4.8f)
            quadTo(13.9f, 4.8f, 13.9f, 7.4f)
            lineTo(13.9f, 8.9f)
        }
        strokePath("body", width = 1.8f, closed = true) {
            roundedRect(5.2f, 10.6f, 18.8f, 20.6f, 2.6f)
        }
        solidPath("keyhole") { circle(12.0f, 15.6f, 1.5f) }
    }

    /**
     * 字号：一个大 A 加一个小 A。
     *
     * 没用「A 加双向箭头」那种画法 —— 箭头在 24dp 下会挤成一根线，而两个大小不同的 A
     * 本身就说明了「字号可调」，不需要额外符号。
     */
    val TextSize: ImageVector = build("melody_text_size") {
        strokePath("bigA", width = 1.7f) {
            moveTo(9.0f, 5.2f); lineTo(4.2f, 19.0f)
            moveTo(9.0f, 5.2f); lineTo(13.8f, 19.0f)
            moveTo(5.8f, 14.6f); lineTo(12.2f, 14.6f)
        }
        strokePath("smallA", width = 1.5f) {
            moveTo(18.8f, 13.4f); lineTo(16.4f, 19.0f)
            moveTo(18.8f, 13.4f); lineTo(21.2f, 19.0f)
            moveTo(17.2f, 17.4f); lineTo(20.4f, 17.4f)
        }
    }

    /**
     * 封面形状：左边一个圆角方框、右边一个圆，两个轮廓并排就是「二选一」。
     * 两个形状等高并排，比在方框里画个圆更像"形状开关"，也不容易被误认成"裁剪"。
     */
    val ArtworkShape: ImageVector = build("melody_artwork_shape") {
        strokePath("square", width = 1.7f, closed = true) {
            roundedRect(3.8f, 7.4f, 11.0f, 14.6f, 2.4f)
        }
        strokePath("circle", width = 1.7f, closed = true) {
            circle(16.8f, 11.0f, 3.6f)
        }
    }

    // ---------------------------------------------------------------- 联网与地区

    /**
     * 地区：地球。
     *
     * 用「圆 + 一条经线 + 一条赤道」表达，而不是定位针（那是"当前位置"）或国旗
     * （十几个地区画十几面旗不现实）。经线用一个封闭的二次曲线对做出来，
     * 与赤道线交叉后 24dp 下能读成球体而不是一个"带横线的圆"。
     */
    val Globe: ImageVector = build("melody_globe") {
        strokePath("ring", width = 1.7f, closed = true) { circle(12.0f, 12.0f, 8.6f) }
        strokePath("meridian", width = 1.5f, closed = true) {
            moveTo(12.0f, 3.6f)
            quadTo(7.2f, 12.0f, 12.0f, 20.4f)
            quadTo(16.8f, 12.0f, 12.0f, 3.6f)
        }
        strokePath("equator", width = 1.5f) { moveTo(4.1f, 12.0f); lineTo(19.9f, 12.0f) }
    }

    /**
     * 匹配评分：仪表盘。
     *
     * 表盘 + 指针是"门槛/刻度"最通用的写法。拱形用一条二次曲线（而不是圆弧）：
     * 24dp 下两者的差别看不出来，而曲线只有一个控制点，改粗细时不会左右不对称。
     */
    val Gauge: ImageVector = build("melody_gauge") {
        strokePath("arc", width = 1.8f) {
            moveTo(3.8f, 17.2f)
            quadTo(12.0f, 4.4f, 20.2f, 17.2f)
        }
        strokePath("needle", width = 1.7f) { moveTo(12.0f, 17.2f); lineTo(16.4f, 10.6f) }
        solidPath("hub") { circle(12.0f, 17.2f, 1.5f) }
    }

    /**
     * 云 + 音符：按关键词搜索的那一家联网歌词来源。
     *
     * 与 [CloudLyrics] 共用同一朵云（[cloudOutline]），只有云里那笔不同 ——
     * 两个来源的图标必须一眼看出是同一族功能，区别只在"它是怎么给的"。
     */
    val CloudMusic: ImageVector = build("melody_cloud_music") {
        strokePath("cloud", width = 1.7f, closed = true) { cloudOutline() }
        solidPath("noteHead") { circle(10.4f, 14.9f, 1.35f) }
        strokePath("noteStem", width = 1.5f) {
            moveTo(11.75f, 14.9f); lineTo(11.75f, 11.4f); lineTo(14.0f, 12.1f)
        }
    }

    /**
     * 云 + 两条横线：按签名查询、库里有时间轴歌词的那一家来源。
     *
     * 横线只有两条而且第二条更短：三条等长会被读成「菜单」，两条长短不一
     * 才读得出是"一页有内容的文本"。
     */
    val CloudLyrics: ImageVector = build("melody_cloud_lyrics") {
        strokePath("cloud", width = 1.7f, closed = true) { cloudOutline() }
        strokePath("line1", width = 1.5f) { moveTo(8.4f, 12.4f); lineTo(15.6f, 12.4f) }
        strokePath("line2", width = 1.5f) { moveTo(8.4f, 15.0f); lineTo(13.2f, 15.0f) }
    }

    private fun build(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()

    private fun ImageVector.Builder.solidPath(
        name: String,
        fillType: PathFillType = PathFillType.NonZero,
        alpha: Float = 1f,
        block: PathBuilder.() -> Unit
    ) = path(
        name = name,
        fill = SolidColor(Color.Black.copy(alpha = alpha)),
        pathFillType = fillType,
        pathBuilder = block
    )

    private fun ImageVector.Builder.strokePath(
        name: String,
        width: Float,
        closed: Boolean = false,
        block: PathBuilder.() -> Unit
    ) = path(
        name = name,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = {
            block()
            if (closed) close()
        }
    )

    /** 用两段圆弧拼一个圆（PathBuilder 没有现成的圆，靠 arcToRelative 闭合）。 */
    private fun PathBuilder.circle(cx: Float, cy: Float, radius: Float) {
        moveTo(cx + radius, cy)
        arcToRelative(radius, radius, 0f, true, true, -2f * radius, 0f)
        arcToRelative(radius, radius, 0f, true, true, 2f * radius, 0f)
        close()
    }

    /** 圆角矩形。 */
    private fun PathBuilder.roundedRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float
    ) {
        val r = minOf(radius, (right - left) / 2f, (bottom - top) / 2f)
        moveTo(left + r, top)
        lineTo(right - r, top)
        quadTo(right, top, right, top + r)
        lineTo(right, bottom - r)
        quadTo(right, bottom, right - r, bottom)
        lineTo(left + r, bottom)
        quadTo(left, bottom, left, bottom - r)
        lineTo(left, top + r)
        quadTo(left, top, left + r, top)
        close()
    }

    /**
     * 云朵轮廓，供 [CloudMusic] / [CloudLyrics] 共用。
     *
     * **刻意不 close()**：底边由调用方的 `strokePath(closed = true)` 补上 ——
     * 在这里闭合的话，两处调用都得再写一次"不要闭合"，早晚有人漏掉。
     */
    private fun PathBuilder.cloudOutline() {
        moveTo(7.4f, 18.0f)
        quadTo(3.8f, 18.0f, 3.8f, 14.9f)
        quadTo(3.8f, 11.9f, 7.4f, 12.0f)
        quadTo(7.7f, 8.2f, 11.5f, 7.9f)
        quadTo(15.2f, 7.6f, 16.4f, 11.2f)
        quadTo(20.2f, 11.1f, 20.2f, 14.6f)
        quadTo(20.2f, 18.0f, 16.6f, 18.0f)
    }

    /** 竖向圆角条，用于暂停键与跳曲键。 */
    private fun PathBuilder.roundedBar(left: Float, top: Float, right: Float, bottom: Float, radius: Float) =
        roundedRect(left, top, right, bottom, radius)
}
