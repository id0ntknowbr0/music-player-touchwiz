"""
Promo graphics and videos for Music Player, built from the app's own screenshots.

Inputs:
  docs/screenshots/*.png             README screenshots (made-up demo library)
  app/build/showcase/promo/square_*  Music Square frames; render them with
                                     ./gradlew testDebugUnitTest --tests "*ShowcaseScreens*"
Outputs (docs/promo/):
  banner.png            1280x640  GitHub social preview
  feature_graphic.png   1024x500  store-style feature graphic
  cards/*.png           1080x1350 one card per feature, for social posts
  promo_vertical.mp4    1080x1920 ~27 s, for Shorts / Reels / TikTok
  promo_landscape.mp4   1920x1080 ~27 s, for YouTube / X
  promo.gif             720x405   short loop for the README

Needs Pillow, NumPy and imageio-ffmpeg:  pip install pillow numpy imageio-ffmpeg
Run from the repository root:            python tools/promo/make_promo.py
"""
import math
import os
import subprocess
import sys

import imageio_ffmpeg
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SHOTS = os.path.join(ROOT, "docs", "screenshots")
SQUARE = os.path.join(ROOT, "app", "build", "showcase", "promo")
OUT = os.path.join(ROOT, "docs", "promo")
FPS = 30

# ---------- Look ----------

BG_TOP = (9, 14, 20)
BG_BOTTOM = (18, 44, 64)
ACCENT = (63, 169, 245)       # the app's "now playing" blue
ACCENT_DARK = (10, 123, 194)
TEXT = (240, 244, 248)
SUBTEXT = (163, 176, 187)

FONT_DIR = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts")


def font(weight, size):
    names = {"bold": ["segoeuib.ttf", "DejaVuSans-Bold.ttf"],
             "regular": ["segoeui.ttf", "DejaVuSans.ttf"],
             "light": ["segoeuisl.ttf", "segoeui.ttf", "DejaVuSans.ttf"]}[weight]
    for n in names:
        for path in (os.path.join(FONT_DIR, n), n):
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                pass
    return ImageFont.load_default()


HEADLINE = "The classic music player,\nrebuilt for modern Android"
FEATURES = ["Synced lyrics", "Music Square moods", "10-band EQ", "Crossfade", "Android Auto",
            "Home-screen widgets", "Sleep timer", "Playlists & .m3u", "Tag & cover editor", "Ringtone cutter"]
FOOTER = "Free & open source  ·  Offline  ·  No ads  ·  Android 11+"
URL = "github.com/id0ntknowbr0/music-player-touchwiz"


def shot(name):
    return Image.open(os.path.join(SHOTS, name + ".png")).convert("RGB")


# ---------- Building blocks ----------

def background(w, h, glow_center=None, glow_size=None):
    """Dark blue gradient with a soft blue glow, like the light behind the Now Playing counter."""
    y = np.linspace(0, 1, h)[:, None, None]
    top, bottom = np.array(BG_TOP, float), np.array(BG_BOTTOM, float)
    arr = np.broadcast_to(top + (bottom - top) * y, (h, w, 3)).astype(np.uint8)
    img = Image.fromarray(arr, "RGB").convert("RGBA")
    if glow_center:
        gw, gh = glow_size
        glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(glow)
        cx, cy = glow_center
        d.ellipse((cx - gw / 2, cy - gh / 2, cx + gw / 2, cy + gh / 2), fill=(47, 134, 214, 110))
        glow = glow.filter(ImageFilter.GaussianBlur(max(gw, gh) / 5))
        img.alpha_composite(glow)
    return img


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return m


def with_shadow(img, blur, offset, opacity=150):
    """Returns (image with a soft drop shadow around it, margin added on each side)."""
    m = blur * 3
    out = Image.new("RGBA", (img.width + 2 * m, img.height + 2 * m), (0, 0, 0, 0))
    sh = Image.new("RGBA", out.size, (0, 0, 0, 0))
    sh.paste((0, 0, 0, opacity), (m, m + offset), img.getchannel("A"))
    out.alpha_composite(sh.filter(ImageFilter.GaussianBlur(blur)))
    out.alpha_composite(img, (m, m))
    return out, m


