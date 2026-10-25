"""Cuts the Path Puzzle logo (store/logo/logo-source.webp) into every asset the app and the Play listing need.
Needs Pillow: python -m pip install --user pillow.   Run: python store/logo/make_logo_assets.py

Writes:
  app/src/main/res/drawable-nodpi/ic_launcher_background.png  432x432 dark gradient (adaptive icon back layer)
  app/src/main/res/drawable-nodpi/ic_launcher_foreground.png  432x432 artwork on transparent (front layer)
  app/src/main/res/drawable-nodpi/ic_launcher_monochrome.png  432x432 single-colour arrows (themed icons, Android 13+)
  app/src/main/res/drawable-nodpi/logo_tile.png               512x512 rounded tile with alpha (loader, menu, splash)
  store/icon-512.png                                          512x512 opaque Play Store icon
  store/logo/launcher-preview.png                             how it looks under circle and squircle masks
"""
import os
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'drawable-nodpi')
os.makedirs(RES, exist_ok=True)

src = Image.open(os.path.join(HERE, 'logo-source.webp')).convert('RGB')
W, H = src.size
lum = src.convert('L')

# ---- the tile's own shape: everything not connected to the black canvas outside it -------------
inside = lum.point(lambda v: 255 if v > 3 else 0)
outside = inside.copy()
ImageDraw.floodfill(outside, (0, 0), 128, thresh=0)
tile_mask = outside.point(lambda v: 0 if v == 128 else 255).filter(ImageFilter.GaussianBlur(1.2))
bbox = tile_mask.point(lambda v: 255 if v > 128 else 0).getbbox()
print('tile box', bbox)

# 1. loader / menu / splash tile: the tile with transparent rounded corners
tile = src.crop(bbox).convert('RGBA')
tile.putalpha(tile_mask.crop(bbox))
tile = tile.resize((512, 512), Image.LANCZOS)
tile.save(os.path.join(RES, 'logo_tile.png'), optimize=True)

# ---- the artwork without the tile edge: a feathered rounded rectangle well inside the tile -------
CROP = (120, 120, 1135, 1115)
feather = Image.new('L', (W * 2, H * 2), 0)
ImageDraw.Draw(feather).rounded_rectangle((140 * 2, 140 * 2, 1115 * 2, 1095 * 2), radius=230 * 2, fill=255)
feather = feather.resize((W, H), Image.LANCZOS).filter(ImageFilter.GaussianBlur(20))
art = src.crop(CROP).convert('RGBA')
art.putalpha(feather.crop(CROP))
art_lum = lum.crop(CROP)
ART_CX, ART_CY = 627 - CROP[0], 572 - CROP[1]          # where the board's centre sits in the crop
ART_W = 945                                             # width of the board and arrows in the source

def bg_value(src_y):
    """The tile's own dark vertical gradient as measured in the source: 27 near the top, easing to 15
    by y 730, holding there, then dropping toward the tile's lower edge."""
    pts = [(-10000, 27), (230, 27), (730, 15), (1030, 14), (1100, 10), (10000, 10)]
    for (y0, v0), (y1, v1) in zip(pts, pts[1:]):
        if y0 <= src_y <= y1:
            return int(round(v0 + (src_y - y0) * (v1 - v0) / (y1 - y0)))
    return 10

def gradient(size, scale):
    img = Image.new('RGB', (size, size))
    px = img.load()
    for y in range(size):
        v = bg_value(572 + (y - size / 2) / scale)
        for x in range(size):
            px[x, y] = (v, v, v)
    return img

def place(layer, size, scale, mode):
    """Puts [layer] (a CROP-sized image) on a transparent size x size canvas, board centred, scaled."""
    w, h = int(layer.width * scale), int(layer.height * scale)
    scaled = layer.resize((w, h), Image.LANCZOS)
    canvas = Image.new(mode, (size, size), (0, 0, 0, 0) if mode == 'RGBA' else 0)
    canvas.paste(scaled, (int(size / 2 - ART_CX * scale), int(size / 2 - ART_CY * scale)), scaled if mode == 'RGBA' else None)
    return canvas

# 2. adaptive icon layers. The visible area is a 72 dp circle of the 108 dp icon and the safe zone 66 dp,
#    so the board is made 262 px wide on the 432 px canvas (about 61% of it).
S_AD = 262 / ART_W
gradient(432, S_AD).save(os.path.join(RES, 'ic_launcher_background.png'), optimize=True)
fg = place(art, 432, S_AD, 'RGBA')
fg.save(os.path.join(RES, 'ic_launcher_foreground.png'), optimize=True)

# 3. themed (monochrome) icon: the bright arrows only, one colour, alpha from brightness
mono_alpha = art_lum.point(lambda v: max(0, min(255, int((v - 140) * 255 / 40))))
mono_layer = Image.new('RGBA', art.size, (0, 0, 0, 0))
black = Image.new('RGBA', art.size, (0, 0, 0, 255))
from PIL import ImageChops
mono_alpha = ImageChops.multiply(mono_alpha, art.getchannel('A'))
mono_layer = Image.composite(black, mono_layer, mono_alpha)
place(mono_layer, 432, S_AD, 'RGBA').save(os.path.join(RES, 'ic_launcher_monochrome.png'), optimize=True)

# 4. Play Store icon: 512 x 512, opaque, board filling about 78% of the width
S_PLAY = 400 / ART_W
play = gradient(512, S_PLAY).convert('RGBA')
play.alpha_composite(place(art, 512, S_PLAY, 'RGBA'))
play.convert('RGB').save(os.path.join(ROOT, 'store', 'icon-512.png'), optimize=True)

# 5. preview: the adaptive icon under a circle, a squircle and a square mask, on grey
def masked(shape):
    base = gradient(432, S_AD).convert('RGBA')
    base.alpha_composite(fg)
    m = Image.new('L', (432 * 4, 432 * 4), 0)
    d = ImageDraw.Draw(m)
    box = (36 * 4, 36 * 4, 396 * 4, 396 * 4)       # the 72 dp window of the 108 dp icon
    if shape == 'circle':
        d.ellipse(box, fill=255)
    elif shape == 'squircle':
        d.rounded_rectangle(box, radius=110 * 4, fill=255)
    else:
        d.rounded_rectangle(box, radius=20 * 4, fill=255)
    m = m.resize((432, 432), Image.LANCZOS)
    base.putalpha(m)
    return base
sheet = Image.new('RGB', (432 * 3 + 40 * 4, 432 + 80), (120, 120, 120))
for i, shape in enumerate(('circle', 'squircle', 'square')):
    img = masked(shape)
    sheet.paste(img, (40 + i * (432 + 40), 40), img)
sheet.save(os.path.join(HERE, 'launcher-preview.png'))
print('done')
