#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成「自定义图标」所需的全部资源 + 清单别名（幂等，可重复运行）：
  1. res/mipmap-xxxhdpi/cardart_NNN.webp   101 张卡面（432px 画布，萌宠居中 224px，占 108dp 的 56dp）
  2. res/drawable/icon_bg_<rarity>.xml     5 个稀有度渐变底
  3. res/drawable/icon_ring_<rarity>.xml   5 个稀有度描边圈（62dp，位于 66dp 安全区内）
  4. res/drawable/icon_fg_cNNN_<r>.xml     505 个前景 layer-list（描边圈 + 卡面）
  5. res/mipmap-anydpi-v26/icon_cNNN_<r>.xml  505 个自适应图标
  6. AndroidManifest.xml 中插入 506 个 activity-alias（默认 standard + 101×5 卡片别名）
     卡片别名默认 enabled=false，运行时由 IconSwitcher 切换。

用法：在仓库根目录执行  python3 tools/gen_icon_aliases.py
依赖：Pillow（pip install Pillow）
"""
import os
import re
from PIL import Image

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
SRC = os.path.join(ROOT, "app/src/main/assets/cards")
RES = os.path.join(ROOT, "app/src/main/res")
DRAWABLE = os.path.join(RES, "drawable")
MIPMAP_XXXHDPI = os.path.join(RES, "mipmap-xxxhdpi")
MIPMAP_V26 = os.path.join(RES, "mipmap-anydpi-v26")
MANIFEST = os.path.join(ROOT, "app/src/main/AndroidManifest.xml")

TOTAL = 101
CANVAS = 432          # 自适应图标画布 108dp @ xxxhdpi(4x)
ART_SIZE = 224        # 萌宠 56dp @4x，居中，66dp 安全区内
RING_DP = 62          # 描边圈直径，外缘 < 66dp 安全区

# (别名后缀, 背景类型, 起始色, 结束色, 描边色, 描边宽dp)
RARITIES = [
    ("common",  "linear",  "#1B2130", "#141926", "#8B93A7", "1.5"),
    ("copper",  "linear",  "#2A2118", "#1B1611", "#C97A45", "2"),
    ("silver",  "linear",  "#232B38", "#161B24", "#C9D4E3", "2"),
    ("gold",    "linear",  "#3A2E0F", "#1C180C", "#F5C542", "2.5"),
    ("diamond", "radial",  "#1F5A68", "#0C1B22", "#67E8F9", "2.5"),
]


def gen_cardart():
    os.makedirs(MIPMAP_XXXHDPI, exist_ok=True)
    for i in range(1, TOTAL + 1):
        src = Image.open(os.path.join(SRC, "%03d.webp" % i)).convert("RGBA")
        art = src.crop(src.getbbox())          # 紧裁透明底
        art.thumbnail((ART_SIZE, ART_SIZE), Image.LANCZOS)
        canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
        canvas.paste(art, ((CANVAS - art.width) // 2, (CANVAS - art.height) // 2), art)
        canvas.save(os.path.join(MIPMAP_XXXHDPI, "cardart_%03d.webp" % i), "WEBP", quality=85, method=6)
    print("cardart: %d webp -> %s" % (TOTAL, os.path.relpath(MIPMAP_XXXHDPI, ROOT)))


def gen_bg_and_rings():
    os.makedirs(DRAWABLE, exist_ok=True)
    for name, kind, c1, c2, ring, rw in RARITIES:
        if kind == "radial":
            bg = (
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<shape xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <gradient\n'
                '        android:type="radial"\n'
                '        android:gradientRadius="70dp"\n'
                '        android:startColor="%s"\n'
                '        android:centerColor="#123741"\n'
                '        android:endColor="%s" />\n'
                "</shape>\n" % (c1, c2)
            )
        else:
            bg = (
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<shape xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <gradient\n'
                '        android:angle="270"\n'
                '        android:startColor="%s"\n'
                '        android:endColor="%s" />\n'
                "</shape>\n" % (c1, c2)
            )
        open(os.path.join(DRAWABLE, "icon_bg_%s.xml" % name), "w").write(bg)

        ring_xml = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:shape="oval">\n'
            '    <size android:width="%ddp" android:height="%ddp" />\n'
            '    <stroke android:width="%sdp" android:color="%s" />\n'
            '    <solid android:color="#00000000" />\n'
            "</shape>\n" % (RING_DP, RING_DP, rw, ring)
        )
        open(os.path.join(DRAWABLE, "icon_ring_%s.xml" % name), "w").write(ring_xml)
    print("bg/ring: %d + %d xml -> %s" % (len(RARITIES), len(RARITIES), os.path.relpath(DRAWABLE, ROOT)))


def gen_fg_and_adaptive():
    os.makedirs(MIPMAP_V26, exist_ok=True)
    for i in range(1, TOTAL + 1):
        for r_idx, (name, _, _, _, _, _) in enumerate(RARITIES):
            fg = (
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<layer-list xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <item android:drawable="@drawable/icon_ring_%s" android:gravity="center" />\n'
                '    <item android:drawable="@mipmap/cardart_%03d" />\n'
                "</layer-list>\n" % (name, i)
            )
            open(os.path.join(DRAWABLE, "icon_fg_c%03d_%d.xml" % (i, r_idx)), "w").write(fg)

            adaptive = (
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/icon_bg_%s" />\n'
                '    <foreground android:drawable="@drawable/icon_fg_c%03d_%d" />\n'
                "</adaptive-icon>\n" % (name, i, r_idx)
            )
            open(os.path.join(MIPMAP_V26, "icon_c%03d_%d.xml" % (i, r_idx)), "w").write(adaptive)
    print("fg/adaptive: %d + %d xml" % (TOTAL * len(RARITIES), TOTAL * len(RARITIES)))


ALIAS_TMPL = (
    '        <activity-alias\n'
    '            android:name=".icon.%s"\n'
    '            android:enabled="%s"\n'
    '            android:exported="true"\n'
    '            android:icon="@mipmap/%s"\n'
    '            android:targetActivity=".ui.MainActivity">\n'
    '            <intent-filter>\n'
    '                <action android:name="android.intent.action.MAIN" />\n'
    '                <category android:name="android.intent.category.LAUNCHER" />\n'
    '            </intent-filter>\n'
    '        </activity-alias>\n'
)


def gen_manifest():
    src = open(MANIFEST).read()
    block = []
    # 默认别名：涟漪图标，默认启用
    block.append(
        '        <!-- 默认桌面入口（涟漪图标）；卡片别名默认禁用，由 IconSwitcher 运行时切换 -->\n'
    )
    block.append(
        ALIAS_TMPL % ("standard", "true", "ic_launcher")
    )
    for i in range(1, TOTAL + 1):
        for r_idx in range(len(RARITIES)):
            block.append(
                ALIAS_TMPL % ("a%03d_%d" % (i, r_idx), "false", "icon_c%03d_%d" % (i, r_idx))
            )
    content = "".join(block)

    pattern = re.compile(r"(<!-- ICON_ALIASES_START -->).*(<!-- ICON_ALIASES_END -->)", re.S)
    if not pattern.search(src):
        raise SystemExit("manifest 中未找到 ICON_ALIASES 标记，请勿手动删除")
    out = pattern.sub(
        lambda m: m.group(1) + "\n" + content + "        " + m.group(2), src, count=1
    )
    open(MANIFEST, "w").write(out)
    print("manifest: %d aliases" % (1 + TOTAL * len(RARITIES)))


if __name__ == "__main__":
    gen_cardart()
    gen_bg_and_rings()
    gen_fg_and_adaptive()
    gen_manifest()
    print("done.")
