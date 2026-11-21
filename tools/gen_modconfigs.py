"""Generate AMP settings for GTNH mod configs.

Reads a GTNH server's config/ and serverutilities/ folders (fresh pack values = defaults) and writes:
  minecraft-gtnhconfig.json      base settings (tools/base-config.json) + one field per mod setting
  minecraft-gtnhmetaconfig.json  base mappings + amp-modconfigs.txt mapping read by ModConfigApply.java
  tools/modconfigs-report.txt    what was included, grouped, and skipped

Usage: python tools/gen_modconfigs.py <server dir with config/ and serverutilities/>
Re-run against a new pack version's server files to refresh the settings.
"""
import hashlib
import json
import os
import re
import sys
from collections import Counter, defaultdict

GROUPS = 10
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

CATEGORY = re.compile(r'^\s*("[^"]*"|[^{}#=]+?)\s*\{\s*$')
SETTING = re.compile(r'^(\s*)([BIDS]):("[^"]*"|[^=<]+?)=(.*)$')
LIST_START = re.compile(r'^\s*[BIDS]:("[^"]*"|[^=<]+?)\s*<\s*$')

# Big data tables: per-block / per-material / per-biome rows, not options.
SKIP_FILES = {
    'config/AWWayofTime.cfg', 'config/TGregworks.cfg', 'config/SuperTiC/effects.cfg',
    'config/IguanaTinkerTweaks/BlockDefaults.cfg', 'config/IguanaTinkerTweaks/MaterialDefaults.cfg',
    'config/IguanaTinkerTweaks/ToolDefaults.cfg',
}
SKIP_PREFIXES = ('config/RTG/biomes/', 'config/txloader/', 'serverutilities/client/')
# Per-flower / per-mob tables inside otherwise useful files.
SKIP_SECTIONS = {
    'config/Botania.cfg': {'shedding'},
    'config/InfernalMobs.cfg': {'entitiesalwaysinfernal', 'permittedentities', 'entitybasehealth'},
}
SKIP_MODS = {'autotorch', 'angelica-modules', 'angelica'}

CLIENT = re.compile(r'client|render|gui|graphic|visual|sound|music|particle|hud|overlay|tooltip|texture|keybind'
                    r'|color|colour|display|font|animation|shader|waila|nei|zoom|camera|fov|screen|icon|model'
                    r'|light ?map|fog|sky|cloud|chat ?format', re.I)
IDS = re.compile(r'(^|[ _.])(id|ids)$|id$|dimension ?id|biome ?id|potion ?id|enchant\w* ?id|entity ?id'
                 r'|block ?id|item ?id|^ids?[ _]', re.I)
INTERNAL = re.compile(r'debug|logging|log |verbose|version|mixin|compat|integration|asm|coremod|transformer'
                      r'|patch|experimental|benchmark|profil|thread|cache|telemetry|update ?check|checkForUpdates'
                      r'|metrics|lang', re.I)
RANGE = re.compile(r'\[range:\s*(-?[\d.]+)\s*~\s*(-?[\d.]+)', re.I)
VALID = re.compile(r'\[valid:\s*([^\]]+)\]', re.I)
INT32 = (-2**31, 2**31 - 1)


def strip_quotes(s):
    s = s.strip()
    return s[1:-1] if len(s) >= 2 and s[0] == s[-1] == '"' else s


def esc(s):
    return s.replace('%', '%25').replace('|', '%7C').replace('=', '%3D')


def mod_of(rel):
    parts = rel.split('/')
    if parts[0] == 'serverutilities':
        return 'ServerUtilities'
    return parts[1] if len(parts) > 2 else os.path.splitext(parts[1])[0]