def phone(screen, width):
    """A plain modern phone around a screenshot. Returns (RGBA image, shadow margin)."""
    b = max(6, round(width * 0.03))
    sw = width - 2 * b
    sh = round(screen.height * sw / screen.width)
    r = round(width * 0.10)
    body = Image.new("RGBA", (width, sh + 2 * b), (0, 0, 0, 0))
    d = ImageDraw.Draw(body)
    d.rounded_rectangle((0, 0, width - 1, sh + 2 * b - 1), r, fill=(14, 15, 17, 255), outline=(70, 76, 84, 255),
                        width=max(2, width // 220))
    scr = screen.resize((sw, sh), Image.LANCZOS).convert("RGBA")
    body.paste(scr, (b, b), rounded_mask((sw, sh), max(4, r - b)))
    return with_shadow(body, blur=max(8, width // 18), offset=max(6, width // 25))


def card(img, width, radius=28):
    """A rounded image with a shadow (used for the widgets picture)."""
    h = round(img.height * width / img.width)
    im = img.resize((width, h), Image.LANCZOS).convert("RGBA")
    im.putalpha(rounded_mask(im.size, radius))
    return with_shadow(im, blur=max(8, width // 22), offset=max(6, width // 30))


def logo(size):
    """Original app mark: a blue rounded square with a white note. (Not the launcher icon.)"""
    s = size * 4
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    grad = Image.new("RGBA", (s, s))
    top, bot = np.array((63, 184, 240), float), np.array(ACCENT_DARK, float)
    t = np.linspace(0, 1, s)[:, None, None]
    grad = Image.fromarray(np.broadcast_to(top + (bot - top) * t, (s, s, 3)).astype(np.uint8)).convert("RGBA")
    img.paste(grad, (0, 0), rounded_mask((s, s), round(s * 0.24)))
    d = ImageDraw.Draw(img)
    w = (255, 255, 255, 255)
    # Beamed eighth notes.
    hx1, hx2, hy1, hy2, r = s * 0.33, s * 0.66, s * 0.70, s * 0.64, s * 0.095
    sw = s * 0.055
    d.ellipse((hx1 - r * 1.15, hy1 - r, hx1 + r * 1.15, hy1 + r), fill=w)
    d.ellipse((hx2 - r * 1.15, hy2 - r, hx2 + r * 1.15, hy2 + r), fill=w)
    sx1, sx2 = hx1 + r * 1.15 - sw, hx2 + r * 1.15 - sw
    d.rectangle((sx1, s * 0.26, sx1 + sw, hy1), fill=w)
    d.rectangle((sx2, s * 0.20, sx2 + sw, hy2), fill=w)
    d.polygon([(sx1, s * 0.26), (sx2 + sw, s * 0.20), (sx2 + sw, s * 0.31), (sx1, s * 0.37)], fill=w)
    return img.resize((size, size), Image.LANCZOS)


def text_layer(lines, max_width=None, align="left", spacing=1.18):
    """lines: [(text, font, colour)]; text may contain '\\n'. Returns a tight RGBA image."""
    rows = []
    for txt, f, col in lines:
        for part in txt.split("\n"):
            rows.append((part, f, col))
    probe = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    sizes = []
    for txt, f, _ in rows:
        box = probe.textbbox((0, 0), txt or " ", font=f)
        sizes.append((box[2] - box[0], round(f.size * spacing)))
    w = max_width or max(s[0] for s in sizes) + 4
    h = sum(s[1] for s in sizes) + 8
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    y = 0
    for (txt, f, col), (tw, lh) in zip(rows, sizes):
        x = {"left": 0, "center": (w - tw) / 2, "right": w - tw}[align]
        d.text((x, y), txt, font=f, fill=col)
        y += lh
    return img


def chips(items, f, max_width, align="left", gap=14):
    """Pill-shaped feature labels, wrapped to max_width."""
    probe = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    pad_x, pad_y = round(f.size * 0.85), round(f.size * 0.45)
    pills = []
    for it in items:
        b = probe.textbbox((0, 0), it, font=f)
        pills.append((it, b[2] - b[0] + 2 * pad_x, f.size + 2 * pad_y, b))
    rows, row, rw = [], [], 0
    for p in pills:
        if row and rw + gap + p[1] > max_width:
            rows.append(row); row, rw = [], 0
        rw += (gap if row else 0) + p[1]; row.append(p)
    if row:
        rows.append(row)
    ph = pills[0][2]
    img = Image.new("RGBA", (max_width, len(rows) * (ph + gap)), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    y = 0
    for row in rows:
        total = sum(p[1] for p in row) + gap * (len(row) - 1)
        x = (max_width - total) / 2 if align == "center" else 0
        for txt, w, h, b in row:
            d.rounded_rectangle((x, y, x + w, y + h), h / 2, fill=(63, 169, 245, 38), outline=(63, 169, 245, 170), width=2)
            d.text((x + pad_x, y + h / 2), txt, font=f, fill=TEXT, anchor="lm")
            x += w + gap
        y += ph + gap
    return img


def faded(img, a):
    if a >= 1:
        return img
    out = img.copy()
    out.putalpha(img.getchannel("A").point(lambda v: round(v * max(0.0, a))))
    return out


def paste(base, layer, x, y, a=1.0):
    if a <= 0.001:
        return
    lx, ly = round(x), round(y)
    # Clip to the canvas; alpha_composite needs the source inside it.
    sx, sy = max(0, -lx), max(0, -ly)
    ex, ey = min(layer.width, base.width - lx), min(layer.height, base.height - ly)
    if ex <= sx or ey <= sy:
        return
    piece = layer.crop((sx, sy, ex, ey)) if (sx, sy, ex, ey) != (0, 0, layer.width, layer.height) else layer
    base.alpha_composite(faded(piece, a), (lx + sx, ly + sy))


# ---------- Still graphics ----------

def banner(w, h, path):
    bg = background(w, h, glow_center=(w * 0.74, h * 0.55), glow_size=(w * 0.55, h * 0.9))
    s = h / 640
    # Three phones, fanned: library behind left, lyrics behind right, Now Playing in front.
    pw_back, pw_front = round(250 * s), round(290 * s)
    for name, cx, cy, pw in (("songs", 0.60, 0.60, pw_back), ("lyrics", 0.88, 0.60, pw_back)):
        p, m = phone(shot(name), pw)
        paste(bg, p, w * cx - p.width / 2, h * cy - (p.height - 2 * m) / 2 - m)
    p, m = phone(shot("now_playing"), pw_front)
    paste(bg, p, w * 0.74 - p.width / 2, h * 0.30 - m)
    # Text on the left.
    x = round(64 * s)
    lg = logo(round(64 * s))
    bg.alpha_composite(lg, (x, round(72 * s)))
    title = text_layer([("Music Player", font("bold", round(40 * s)), TEXT)])
    paste(bg, title, x + lg.width + round(18 * s), round(72 * s) + (lg.height - title.height) / 2 + 2 * s)
    head = text_layer([(HEADLINE, font("bold", round(38 * s)), TEXT)])
    paste(bg, head, x, round(190 * s))
    sub = text_layer([("Synced lyrics, Music Square moods, a 10-band EQ,\ncrossfade, widgets and Android Auto.",
                       font("regular", round(22 * s)), SUBTEXT)])
    paste(bg, sub, x, round(330 * s))
    foot = text_layer([(FOOTER, font("regular", round(19 * s)), ACCENT)])
    paste(bg, foot, x, h - round(110 * s))
    url = text_layer([(URL, font("light", round(18 * s)), SUBTEXT)])
    paste(bg, url, x, h - round(78 * s))
    bg.convert("RGB").save(path, optimize=True)


FEATURE_CARDS = [
    ("now_playing", "now_playing", "The classic look,\nwith modern sound",
     "Shuffle, repeat, A-B repeat, a queue and a\nfavourites heart, all in the familiar layout."),
    ("library", "songs", "Your whole library,\noffline",
     "Songs, albums, artists, genres, folders and\nplaylists, with a fast A-Z index bar."),
    ("lyrics", "lyrics", "Synced lyrics over\nthe album art",
     "Import .lrc files, read embedded lyrics, or\nsearch online, only if you allow it."),
    ("music_square", "music_square", "Pick a mood,\nget a playlist",
     "Music Square places songs on a mood grid.\nThe analysis runs on your phone."),
    ("widgets", "widgets", "Three widgets for\nyour home screen",
     "4x2 with large album art, 4x1 and a\n1x1 play/pause button."),
]


def feature_card(key, image, title, body, path, w=1080, h=1350):
    bg = background(w, h, glow_center=(w / 2, h * 0.70), glow_size=(w * 0.9, h * 0.6))
    # Small brand line at the top, then the headline.
    lg = logo(44)
    name = text_layer([("Music Player", font("bold", 28), TEXT)])
    bx = (w - lg.width - 14 - name.width) / 2
    paste(bg, lg, bx, 48)
    paste(bg, name, bx + lg.width + 14, 48 + (lg.height - name.height) / 2 + 2)
    t = text_layer([(title, font("bold", 64), TEXT)], max_width=w - 160, align="center")
    paste(bg, t, 80, 130)
    b = text_layer([(body, font("regular", 30), SUBTEXT)], max_width=w - 160, align="center")
    paste(bg, b, 80, 130 + t.height + 18)
    top = 130 + t.height + 18 + b.height + 40
    if key == "widgets":
        c, m = card(shot(image), 860)
        paste(bg, c, (w - c.width) / 2, top + 60 - m)
    else:
        p, m = phone(shot(image), 560)
        paste(bg, p, (w - p.width) / 2, top - m)   # runs off the bottom edge on purpose
    bg.convert("RGB").save(path, optimize=True)


def features_card(path, w=1080, h=1350):
    bg = background(w, h, glow_center=(w / 2, h * 0.45), glow_size=(w, h * 0.7))
    lg = logo(150)
    bg.alpha_composite(lg, ((w - 150) // 2, 150))
    paste(bg, text_layer([("Music Player", font("bold", 72), TEXT)], max_width=w, align="center"), 0, 330)
    paste(bg, text_layer([(HEADLINE, font("regular", 36), SUBTEXT)], max_width=w, align="center"), 0, 440)
    c = chips(FEATURES, font("regular", 30), w - 160, align="center", gap=18)
    paste(bg, c, 80, 640)
    fy = 640 + c.height + 110
    paste(bg, text_layer([(FOOTER, font("regular", 28), ACCENT)], max_width=w, align="center"), 0, fy)
    paste(bg, text_layer([(URL, font("light", 28), SUBTEXT)], max_width=w, align="center"), 0, fy + 50)
    bg.convert("RGB").save(path, optimize=True)


# ---------- Video ----------

def ease_out(x):
    x = min(1.0, max(0.0, x))
    return 1 - (1 - x) ** 3


def ease_in_out(x):
    x = min(1.0, max(0.0, x))
    return 3 * x * x - 2 * x * x * x


class Video:
    """One promo, laid out for a portrait (1080x1920) or landscape (1920x1080) canvas."""

    SCENES = [  # name, start, end
        ("intro", 0.0, 3.2), ("now", 3.2, 7.0), ("library", 7.0, 11.2), ("square", 11.2, 15.8),
        ("lyrics", 15.8, 19.4), ("widgets", 19.4, 23.0), ("outro", 23.0, 28.0),
    ]
    CAPTIONS = {
        "now": ("The classic look", "Shuffle, repeat, A-B repeat and a queue,\nin the layout you remember."),
        "library": ("Your whole library, offline", "Songs, albums, artists, genres, folders,\nplaylists and a fast A-Z index."),
        "square": ("Pick a mood, get a playlist", "Drag across the Music Square.\nThe analysis runs on your phone."),
        "lyrics": ("Synced lyrics", "Over the album art, line by line.\nImport .lrc or search online."),
        "widgets": ("Home-screen widgets", "4x2, 4x1 and 1x1, in the classic style."),
    }

    def __init__(self, w, h):
        self.w, self.h = w, h
        self.portrait = h > w
        self.duration = self.SCENES[-1][2]
        if self.portrait:
            self.phone_w = 600
            self.phone_center = (w / 2, 520)       # top edge of the screen part
            glow = ((w / 2, h * 0.62), (w * 1.0, h * 0.6))
        else:
            self.phone_w = 440
            self.phone_center = (w * 0.70, 70)
            glow = ((w * 0.70, h * 0.55), (w * 0.55, h * 1.0))
        self.bg = background(w, h, *glow)
        self.phones = {n: phone(shot(n), self.phone_w) for n in ("now_playing", "songs", "albums", "lyrics")}
        self.squares = [phone(Image.open(os.path.join(SQUARE, f"square_{k}.png")).convert("RGB"), self.phone_w)
                        for k in range(6)]
        self.widgets = card(shot("widgets"), 900 if self.portrait else 760)
        self.captions = {}
        for k, (title, body) in self.CAPTIONS.items():
            if self.portrait:
                self.captions[k] = (text_layer([(title, font("bold", 70), TEXT)], max_width=w - 120, align="center"),
                                    text_layer([(body, font("regular", 36), SUBTEXT)], max_width=w - 120, align="center"))
            else:
                self.captions[k] = (text_layer([(title, font("bold", 64), TEXT)], max_width=960),
                                    text_layer([(body, font("regular", 36), SUBTEXT)], max_width=960))
        self.logo_big = logo(220 if self.portrait else 200)
        self.logo_small = logo(110 if self.portrait else 96)
        self.title = text_layer([("Music Player", font("bold", 96 if self.portrait else 104), TEXT)],
                                max_width=w, align="center")
        self.tagline = text_layer([(HEADLINE, font("regular", 46 if self.portrait else 44), SUBTEXT)],
                                  max_width=w, align="center")
        cw = w - 140 if self.portrait else 1300
        self.chip_rows = chips(FEATURES, font("regular", 36 if self.portrait else 32), cw, align="center", gap=18)
        self.footer = text_layer([(FOOTER, font("regular", 36 if self.portrait else 34), ACCENT)], max_width=w, align="center")
        self.url = text_layer([(URL, font("light", 34 if self.portrait else 32), SUBTEXT)], max_width=w, align="center")

    # -- pieces --

    def put_phone(self, base, ph, a, dy=0.0, dx=0.0):
        img, m = ph
        x = self.phone_center[0] - img.width / 2 + dx
        y = self.phone_center[1] - m + dy
        paste(base, img, x, y, a)

    def caption(self, base, key, u, d):
        title, body = self.captions[key]
        a_in = ease_out((u - 0.15) / 0.5)
        a_in2 = ease_out((u - 0.35) / 0.5)
        out = ease_in_out((d - u) / 0.35)
        rise = 40 * (1 - a_in)
        rise2 = 40 * (1 - a_in2)
        if self.portrait:
            paste(base, title, 60, 150 + rise, a_in * out)
            paste(base, body, 60, 150 + title.height + 20 + rise2, a_in2 * out)
        else:
            y0 = (self.h - title.height - body.height - 24) / 2
            paste(base, title, 140, y0 + rise, a_in * out)
            paste(base, body, 140, y0 + title.height + 24 + rise2, a_in2 * out)

    def phone_motion(self, u, d):
        """Slides up into place, then fades and drifts left when the scene ends."""
        p = ease_out(u / 0.7)
        q = ease_in_out((d - u) / 0.35)
        return p * q, (1 - p) * self.h * 0.18, -(1 - q) * 60

    # -- scenes --

    def frame(self, t):
        base = self.bg.copy()
        for name, s, e in self.SCENES:
            if s <= t < e or (name == "outro" and t >= e):
                getattr(self, "scene_" + name)(base, t - s, e - s)
        return base.convert("RGB")

    def scene_intro(self, base, u, d):
        out = ease_in_out((d - u) / 0.4)
        p = ease_out(u / 0.8)
        size = round(self.logo_big.width * (0.7 + 0.3 * p))
        lg = self.logo_big.resize((size, size), Image.LANCZOS)
        cy = self.h * (0.36 if self.portrait else 0.30)
        paste(base, lg, (self.w - size) / 2, cy - size / 2, p * out)
        a = ease_out((u - 0.45) / 0.6)
        ty = cy + self.logo_big.width / 2 + 50
        paste(base, self.title, 0, ty + 40 * (1 - a), a * out)
        b = ease_out((u - 0.85) / 0.6)
        paste(base, self.tagline, 0, ty + self.title.height + 30 + 40 * (1 - b), b * out)

    def scene_now(self, base, u, d):
        a, dy, dx = self.phone_motion(u, d)
        self.put_phone(base, self.phones["now_playing"], a, dy, dx)
        self.caption(base, "now", u, d)

    def scene_library(self, base, u, d):
        a, dy, dx = self.phone_motion(u, d)
        swap = ease_in_out((u - 2.0) / 0.35)          # songs, then albums
        if swap < 1:
            self.put_phone(base, self.phones["songs"], a, dy, dx)
        if swap > 0:
            self.put_phone(base, self.phones["albums"], a * swap, dy, dx)
        self.caption(base, "library", u, d)

    def scene_square(self, base, u, d):
        a, dy, dx = self.phone_motion(u, d)
        steps = (u - 0.9) / 0.5                        # one more square lit every half second
        k = int(min(5, max(0, math.floor(steps))))
        frac = ease_in_out((steps - math.floor(steps)) / 0.3) if 0 <= steps < 5 else 1
        self.put_phone(base, self.squares[k], a, dy, dx)
        if k < 5 and steps >= 0:
            self.put_phone(base, self.squares[k + 1], a * frac, dy, dx)
        self.caption(base, "square", u, d)

    def scene_lyrics(self, base, u, d):
        a, dy, dx = self.phone_motion(u, d)
        self.put_phone(base, self.phones["lyrics"], a, dy, dx)
        self.caption(base, "lyrics", u, d)

    def scene_widgets(self, base, u, d):
        a, dy, dx = self.phone_motion(u, d)
        img, m = self.widgets
        if self.portrait:
            x, y = (self.w - img.width) / 2, self.h * 0.40 - m
        else:
            x, y = self.w * 0.70 - img.width / 2, (self.h - img.height) / 2
        paste(base, img, x + dx, y + dy * 0.6, a)
        self.caption(base, "widgets", u, d)

    def scene_outro(self, base, u, d):
        p = ease_out(u / 0.6)
        lg = self.logo_small
        top = self.h * (0.16 if self.portrait else 0.10)
        paste(base, lg, (self.w - lg.width) / 2, top + 30 * (1 - p), p)
        ty = top + lg.height + 24
        paste(base, self.title, 0, ty + 30 * (1 - p), p)
        c = ease_out((u - 0.5) / 0.7)
        cy = ty + self.title.height + (70 if self.portrait else 40)
        paste(base, self.chip_rows, (self.w - self.chip_rows.width) / 2, cy + 30 * (1 - c), c)
        f = ease_out((u - 1.2) / 0.6)
        fy = cy + self.chip_rows.height + (80 if self.portrait else 40)
        paste(base, self.footer, 0, fy, f)
        paste(base, self.url, 0, fy + self.footer.height + 10, f)

    def render(self, path, fps=FPS, scale=None):
        size = scale or (self.w, self.h)
        cmd = [imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-loglevel", "error",
               "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{size[0]}x{size[1]}", "-r", str(fps), "-i", "-",
               "-c:v", "libx264", "-preset", "slow", "-crf", "20", "-pix_fmt", "yuv420p",
               "-movflags", "+faststart", path]
        proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
        n = round(self.duration * fps)
        for i in range(n):
            img = self.frame(i / fps)
            if scale:
                img = img.resize(size, Image.LANCZOS)
            proc.stdin.write(img.tobytes())
            if i % fps == 0:
                print(f"\r{os.path.basename(path)}: {i * 100 // n}%", end="", flush=True)
        proc.stdin.close()
        if proc.wait() != 0:
            sys.exit(f"ffmpeg failed for {path}")
        print(f"\r{os.path.basename(path)}: done   ")


def gif_from(mp4, path, width, fps, start, end):
    """A palette-optimised GIF of part of a video, for the README."""
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    filters = f"fps={fps},scale={width}:-1:flags=lanczos"
    subprocess.run([ff, "-y", "-loglevel", "error", "-ss", str(start), "-to", str(end), "-i", mp4,
                    "-vf", f"{filters},split[a][b];[a]palettegen=max_colors=160:stats_mode=diff[p];"
                           f"[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle",
                    "-loop", "0", path], check=True)


def main():
    os.makedirs(os.path.join(OUT, "cards"), exist_ok=True)
    banner(1280, 640, os.path.join(OUT, "banner.png"))
    banner(1024, 500, os.path.join(OUT, "feature_graphic.png"))
    for i, (key, image, title, body) in enumerate(FEATURE_CARDS, 1):
        feature_card(key, image, title, body, os.path.join(OUT, "cards", f"{i}_{key}.png"))
    features_card(os.path.join(OUT, "cards", f"{len(FEATURE_CARDS) + 1}_features.png"))
    print("graphics: done")
    if "--no-video" in sys.argv:
        return
    Video(1080, 1920).render(os.path.join(OUT, "promo_vertical.mp4"))
    landscape = os.path.join(OUT, "promo_landscape.mp4")
    Video(1920, 1080).render(landscape)
    gif_from(landscape, os.path.join(OUT, "promo.gif"), 720, 12, 3.2, 23.0)
    print("videos: done")


if __name__ == "__main__":
    main()
