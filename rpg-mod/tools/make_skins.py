"""Placeholder skins for story actors (64x64 Minecraft skins).

Run:  python3 make_skins.py   (writes to ../src/main/resources/assets/aot_rpg/textures/entity/actor)
Replace any PNG with a real skin of the same name to upgrade that character.
"""
import os
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'aot_rpg', 'textures', 'entity', 'actor')

SKIN = {'fair': (240, 206, 176), 'light': (226, 190, 158), 'tan': (200, 158, 118), 'olive': (188, 150, 110), 'dark': (140, 98, 66)}
C = {
    'jacket': (138, 98, 62), 'jacket_d': (110, 76, 46), 'shirt': (232, 228, 214), 'pants': (224, 218, 200), 'belt': (70, 50, 34),
    'boots': (60, 40, 28), 'cloak': (46, 84, 52), 'cloak_d': (34, 64, 40), 'garrison': (160, 40, 40), 'mp': (40, 110, 80),
    'white': (245, 245, 245), 'black': (24, 22, 22),
}

def region(img, x, y, w, h, col):
    for i in range(x, x + w):
        for j in range(y, y + h):
            img.putpixel((i, j), col + (255,) if len(col) == 3 else col)

def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c)

def box(img, x, y, w, h, d, col, top=None, bottom=None):
    """A cuboid's unwrapped faces at the standard skin layout origin (x, y): w wide, h tall, d deep."""
    region(img, x + d, y, w, d, top or shade(col, 1.1))                 # top
    region(img, x + d + w, y, w, d, bottom or shade(col, 0.8))          # bottom
    region(img, x, y + d, d, h, shade(col, 0.9))                        # right
    region(img, x + d, y + d, w, h, col)                                # front
    region(img, x + d + w, y + d, d, h, shade(col, 0.9))                # left
    region(img, x + d + w + d, y + d, w, h, shade(col, 0.85))           # back

