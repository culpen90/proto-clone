#!/usr/bin/env python3
"""ADB UI helpers. Assert the expected app is visible before every tap."""
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path


def adb(*args):
    return subprocess.check_output(['adb', *args])


def hierarchy(package):
    adb('shell', 'rm', '-f', '/sdcard/proto-inspect.xml')
    result = adb('shell', 'uiautomator', 'dump', '/sdcard/proto-inspect.xml')
    if b'UI hierchary dumped' not in result:
        raise RuntimeError('Android could not capture a fresh idle UI hierarchy')
    data = adb('shell', 'cat', '/sdcard/proto-inspect.xml')
    root = ET.fromstring(data)
    if not any(n.get('package') == package for n in root.iter('node')):
        raise RuntimeError('Expected app is not visible: ' + package)
    return root, data


def tap(package, text=None, description=None, resource=None):
    root, _ = hierarchy(package)
    matches = [n for n in root.iter('node') if
               (text is None or n.get('text') == text) and
               (description is None or n.get('content-desc') == description) and
               (resource is None or n.get('resource-id', '').endswith(':id/' + resource))]
    if len(matches) != 1:
        raise RuntimeError(f'Expected one match, got {len(matches)}: {text, description, resource}')
    node = matches[0]
    x0, y0, x1, y1 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x0+x1)//2), str((y0+y1)//2))


def capture(package, path):
    root, data = hierarchy(package)
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.with_suffix('.xml').write_bytes(data)
    path.with_suffix('.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    return [(n.get('text'), n.get('bounds')) for n in root.iter('node') if n.get('text')]


def menu(package, item):
    tap(package, description='More options')
    tap(package, text=item)
