# -*- coding: utf-8 -*-
"""复核 v1.9.7 全部关键改动是否真落盘（正面 + 反面断言）。"""
import io

BASE = "D:/work/MelodyPlayer/"
checks = {
 "app/src/main/java/com/melody/player/ui/screens/PlayerScreen.kt": [
   ("HorizontalPager(", True),
   ("rememberPagerState(initialPage = 0", True),
   ("animateScrollToPage(target.ordinal)", True),
   ("swipeSwitchEnabled", True),
   ("detectVerticalDragGestures(", True),
   ("SWIPE_SWITCH_THRESHOLD", True),
   ("if (accumulated < 0) onNext() else onPrevious()", True),
   ("FastRewind", False),           # 快退按钮已删
   ("快退 10 秒", False),
   ("FastForward", False),          # 快进按钮已删
   ("快进 10 秒", False),
   ("onSeekBy", False),             # 参数已删
   ("SEEK_STEP_MS = 10_000L", False),  # 常量已删
 ],
 "app/src/main/java/com/melody/player/ui/MelodyRoot.kt": [
   ("onSeekBy", False),
   ('LIBRARY("音乐库", MelodyIcons.PulseBars)', True),
   ("onSwipeSwitchSongChange = vm::setSwipeSwitchSong", True),
 ],
 "app/src/main/java/com/melody/player/ui/icons/MelodyIcons.kt": [
   ('solidPath("barLeft2", alpha = 0.78f)', True),
   ("alpha: Float = 1f", True),
   ("melody_swap_vertical", True),
 ],
 "app/src/main/java/com/melody/player/ui/components/Artwork.kt": [
   ("MelodyIcons.PulseBars", True),
   ("MelodyIcons.MusicNote", False),
 ],
 "app/src/main/res/drawable/ic_stat_music.xml": [
   ('android:fillAlpha="0.78"', True),
   ('android:fillAlpha="0.92"', True),
 ],
 "app/src/main/java/com/melody/player/data/AlbumArtRepository.kt": [
   ("suspend fun applyFirstCandidate(song: Song): CoverResult", True),
   ("searchCandidates(keyword).firstOrNull()", True),
 ],
 "app/src/main/java/com/melody/player/ui/player/PlayerViewModel.kt": [
   ("covers.applyFirstCandidate(song)", True),
   ("fun setSwipeSwitchSong(enabled: Boolean)", True),
   ("swipeSwitchSong = prefs.swipeSwitchSong,", True),
   ("when (covers.ensure(song))", False),   # 批量已不再用打分ensure
 ],
 "app/src/main/java/com/melody/player/data/Prefs.kt": [
   ("var swipeSwitchSong: Boolean", True),
   ("KEY_SWIPE_SWITCH", True),
 ],
 "app/src/main/java/com/melody/player/ui/player/PlayerUiState.kt": [
   ("val swipeSwitchSong: Boolean = true", True),
 ],
 "app/src/main/java/com/melody/player/ui/screens/SettingsScreen.kt": [
   ("上下滑动切换歌曲", True),
   ("MelodyIcons.SwapVertical", True),
   ("coverHelpExpanded", True),
   ("AnimatedVisibility(visible = coverHelpExpanded)", True),
   ("给一首歌挑封面：在曲库长按", True),  # 全文仍在（折叠内容里）
   ("onSwipeSwitchSongChange: (Boolean) -> Unit", True),
   ("import androidx.compose.animation.AnimatedVisibility", True),
 ],
}
ok = True
for path, items in checks.items():
    s = io.open(BASE + path, encoding="utf-8").read()
    for needle, want in items:
        got = needle in s
        if got != want:
            ok = False
            print("FAIL", path, repr(needle[:60]), "want", want, "got", got)
print("ALL OK" if ok else "HAS FAILURES")
