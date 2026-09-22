# -*- coding: utf-8 -*-
"""复核本轮所有关键改动是否真落盘（正面 + 反面断言）。"""
import io

BASE = "D:/work/MelodyPlayer/"
checks = {
 "app/src/main/java/com/melody/player/ui/components/CoverImages.kt": [
   ("image = repository.load(key)?.asImageBitmap()", True),
   ("if (image == null)", False),
 ],
 "app/src/main/java/com/melody/player/ui/player/PlayerViewModel.kt": [
   ("private var songEdits: Map<String, SongEdit> = prefs.songEdits", True),
   (".let { SongEdits.apply(it, songEdits) }", True),
   ("republishSong(song)\n        notifyCoverChanged()", True),
   ("republishCurrent", False),
   ("fun beginEditSong(song: Song)", True),
   ("fun applySongEdit(title: String, artist: String, album: String)", True),
   ("fun resetSongEdit()", True),
   ("private fun updateSongEverywhere", True),
   ("import com.melody.player.core.SongEdit", True),
   ("import com.melody.player.core.SongEdits", True),
 ],
 "app/src/main/java/com/melody/player/data/Prefs.kt": [
   ("var songEdits: Map<String, SongEdit>", True),
   ("KEY_SONG_EDITS", True),
   ("import com.melody.player.core.SongEdit", True),
 ],
 "app/src/main/java/com/melody/player/ui/components/SongRow.kt": [
   ("onEditSong: (() -> Unit)? = null", True),
   ("编辑歌曲信息", True),
 ],
 "app/src/main/java/com/melody/player/ui/screens/PlayerScreen.kt": [
   ("onEditSongInfo: () -> Unit", True),
   ("编辑歌曲信息", True),
 ],
 "app/src/main/java/com/melody/player/ui/icons/MelodyIcons.kt": [
   ("melody_edit", True),
 ],
 "app/src/main/java/com/melody/player/ui/MelodyRoot.kt": [
   ("onEditSong = vm::beginEditSong", True),
   ("onEditSongInfo = vm::beginEditSongForCurrent", True),
   ("SongEditDialog(", True),
   ("import com.melody.player.ui.components.SongEditDialog", True),
 ],
 "app/src/main/java/com/melody/player/ui/player/PlayerUiState.kt": [
   ("val editTarget: Song? = null", True),
   ("val editHasOriginal: Boolean = false", True),
 ],
 "app/src/main/java/com/melody/player/core/SongEdits.kt": [
   ("object SongEdits", True),
   ("fun restored(song: Song)", True),
 ],
}
ok = True
for path, items in checks.items():
    s = io.open(BASE + path, encoding="utf-8").read()
    for needle, want in items:
        got = needle in s
        if got != want:
            ok = False
            print("FAIL", path, repr(needle[:50]), "want", want, "got", got)
print("ALL OK" if ok else "HAS FAILURES")