def parse(path, rel):
    """Yield (category path, type, name, value, comment) for scalar settings in a Forge cfg."""
    text = open(path, encoding='utf-8', errors='replace').read()
    stack, comment, in_list = [], [], False
    for line in text.splitlines():
        s = line.strip()
        if in_list:
            if s == '>':
                in_list = False
            continue
        if LIST_START.match(line):
            in_list, comment = True, []
            continue
        c = CATEGORY.match(line)
        if c:
            stack.append(strip_quotes(c.group(1)))
            comment = []
            continue
        if s == '}':
            if stack:
                stack.pop()
            comment = []
            continue
        if s.startswith('#'):
            t = s.lstrip('#').strip()
            if t and not set(t) <= set('#-='):
                comment.append(t)
            continue
        m = SETTING.match(line)
        if m:
            yield '.'.join(stack), m.group(2), strip_quotes(m.group(3)), m.group(4), ' '.join(comment)
        comment = []


def keep(rel, cat, name):
    if rel in SKIP_FILES or rel.startswith(SKIP_PREFIXES):
        return 'table'
    if mod_of(rel).lower() in SKIP_MODS:
        return 'skipped mod'
    top = cat.split('.')[0] if cat else ''
    if top in SKIP_SECTIONS.get(rel, ()):
        return 'table'
    where = rel + ' ' + cat
    if CLIENT.search(where) or CLIENT.search(name):
        return 'client'
    if IDS.search(name) or re.search(r'\bids?\b', cat, re.I):
        return 'ids'
    if INTERNAL.search(name) or INTERNAL.search(cat):
        return 'internal'
    return None


def pretty(s):
    return s.replace('_', ' ').strip()


def letter_groups(mods):
    """Split mods (sorted by name) into GROUPS runs of whole starting letters, as evenly as possible."""
    by_letter = defaultdict(list)
    for m in mods:
        by_letter[m[0][0].upper()].append(m)
    letters = sorted(by_letter)
    sizes = [len(by_letter[l]) for l in letters]
    n, k = len(letters), min(GROUPS, len(letters))
    target = len(mods) / k
    inf = float('inf')
    # cost[i][g]: best squared-deviation cost for letters[i:] split into g groups
    cost = [[inf] * (k + 1) for _ in range(n + 1)]
    cut = [[0] * (k + 1) for _ in range(n + 1)]
    cost[n][0] = 0
    for i in range(n - 1, -1, -1):
        for g in range(1, k + 1):
            total = 0
            for j in range(i + 1, n + 1):
                total += sizes[j - 1]
                rest = cost[j][g - 1]
                if rest < inf and (total - target) ** 2 + rest < cost[i][g]:
                    cost[i][g], cut[i][g] = (total - target) ** 2 + rest, j
    groups, labels, i, g = [], [], 0, k
    while g:
        j = cut[i][g]
        groups.append([m for l in letters[i:j] for m in by_letter[l]])
        labels.append(letters[i] if j - i == 1 else f'{letters[i]}-{letters[j - 1]}')
        i, g = j, g - 1
    return groups, labels