def make(name, skin='light', hair=(90, 60, 40), style='short', eyes=(80, 60, 40), outfit='cadet', extra=()):
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    sk = SKIN[skin]
    # Head
    box(img, 0, 0, 8, 8, 8, sk, top=hair)
    # Hair on the head: top rows of front/sides, whole back.
    rows = {'short': 2, 'bob': 3, 'long': 3, 'bald': 0, 'buzz': 1, 'bun': 2, 'ponytail': 2, 'undercut': 2, 'two_tone': 2}[style]
    for (fx, fw) in ((0, 8), (8, 8), (16, 8)):
        region(img, fx, 8, fw, rows, hair) if rows else None
    region(img, 24, 8, 8, 8 if style != 'bald' else 0, hair) if style != 'bald' else None
    if style in ('bob', 'long'):
        region(img, 0, 8, 8, 6, hair)      # right side
        region(img, 16, 8, 8, 6, hair)     # left side
        region(img, 8, 10, 1, 4, hair)     # front fringe edges
        region(img, 15, 10, 1, 4, hair)
    if style == 'two_tone':
        region(img, 8, 8, 8, 1, shade(hair, 0.55))
    if style == 'undercut':
        region(img, 0, 10, 8, 2, shade(hair, 0.6))
        region(img, 16, 10, 8, 2, shade(hair, 0.6))
    # Face: eyes and mouth on the front (8..15, 8..15).
    region(img, 9, 12, 2, 1, C['white'])
    region(img, 13, 12, 2, 1, C['white'])
    region(img, 10, 12, 1, 1, eyes)
    region(img, 13, 12, 1, 1, eyes)
    region(img, 9, 11, 2, 1, shade(hair, 0.8))   # brows
    region(img, 13, 11, 2, 1, shade(hair, 0.8))
    region(img, 11, 14, 2, 1, shade(sk, 0.75))    # mouth
    # Hat layer for long hair / ponytail / bun (overlay at x+32).
    if style == 'long':
        region(img, 32 + 24, 8, 8, 8, hair)
        region(img, 32, 8, 8, 8, hair)
        region(img, 48, 8, 8, 8, hair)
    if style in ('ponytail', 'bun'):
        region(img, 32 + 24 + 2, 8 + 2, 4, 4, shade(hair, 0.9))

    # Outfits
    civilian = outfit in ('civilian_m', 'civilian_f', 'farmer', 'noble', 'priest', 'thug', 'child', 'apron')
    if civilian:
        top = {'civilian_m': (120, 110, 90), 'civilian_f': (150, 110, 120), 'farmer': (130, 120, 80), 'noble': (70, 50, 110),
               'priest': (230, 230, 225), 'thug': (70, 70, 70), 'child': (140, 120, 100), 'apron': (170, 140, 110)}[outfit]
        legs = {'civilian_f': top, 'priest': top, 'apron': top, 'noble': (40, 30, 60)}.get(outfit, (90, 80, 70))
        box(img, 16, 16, 8, 12, 4, top)
        box(img, 40, 16, 4, 12, 4, top)
        box(img, 32, 48, 4, 12, 4, top)
        box(img, 0, 16, 4, 12, 4, legs)
        box(img, 16, 48, 4, 12, 4, legs)
        for (x, y) in ((40, 16), (32, 48)):
            region(img, x, y + 4 + 9, 16, 3, sk)   # bare hands
        if outfit == 'apron':
            region(img, 21, 24, 6, 8, (235, 230, 215))
        if outfit == 'noble':
            region(img, 23, 20, 2, 12, (210, 180, 90))
        if outfit == 'priest':
            region(img, 23, 21, 2, 6, (180, 150, 60))
    else:
        # Military: cropped jacket over a white shirt, white trousers, straps, knee boots.
        box(img, 16, 16, 8, 12, 4, C['shirt'])
        region(img, 20, 20, 8, 5, C['jacket'])          # jacket front (cropped)
        region(img, 23, 20, 2, 5, C['shirt'])           # open front
        region(img, 32, 20, 8, 5, C['jacket_d'])        # back
        region(img, 16, 20, 4, 5, C['jacket_d'])
        region(img, 28, 20, 4, 5, C['jacket_d'])
        region(img, 20, 28, 8, 1, C['belt'])            # waist belt
        region(img, 21, 25, 1, 3, C['belt'])            # chest straps
        region(img, 26, 25, 1, 3, C['belt'])
        box(img, 40, 16, 4, 12, 4, C['jacket'])
        box(img, 32, 48, 4, 12, 4, C['jacket'])
        for (x, y) in ((40, 16), (32, 48)):
            region(img, x, y + 4 + 10, 16, 2, sk)
        box(img, 0, 16, 4, 12, 4, C['pants'])
        box(img, 16, 48, 4, 12, 4, C['pants'])
        for (x, y) in ((0, 16), (16, 48)):
            region(img, x, y + 4 + 6, 16, 6, C['boots'])
            region(img, x, y + 4 + 2, 16, 1, C['belt'])  # thigh straps
        badge = {'survey': (230, 230, 230), 'garrison': C['garrison'], 'mp': C['mp']}.get(outfit)
        if badge:
            region(img, 21, 21, 1, 2, badge)             # emblem on the chest
            region(img, 34, 21, 4, 3, badge)             # emblem on the back
        if outfit == 'survey':
            # The green cloak over the back (jacket overlay layer) and shoulders.
            region(img, 32 + 0, 36, 4, 12, C['cloak_d'])   # overlay right
            region(img, 32 + 8, 36, 4, 12, C['cloak_d'])   # overlay left
            region(img, 32, 36, 0, 0, C['cloak'])
            region(img, 32 + 4 + 8 + 4 - 4, 36, 8, 12, C['cloak'])  # overlay back (32,36)
            region(img, 34, 38, 4, 4, (230, 230, 230))      # wings of freedom
            region(img, 35, 39, 2, 2, (60, 90, 160))
    for e in extra:
        e(img)
    img.save(os.path.join(OUT, name + '.png'))

def scarf(img):
    region(img, 20, 20, 8, 2, (170, 30, 30))
    region(img, 16, 20, 4, 2, (150, 25, 25))
    region(img, 28, 20, 4, 2, (150, 25, 25))
    region(img, 32, 20, 8, 2, (150, 25, 25))

def cravat(img):
    region(img, 23, 20, 2, 3, (245, 245, 245))

def glasses(img):
    region(img, 9, 12, 2, 1, (60, 60, 70))
    region(img, 13, 12, 2, 1, (60, 60, 70))
    region(img, 11, 12, 2, 1, (60, 60, 70))

def goatee(img):
    region(img, 11, 14, 2, 2, (120, 100, 80))

def freckles(img):
    for (x, y) in ((9, 13), (14, 13), (10, 14), (13, 14)):
        img.putpixel((x, y), (170, 110, 80, 255))

def hood(img):
    # Hood down on her back: the hat layer's back and top, framing but not covering the face.
    region(img, 56, 8, 8, 8, (60, 90, 140))
    region(img, 40, 0, 8, 8, (60, 90, 140))
    region(img, 32, 12, 8, 4, (60, 90, 140))
    region(img, 48, 12, 8, 4, (60, 90, 140))

def beard(img):
    region(img, 9, 13, 6, 3, (170, 140, 60))

def stubble(img):
    region(img, 9, 14, 6, 2, (120, 90, 60))

