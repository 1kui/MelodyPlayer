# -*- coding: utf-8 -*-
"""修复批量 Edit 静默丢失的三处改动（每处改完重新读文件自证命中）。"""
import io

def patch(path, old, new):
    s = io.open(path, encoding="utf-8").read()
    assert s.count(old) == 1, "anchor not unique/found in %s: %r (count=%d)" % (path, old[:60], s.count(old))
    s = s.replace(old, new)
    io.open(path, "w", encoding="utf-8", newline="").write(s)
    # 自证：重新读回
    s2 = io.open(path, encoding="utf-8").read()
    assert new in s2, "NOT LANDED in %s" % path
    print("OK", path, "->", new.strip().splitlines()[0][:70])

BASE = "app/src/main/java/com/melody/player/"

# 1) PlayerViewModel: 补 import
patch(
    BASE + "ui/player/PlayerViewModel.kt",
    "import com.melody.player.core.Song\nimport com.melody.player.core.SongQuery",
    "import com.melody.player.core.Song\nimport com.melody.player.core.SongEdit\n"
    "import com.melody.player.core.SongEdits\nimport com.melody.player.core.SongQuery",
)

# 2) LibraryScreen: 补参数声明
patch(
    BASE + "ui/screens/LibraryScreen.kt",
    "    onFetchCover: (Song) -> Unit,\n    modifier: Modifier = Modifier\n) {",
    "    onFetchCover: (Song) -> Unit,\n    onEditSong: (Song) -> Unit,\n    modifier: Modifier = Modifier\n) {",
)

# 3) MelodyRoot: 补 import
patch(
    BASE + "ui/MelodyRoot.kt",
    "import com.melody.player.ui.components.MiniPlayer\n",
    "import com.melody.player.ui.components.MiniPlayer\nimport com.melody.player.ui.components.SongEditDialog\n",
)

print("all patches landed")