def main(server):
    settings = defaultdict(list)   # mod -> [entries]
    skipped = Counter()
    for top in ('config', 'serverutilities'):
        for dp, _, fns in os.walk(os.path.join(server, top)):
            for fn in sorted(fns):
                if not fn.lower().endswith('.cfg'):
                    continue
                path = os.path.join(dp, fn)
                rel = os.path.relpath(path, server).replace('\\', '/')
                for cat, typ, name, value, comment in parse(path, rel):
                    why = keep(rel, cat, name)
                    if why:
                        skipped[why] += 1
                        continue
                    settings[mod_of(rel)].append(dict(rel=rel, cat=cat, typ=typ, name=name, value=value, comment=comment))

    # merge mods whose names differ only by case (e.g. climatecontrol / ClimateControl)
    merged = {}
    for mod in sorted(settings, key=str.lower):
        k = mod.lower()
        if k in merged:
            merged[k][1].extend(settings[mod])
        else:
            merged[k] = [mod, list(settings[mod])]
    mods = sorted(merged.values(), key=lambda m: m[0].lower())

    groups, labels = letter_groups(mods)

    fields, mappings, report = [], {}, []
    used = set()
    for gi, (label, group) in enumerate(zip(labels, groups)):
        category = f'Mods {label}:extension'
        report.append(f'== Mods {label}: {len(group)} mods')
        for order, (mod, entries) in enumerate(group, start=1):
            files = sorted({e['rel'] for e in entries})
            multi = len(files) > 1
            report.append(f'   {mod}: {len(entries)} settings, {len(files)} file(s)')
            for e in entries:
                key = f"{esc(e['rel'])}|{esc(e['cat'])}|{e['typ']}:{esc(e['name'])}"
                fid = 'MC' + hashlib.sha1(key.encode()).hexdigest()[:12]
                if fid in used:
                    continue
                used.add(fid)
                where = []
                if multi:
                    where.append(pretty(os.path.splitext(os.path.basename(e['rel']))[0]))
                for c in e['cat'].split('.'):
                    c = pretty(c)
                    if c and c.lower() != 'general' and (not where or where[-1].lower() != c.lower()):
                        where.append(c)
                display = ' > '.join(where + [pretty(e['name'])])
                desc = e['comment'][:600]
                loc = f"[{e['rel']}" + (f" > {e['cat']}" if e['cat'] else '') + ']'
                field = {
                    'DisplayName': display,
                    'Category': category,
                    'Subcategory': f'{mod}:extension:{order}',
                    'Description': (desc + ' ' + loc).strip(),
                    'Keywords': ','.join(dict.fromkeys([mod, e['name'], os.path.basename(e['rel'])])),
                    'FieldName': fid,
                    'ParamFieldName': fid,
                    'DefaultValue': e['value'].strip(),
                    'EnumValues': {},
                }
                if e['typ'] == 'B':
                    field['InputType'] = 'checkbox'
                    field['DefaultValue'] = e['value'].strip().lower()
                    field['EnumValues'] = {'False': 'false', 'True': 'true'}
                elif e['typ'] == 'I':
                    field['InputType'] = 'number'
                    r = RANGE.search(e['comment'])
                    if r:
                        try:
                            lo, hi = int(float(r.group(1))), int(float(r.group(2)))
                            if INT32[0] <= lo and hi <= INT32[1]:
                                field['MinValue'], field['MaxValue'] = str(lo), str(hi)
                        except ValueError:
                            pass
                    try:
                        if not INT32[0] <= int(e['value']) <= INT32[1]:
                            field['InputType'] = 'text'
                    except ValueError:
                        field['InputType'] = 'text'
                else:
                    field['InputType'] = 'text'
                    v = VALID.search(e['comment'])
                    if e['typ'] == 'S' and v:
                        opts = [o.strip() for o in v.group(1).split(',') if o.strip()]
                        if e['value'].strip() in opts and len(opts) <= 40:
                            field['InputType'] = 'enum'
                            field['EnumValues'] = {o: o for o in opts}
                fields.append(field)
                mappings[key + '='] = fid

    base = json.load(open(os.path.join(HERE, 'base-config.json'), encoding='utf-8'))
    with open(os.path.join(REPO, 'minecraft-gtnhconfig.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(base + fields, f, indent=1, ensure_ascii=False)

    meta = json.load(open(os.path.join(HERE, 'base-metaconfig.json'), encoding='utf-8'))
    meta.append({
        'ConfigFile': 'amp-modconfigs.txt',
        'ConfigType': 'kvp',
        'ConfigFormat': '{0}{1}',
        'Importable': False,
        'Subsections': [{'Heading': '$root', 'SettingMappings': mappings}],
    })
    with open(os.path.join(REPO, 'minecraft-gtnhmetaconfig.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(meta, f, indent=1, ensure_ascii=False)

    head = [f'{len(fields)} settings from {len(mods)} mods in {GROUPS} groups',
            f'skipped: {dict(skipped)}', '']
    with open(os.path.join(HERE, 'modconfigs-report.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(head + report) + '\n')
    print('\n'.join(head))
    for label, g in zip(labels, groups):
        print(f'  Mods {label}: {len(g)} mods ({g[0][0]} .. {g[-1][0]})')


if __name__ == '__main__':
    main(sys.argv[1])