os.makedirs(OUT, exist_ok=True)
BRN, BLK, BLD, DRK, GRY = (92, 60, 38), (26, 24, 26), (226, 198, 120), (58, 40, 30), (150, 150, 150)
make('eren', 'light', BRN, 'short', (60, 150, 110), 'survey')
make('eren_cadet', 'light', BRN, 'short', (60, 150, 110), 'cadet')
make('eren_child', 'light', BRN, 'short', (60, 150, 110), 'child')
make('mikasa', 'fair', BLK, 'bob', (40, 40, 50), 'survey', (scarf,))
make('mikasa_cadet', 'fair', BLK, 'long', (40, 40, 50), 'cadet', (scarf,))
make('mikasa_child', 'fair', BLK, 'long', (40, 40, 50), 'child', (scarf,))
make('armin', 'fair', BLD, 'bob', (70, 120, 200), 'survey')
make('armin_cadet', 'fair', BLD, 'bob', (70, 120, 200), 'cadet')
make('armin_child', 'fair', BLD, 'bob', (70, 120, 200), 'child')
make('jean', 'light', (170, 130, 80), 'two_tone', (110, 80, 50), 'cadet')
make('marco', 'tan', DRK, 'short', (80, 60, 40), 'cadet', (freckles,))
make('connie', 'tan', (70, 60, 50), 'buzz', (90, 70, 50), 'cadet')
make('sasha', 'light', (120, 80, 50), 'ponytail', (120, 80, 50), 'cadet')
make('reiner', 'light', BLD, 'short', (190, 150, 60), 'cadet')
make('bertholdt', 'tan', BLK, 'short', (50, 50, 40), 'cadet')
make('annie', 'fair', BLD, 'bun', (90, 140, 190), 'cadet', (hood,))
make('krista', 'fair', (240, 220, 140), 'long', (90, 140, 200), 'cadet')
make('ymir', 'tan', DRK, 'ponytail', (90, 70, 50), 'cadet', (freckles,))
make('thomas', 'light', (160, 120, 70), 'short', (90, 70, 50), 'cadet')
make('samuel', 'light', DRK, 'short', (80, 60, 40), 'cadet')
make('levi', 'fair', BLK, 'undercut', (60, 70, 80), 'survey', (cravat,))
make('erwin', 'light', BLD, 'short', (70, 120, 200), 'survey')
make('hange', 'light', (110, 70, 40), 'ponytail', (110, 70, 40), 'survey', (glasses,))
make('hannes', 'light', (190, 160, 90), 'short', (100, 90, 60), 'garrison', (stubble,))
make('pixis', 'tan', GRY, 'bald', (60, 60, 60), 'garrison', (goatee,))
make('keith', 'tan', GRY, 'bald', (70, 60, 50), 'cadet', (goatee,))
make('carla', 'light', BRN, 'long', (140, 110, 60), 'apron')
make('garrison_soldier', 'light', DRK, 'short', (70, 60, 50), 'garrison')
make('garrison_captain', 'light', GRY, 'short', (70, 60, 50), 'garrison', (stubble,))
make('mp_soldier', 'light', BRN, 'short', (70, 60, 50), 'mp')
make('mp_officer', 'fair', BLK, 'undercut', (60, 60, 60), 'mp', (stubble,))
make('scout_soldier', 'light', BRN, 'short', (70, 60, 50), 'survey')
make('cadet_m', 'light', BRN, 'short', (80, 60, 40), 'cadet')
make('cadet_f', 'fair', (150, 100, 60), 'ponytail', (80, 60, 40), 'cadet')
make('civilian_m', 'tan', DRK, 'short', (80, 60, 40), 'civilian_m')
make('civilian_f', 'light', (130, 80, 50), 'long', (80, 60, 40), 'civilian_f')
make('farmer', 'tan', (110, 80, 50), 'short', (80, 60, 40), 'farmer', (beard,))
make('baker', 'light', (140, 100, 60), 'short', (80, 60, 40), 'apron')
make('noble', 'fair', BLD, 'short', (70, 110, 170), 'noble')
make('priest', 'fair', GRY, 'short', (60, 60, 70), 'priest')
make('thug', 'olive', BLK, 'buzz', (50, 40, 30), 'thug', (stubble,))
make('smuggler', 'light', (120, 40, 30), 'short', (60, 50, 40), 'thug')
make('refugee_child', 'light', (130, 90, 60), 'short', (80, 60, 40), 'child')
make('refugee', 'tan', DRK, 'long', (80, 60, 40), 'civilian_f')
make('merchant', 'light', GRY, 'short', (70, 60, 50), 'noble', (beard,))
print('skins written to', os.path.abspath(OUT))
