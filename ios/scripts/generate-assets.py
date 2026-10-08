#!/usr/bin/env python3
"""Recreate the app's original moon icon and named colors. Requires Pillow."""
from pathlib import Path
import json
import math
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]

def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n')

size = 1024
icon = Image.new('RGB', (size, size))
pixels = icon.load()
for y in range(size):
    for x in range(size):
        glow = max(0, 1 - math.hypot((x - 420) / 900, (y - 340) / 900)) ** 2
        vertical = y / size
        pixels[x, y] = (int(36 + 53 * glow - 10 * vertical),
                        int(29 + 40 * glow - 5 * vertical),
                        int(69 + 88 * glow - 14 * vertical))
moon = Image.new('L', (size, size), 0)
draw = ImageDraw.Draw(moon)
draw.ellipse((240, 242, 770, 772), fill=255)
draw.ellipse((424, 138, 915, 629), fill=0)
icon.paste((227, 220, 255), mask=moon)
draw = ImageDraw.Draw(icon)
for cx, cy, radius in [(735, 290, 38), (803, 433, 20)]:
    draw.polygon([(cx, cy - radius), (cx + radius * .25, cy - radius * .25),
                  (cx + radius, cy), (cx + radius * .25, cy + radius * .25),
                  (cx, cy + radius), (cx - radius * .25, cy + radius * .25),
                  (cx - radius, cy), (cx - radius * .25, cy - radius * .25)], fill=(227, 220, 255))

for target in ['WakeMeUp', 'WakeMeUpWatch']:
    catalog = ROOT / target / 'Assets.xcassets'
    write_json(catalog / 'Contents.json', {'info': {'author': 'xcode', 'version': 1}})
    for name, light, dark in [('AccentColor', '#705BCD', '#B3A3F6'), ('SeaColor', '#167858', '#7CDEB8')]:
        colors = []
        for hex_color, appearance in [(light, None), (dark, 'dark')]:
            components = {key: f'{int(hex_color[index:index+2], 16) / 255:.6f}'
                          for key, index in [('red', 1), ('green', 3), ('blue', 5)]}
            components['alpha'] = '1.000'
            entry = {'idiom': 'universal', 'color': {'color-space': 'srgb', 'components': components}}
            if appearance: entry['appearances'] = [{'appearance': 'luminosity', 'value': appearance}]
            colors.append(entry)
        write_json(catalog / f'{name}.colorset' / 'Contents.json', {'colors': colors, 'info': {'author': 'xcode', 'version': 1}})
    directory = catalog / 'AppIcon.appiconset'
    directory.mkdir(parents=True, exist_ok=True)
    images = []
    if target == 'WakeMeUp':
        icon.save(directory / 'AppIcon.png')
        images.append({'filename': 'AppIcon.png', 'idiom': 'universal', 'platform': 'ios', 'size': '1024x1024'})
    else:
        specifications = [
            (24, 2, 'notificationCenter', '38mm'), (27.5, 2, 'notificationCenter', '42mm'),
            (33, 2, 'notificationCenter', '45mm'),
            (29, 2, 'companionSettings', None), (29, 3, 'companionSettings', None),
            (40, 2, 'appLauncher', '38mm'), (44, 2, 'appLauncher', '40mm'),
            (46, 2, 'appLauncher', '41mm'), (50, 2, 'appLauncher', '44mm'),
            (51, 2, 'appLauncher', '45mm'), (54, 2, 'appLauncher', '49mm'),
            (86, 2, 'quickLook', '38mm'), (98, 2, 'quickLook', '42mm'),
            (108, 2, 'quickLook', '44mm')]
        for points, scale, role, subtype in specifications:
            dimension = int(points * scale)
            filename = f'icon-{dimension}.png'
            icon.resize((dimension, dimension), Image.Resampling.LANCZOS).save(directory / filename)
            entry = {'filename': filename, 'idiom': 'watch', 'role': role, 'size': f'{points}x{points}', 'scale': f'{scale}x'}
            if subtype: entry['subtype'] = subtype
            images.append(entry)
        icon.save(directory / 'AppIcon.png')
        images.append({'filename': 'AppIcon.png', 'idiom': 'watch-marketing', 'size': '1024x1024', 'scale': '1x'})
    write_json(directory / 'Contents.json', {'images': images, 'info': {'author': 'xcode', 'version': 1}})
print('Generated original app icons and adaptive color assets.')
