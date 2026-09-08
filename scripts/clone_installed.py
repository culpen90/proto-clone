#!/usr/bin/env python3
"""Make a side-by-side local clone without changing any executable or resource.

Only the package name and launcher alias in AndroidManifest.xml change.
Each payload entry is hash-checked after signing; signing metadata is regenerated.
The .apks output is a plain ZIP of split APKs, not a bundletool APK-set archive.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct as S
import subprocess
import zipfile

U32 = lambda b, o: S.unpack_from('<I', b, o)[0]
NONE = 0xffffffff
ANDROID = 'http://schemas.android.com/apk/res/android'
SOURCE_PACKAGE = 'henize.proto_c'
VERSION_CODE = 53
VERSION_NAME = '1.99c (Patch 1)'


def chunks(data, start=8):
    while start < len(data):
        kind, header, size = S.unpack_from('<HHI', data, start)
        if header < 8 or size < header or start + size > len(data):
            raise ValueError('Malformed binary XML chunk')
        yield kind, bytearray(data[start:start+size])
        start += size


def strings_from_pool(pool):
    count, styles, flags, start, style_start = S.unpack_from('<5I', pool, 8)
    if styles:
        raise ValueError('Styled manifest string pools are unsupported')
    utf8 = bool(flags & 0x100)
    def length(pos):
        if utf8:
            n=pool[pos]; pos+=1
            if n & 0x80: n=((n&0x7f)<<8)|pool[pos]; pos+=1
        else:
            n=S.unpack_from('<H',pool,pos)[0]; pos+=2
            if n & 0x8000: n=((n&0x7fff)<<16)|S.unpack_from('<H',pool,pos)[0];pos+=2
        return n,pos
    strings=[]
    for i in range(count):
        pos=start+U32(pool,28+i*4)
        n,pos=length(pos)
        if utf8:
            n,pos=length(pos)
            strings.append(bytes(pool[pos:pos+n]).decode('utf-8'))
        else: strings.append(bytes(pool[pos:pos+2*n]).decode('utf-16le'))
    return strings


def pool_from_strings(strings):
    data=bytearray(); offsets=[]
    for value in strings:
        encoded=value.encode('utf-16le'); n=len(encoded)//2
        offsets.append(len(data))
        data.extend(S.pack('<H',n) if n<0x8000 else S.pack('<HH',0x8000|(n>>16),n&0xffff))
        data.extend(encoded+b'\0\0')
    data.extend(b'\0'*(-len(data)%4))
    start=28+4*len(strings)
    return S.pack('<HHI5I',1,28,start+len(data),len(strings),0,0,start,0)+S.pack('<'+'I'*len(offsets),*offsets)+data


def manifest_elements(data):
    """Read only the manifest structure needed to validate this supported APK set."""
    if len(data) < 8 or S.unpack_from('<HHI', data) != (3, 8, len(data)):
        raise ValueError('Invalid binary Android manifest header')
    parts = list(chunks(data))
    pools = [chunk for kind, chunk in parts if kind == 1]
    if len(pools) != 1:
        raise ValueError('Expected one manifest string pool')
    strings = strings_from_pool(pools[0])
    elements = []
    stack = []
    for kind, chunk in parts:
        if kind == 0x102:
            node = strings[U32(chunk, 20)]
            start, size, count = S.unpack_from('<HHH', chunk, 24)
            if size != 20 or 16 + start + size * count > len(chunk):
                raise ValueError('Unexpected manifest attribute layout')
            attrs = {}
            for index in range(count):
                pos = 16 + start + size * index
                ns, name, raw = S.unpack_from('<III', chunk, pos)
                value_type = chunk[pos + 15]
                value = U32(chunk, pos + 16)
                if value_type == 3:
                    value = strings[value]
                key = (None if ns == NONE else strings[ns], strings[name])
                if key in attrs:
                    raise ValueError('Duplicate manifest attribute')
                attrs[key] = value
            stack.append(node)
            elements.append((tuple(stack), attrs))
        elif kind == 0x103:
            node = strings[U32(chunk, 20)]
            if not stack or stack.pop() != node:
                raise ValueError('Unbalanced manifest elements')
    if stack:
        raise ValueError('Unclosed manifest elements')
    return elements


def validate_manifest(data, filename):
    elements = manifest_elements(data)
    roots = [attrs for path, attrs in elements if path == ('manifest',)]
    if len(roots) != 1:
        raise ValueError('Expected one manifest root: ' + filename)
    root = roots[0]
    if root.get((None, 'package')) != SOURCE_PACKAGE or root.get((ANDROID, 'versionCode')) != VERSION_CODE:
        raise ValueError('Unexpected source application or version: ' + filename)
    applications = [attrs for path, attrs in elements if path == ('manifest', 'application')]
    if len(applications) != 1:
        raise ValueError('Expected one application: ' + filename)
    activities = [(path, attrs) for path, attrs in elements if path[-1] == 'activity']
    aliases = [path for path, _ in elements if path[-1] == 'activity-alias']
    filters = [path for path, _ in elements if path[-1] == 'intent-filter']
    if filename != 'base.apk':
        expected_split = filename.removeprefix('split_').removesuffix('.apk')
        if (not filename.startswith('split_config.') or root.get((None, 'split')) != expected_split
                or activities or aliases or filters or applications[0].get((ANDROID, 'hasCode')) != 0):
            raise ValueError('Unexpected configuration split: ' + filename)
        return
    activity_path = ('manifest', 'application', 'activity')
    filter_path = activity_path + ('intent-filter',)
    if (root.get((ANDROID, 'versionName')) != VERSION_NAME or (None, 'split') in root
            or len(activities) != 1 or activities[0][0] != activity_path
            or activities[0][1].get((ANDROID, 'name')) != 'henize.proto.MainActivity'
            or aliases or filters != [filter_path]):
        raise ValueError('Expected the supported single-activity Proto launcher')
    filter_children = [(path[-1], attrs.get((ANDROID, 'name')))
                       for path, attrs in elements if path[:-1] == filter_path]
    if sorted(filter_children) != [('action', 'android.intent.action.MAIN'),
                                   ('category', 'android.intent.category.LAUNCHER')]:
        raise ValueError('Unexpected launcher intent filter')


def validate_inputs(input_dir, output_dir):
    if input_dir.resolve() == output_dir.resolve():
        raise ValueError('Input and output directories must differ')
    inventory = json.loads((input_dir / 'inventory.json').read_text())
    if not isinstance(inventory, list) or not inventory:
        raise ValueError('The source APK inventory must not be empty')
    expected = {}
    for entry in inventory:
        name = entry['file']
        if name != Path(name).name or not name.endswith('.apk') or name in expected:
            raise ValueError('Invalid or duplicate inventory filename: ' + name)
        expected[name] = entry
    sources = sorted(input_dir.glob('*.apk'))
    names = {source.name for source in sources}
    if 'base.apk' not in names or names != set(expected):
        raise ValueError('Source APK filenames do not match the complete inventory')
    stale = {path.name for path in output_dir.glob('*.apk')} - names
    if stale:
        raise ValueError('Unexpected stale output APKs: ' + ', '.join(sorted(stale)))
    for source in sources:
        entry = expected[source.name]
        data = source.read_bytes()
        if len(data) != entry['bytes'] or digest(data) != entry['sha256']:
            raise ValueError('Source APK does not match inventory: ' + source.name)
        with zipfile.ZipFile(source) as archive:
            names_in_zip = archive.namelist()
            if len(set(names_in_zip)) != len(names_in_zip):
                raise ValueError('Duplicate source ZIP entries: ' + source.name)
            validate_manifest(archive.read('AndroidManifest.xml'), source.name)
    return sources


def patch_manifest(data, package, base):
    original=list(chunks(data)); pool=next(c for k,c in original if k==1)
    strings=strings_from_pool(pool)
    if 'henize.proto_c' not in strings: raise ValueError('Unexpected source package')
    strings[strings.index('henize.proto_c')]=package
    def sid(value):
        if value not in strings: strings.append(value)
        return strings.index(value)
    additions=[]
    if base:
        ns=sid(ANDROID); alias=sid('activity-alias')
        label=sid('label'); name=sid('name'); exported=sid('exported'); target=sid('targetActivity')
        clone_name=sid(package+'.Launcher'); clone_label=sid('Proto Clone'); main=sid('henize.proto.MainActivity')
        def attr(key, kind, value):
            return S.pack('<IIIHBBI', ns,key,value if kind==3 else NONE,8,0,kind,value)
        attrs=[attr(label,3,clone_label),attr(name,3,clone_name),attr(exported,0x12,0xffffffff),attr(target,3,main)]
        ext=S.pack('<IIHHHHHH',NONE,alias,20,20,len(attrs),0,0,0)
        alias_start=S.pack('<HHIII',0x102,16,16+len(ext)+20*len(attrs),0,NONE)+ext+b''.join(attrs)
        alias_end=S.pack('<HHIIIII',0x103,16,24,0,NONE,NONE,alias)
        additions=(alias_start,alias_end)
    out=[]; activity=False; moved_filter=[]; filter_depth=0
    for kind,c in original:
        if kind==1: continue
        if kind==0x180:
            if base:
                resource_ids=list(S.unpack_from('<'+'I'*((len(c)-8)//4),c,8))
                resource_ids.extend([0]*(len(strings)-len(resource_ids)))
                resource_ids[target]=0x01010202
                c=S.pack('<HHI',0x180,8,8+4*len(resource_ids))+S.pack('<'+'I'*len(resource_ids),*resource_ids)
            out.append(c);continue
        node=strings[U32(c,20)] if kind in (0x102,0x103) else None
        if base and kind==0x102 and node=='activity': activity=True
        if base and activity and kind==0x102 and node=='intent-filter': filter_depth=1;moved_filter.append(c);continue
        if filter_depth:
            moved_filter.append(c)
            if kind==0x102: filter_depth+=1
            elif kind==0x103: filter_depth-=1
            continue
        out.append(c)
        if base and kind==0x103 and node=='activity':
            activity=False
            if not moved_filter: raise ValueError('Launcher filter missing')
            out.extend([additions[0],*moved_filter,additions[1]])
    body=pool_from_strings(strings)+b''.join(out)
    return S.pack('<HHI',3,8,8+len(body))+body


def signature_entry(name):
    upper=name.upper()
    return (upper.startswith('META-INF/') and upper.endswith(('.MF','.SF','.RSA','.DSA','.EC'))) or name=='stamp-cert-sha256'


def digest(data): return hashlib.sha256(data).hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',type=Path,default=Path('reference/apk'))
    parser.add_argument('--output',type=Path,default=Path('artifacts/exact-clone'))
    parser.add_argument('--package',default='local.proto.clone')
    args=parser.parse_args()
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+', args.package) or args.package == SOURCE_PACKAGE:
        raise ValueError('Choose a valid, distinct Android package for the clone')
    sources = validate_inputs(args.input, args.output)
    sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')))
    bt=sdk/'build-tools/36.0.0'
    key=Path.home()/'.android/debug.keystore'
    if not key.exists(): raise SystemExit('Build assembleDebug once to provision the local Android development key.')
    args.output.mkdir(parents=True,exist_ok=True)
    evidence=[]
    for source in sources:
        unsigned=args.output/(source.stem+'-unsigned.apk')
        aligned=args.output/(source.stem+'-aligned.apk')
        final=args.output/source.name
        with zipfile.ZipFile(source) as src,zipfile.ZipFile(unsigned,'w') as dst:
            for entry in src.infolist():
                if signature_entry(entry.filename): continue
                payload=src.read(entry.filename)
                if entry.filename=='AndroidManifest.xml':payload=patch_manifest(payload,args.package,source.name=='base.apk')
                dst.writestr(entry,payload)
        subprocess.run([str(bt/'zipalign'),'-f','4',str(unsigned),str(aligned)],check=True)
        subprocess.run([str(bt/'apksigner'),'sign','--ks',str(key),'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',str(final),str(aligned)],check=True)
        subprocess.run([str(bt/'apksigner'),'verify',str(final)],check=True)
        unchanged=[]
        with zipfile.ZipFile(source) as src,zipfile.ZipFile(final) as dst:
            original_names, final_names = src.namelist(), dst.namelist()
            if len(set(final_names)) != len(final_names):
                raise RuntimeError('Duplicate signed APK ZIP entries: ' + source.name)
            original_payload = {name for name in original_names if not signature_entry(name)}
            final_payload = {name for name in final_names if not signature_entry(name)}
            if original_payload != final_payload:
                raise RuntimeError('Changed signed APK member set: ' + source.name)
            for entry in src.namelist():
                if signature_entry(entry) or entry=='AndroidManifest.xml':continue
                before,after=src.read(entry),dst.read(entry)
                if before!=after:raise RuntimeError('Changed game payload: '+entry)
                unchanged.append({'path':entry,'sha256':digest(before)})
        evidence.append({'input':source.name,'input_sha256':digest(source.read_bytes()),'output_sha256':digest(final.read_bytes()),'unchanged_entries':unchanged})
        unsigned.unlink();aligned.unlink()
        print('Verified',source.name,len(unchanged),'unchanged payload entries',flush=True)
    (args.output/'parity.json').write_text(json.dumps({'package':args.package,'version':'1.99c (Patch 1)','manifest_changes':['package','launcher alias labelled Proto Clone'],'apks':evidence},indent=2)+'\n')
    # A plain ZIP container for transport; it intentionally does not contain bundletool's toc.pb.
    with zipfile.ZipFile(args.output.parent/'proto-clone-1.99c-patch1.apks','w',zipfile.ZIP_DEFLATED) as archive:
        for source in sources:
            apk = args.output / source.name
            archive.write(apk, apk.name)
    print('Complete: plain ZIP of split APKs (not bundletool format).')
    print('Install using adb install-multiple -r followed by all output APK paths.')

if __name__=='__main__':main()
