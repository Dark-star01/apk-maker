# -*- coding: utf-8 -*-
"""دمج ملفات build.gradle (Groovy) نصياً بدون حذف أي شيء.
   - dependencies: نضيف السطر فقط إذا group:artifact غير موجود
   - خصائص مثل compileSdk 34: ما يحدده المستخدم يفوز
   - البلوكات (android / buildFeatures ...): تُدمج داخلياً، والناقص يُضاف
   - apply plugin: تُضاف بعد آخر apply plugin"""
import re
import textwrap

DEP_RE = re.compile(r"""['"]([\w.\-]+):([\w.\-]+)(?::[^'"]*)?['"]""")


def _norm(s):
    return re.sub(r'\s+', ' ', s.replace('"', "'")).strip()


def _skip_str(t, i):
    q = t[i]
    i += 1
    while i < len(t):
        if t[i] == '\\':
            i += 2
            continue
        if t[i] == q:
            return i + 1
        i += 1
    return i


def _match_brace(t, b):
    """يرجع موضع } المطابقة لـ { عند b"""
    depth = 0
    i = b
    n = len(t)
    while i < n:
        c = t[i]
        if c in '\'"':
            i = _skip_str(t, i)
            continue
        if t.startswith('//', i):
            j = t.find('\n', i)
            i = n if j < 0 else j
            continue
        if t.startswith('/*', i):
            j = t.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise ValueError('أقواس { } غير متوازنة في ملف gradle')


def items(t, lo, hi):
    """يقسّم المنطقة [lo,hi) إلى أسطر وبلوكات على مستواها الحالي"""
    out = []
    i = lo
    while i < hi:
        c = t[i]
        if c in ' \t\r\n;':
            i += 1
            continue
        if t.startswith('//', i):
            j = t.find('\n', i)
            i = hi if j < 0 else j
            continue
        if t.startswith('/*', i):
            j = t.find('*/', i + 2)
            i = hi if j < 0 else j + 2
            continue
        start = i
        depth = 0
        kind = None
        while i < hi:
            c = t[i]
            if c in '\'"':
                i = _skip_str(t, i)
                continue
            if depth <= 0 and (t.startswith('//', i) or t.startswith('/*', i)):
                break
            if c in '([':
                depth += 1
            elif c in ')]':
                depth -= 1
            elif c == '{':
                if depth <= 0:
                    kind = 'block'
                    break
                depth += 1
            elif c == '}':
                depth -= 1
            elif c == '\n' and depth <= 0:
                break
            i += 1
        if kind == 'block':
            close = _match_brace(t, i)
            out.append({'kind': 'block', 'head': t[start:i].strip(), 'lo': start,
                        'end': close + 1, 'body_lo': i + 1, 'body_hi': close})
            i = close + 1
        else:
            text = t[start:i].strip()
            if text:
                out.append({'kind': 'line', 'head': text, 'lo': start, 'end': i})
    return out


def _container(t, path):
    lo, hi = 0, len(t)
    close_indent = ''
    for head in path:
        found = None
        for it in items(t, lo, hi):
            if it['kind'] == 'block' and _norm(it['head']) == head:
                found = it
                break
        if found is None:
            return None
        ls = t.rfind('\n', 0, found['lo']) + 1
        close_indent = re.match(r'[ \t]*', t[ls:found['lo']]).group(0)
        lo, hi = found['body_lo'], found['body_hi']
    return lo, hi, close_indent


def _line_key(head, path):
    n = _norm(head)
    if n.startswith('apply '):
        return ('line', n)
    if path and path[-1] == 'dependencies':
        m = DEP_RE.search(head)
        if m:
            return ('dep', m.group(1), m.group(2))
        m = re.search(r"project\(\s*['\"]([^'\"]+)['\"]", head)
        if m:
            return ('proj', m.group(1))
        return ('line', n)
    m = re.match(r'^([A-Za-z_][\w.]*)\s*=\s*\S', head) or \
        re.match(r"^([A-Za-z_][\w.]*)\s+['\"\w$\[].*", head)
    if m:
        return ('prop', m.group(1))
    return ('line', n)


def _insert_text(t, path, text, indent_extra='    '):
    """يضيف text في نهاية الحاوية"""
    c = _container(t, path)
    if c is None:
        raise ValueError('الحاوية غير موجودة: %s' % ' > '.join(path))
    lo, hi, close_indent = c
    child_indent = close_indent + indent_extra if path else ''
    body = t[lo:hi].rstrip(' \t\r\n')
    block = textwrap.indent(textwrap.dedent(text), child_indent)
    if path:
        return t[:lo] + body + '\n' + block + '\n' + close_indent + t[hi:]
    sep = '\n' if body else ''
    return t[:lo] + body + sep + ('\n' if body else '') + block + '\n' + t[hi:]


def _replace_span(t, lo, end, new):
    return t[:lo] + new + t[end:]


def _merge_item(dst, path, it, src, log):
    c = _container(dst, path)
    if c is None:
        raise ValueError('الحاوية غير موجودة: %s' % ' > '.join(path))
    lo, hi, _ = c
    children = items(dst, lo, hi)
    where = (' > '.join(path) + ' > ') if path else ''
    ls = src.rfind('\n', 0, it['lo']) + 1
    prefix = src[ls:it['lo']] if not src[ls:it['lo']].strip() else ''

    if it['kind'] == 'block':
        raw = prefix + src[it['lo']:it['end']]
        in_repos = bool(path) and path[-1] == 'repositories'
        match = None
        for ch in children:
            if ch['kind'] != 'block':
                continue
            if in_repos:
                if _norm(dst[ch['lo']:ch['end']]) == _norm(src[it['lo']:it['end']]):
                    match = ch
                    break
            elif _norm(ch['head']) == _norm(it['head']):
                match = ch
                break
        if match is None:
            dst = _insert_text(dst, path, raw)
            log('   ➕ بلوك %s%s' % (where, _norm(it['head'])))
            return dst
        if in_repos:
            return dst  # نفس البلوك موجود
        for sub in items(src, it['body_lo'], it['body_hi']):
            dst = _merge_item(dst, path + [_norm(it['head'])], sub, src, log)
        return dst

    # سطر
    key = _line_key(it['head'], path)
    text = it['head']
    for ch in children:
        if ch['kind'] != 'line':
            continue
        if _line_key(ch['head'], path) == key:
            if _norm(ch['head']) == _norm(text):
                return dst
            if key[0] == 'prop':
                dst = _replace_span(dst, ch['lo'], ch['end'], text)
                log('   🔀 تحديث %s%s' % (where, _norm(text)))
            else:
                log('   ⏭️ موجود (يبقى الإصدار الحالي): %s' % _norm(text))
            return dst
    if not path and _norm(text).startswith('apply plugin'):
        last = None
        for ch in children:
            if ch['kind'] == 'line' and _norm(ch['head']).startswith('apply plugin'):
                last = ch
        if last is not None:
            dst = dst[:last['end']] + '\n' + text + dst[last['end']:]
        else:
            dst = text + '\n' + dst
        log('   ➕ %s' % _norm(text))
        return dst
    dst = _insert_text(dst, path, text)
    log('   ➕ %s%s' % (where, _norm(text)))
    return dst


def merge_text(dst, src, log):
    for it in items(src, 0, len(src)):
        dst = _merge_item(dst, [], it, src, log)
    return dst


def merge_file(src_path, dst_path, log):
    src = open(src_path, encoding='utf-8').read()
    dst = open(dst_path, encoding='utf-8').read()
    out = merge_text(dst, src, log)
    open(dst_path, 'w', encoding='utf-8').write(out)
