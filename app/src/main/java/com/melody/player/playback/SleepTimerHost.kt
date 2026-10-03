package com.melody.player.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 睡眠定时的"交接点"：界面（[com.melody.player.ui.player.PlayerViewModel]）设下到点时刻，
 * 由 [PlaybackService] 负责等到点后暂停播放。
 *
 * 为什么要有这么一个进程级的对象，而不是让界面直接把倒计时跑在自己身上：
 * **ViewModel 是 Activity 作用域的**。用户设了"30 分钟后停"然后划掉 App，
 * Activity 销毁、ViewModel 清掉、`viewModelScope` 里的协程全部取消 —— 而音乐还在放。
 * 那正是这个功能要解决的场景（戴着耳机睡着），偏偏也是它最容易失效的场景。
 *
 * 播放服务是前台服务、跟着播放活着，所以把"到点做什么"交给它，界面只留一份
 * deadline 用来画倒计时。两边读的是同一个数，显示与到点不会各走各的。
 *
 * 只存内存、**不落盘**：重开 App 后定时器自然失效，符合"临时设一下"的直觉 ——
 * 否则会变成"重开 App 五分钟后莫名暂停"这种解释不清的行为。
 */
object SleepTimerHost {

    /**
     * 到点时刻，单位是 `SystemClock.elapsedRealtime()` 毫秒。
     *
     * 用开机以来的单调时钟而不是墙钟：用户改系统时间（或时区/夏令时）不该
     * 让一个"还有 20 分钟"的定时瞬间到期或者变成 3 小时后。
     */
    private val _deadlineMs = MutableStateFlow<Long?>(null)
    val deadlineMs: StateFlow<Long?> = _deadlineMs.asStateFlow()

    fun set(deadlineMs: Long?) {
        _deadlineMs.value = deadlineMs
    }

    /** 当前现在（单调时钟）。抽出来是为了让"现在"只有一个来源。 */
    fun now(): Long = android.os.SystemClock.elapsedRealtime()
}
