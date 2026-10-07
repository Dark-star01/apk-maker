# -*- coding: utf-8 -*-
"""دمج ملفات XML الخاصة بأندرويد (AndroidManifest.xml و res/values/*.xml)
   القاعدة: لا نحذف أي شيء موجود، نضيف الناقص فقط، وما يحدده المستخدم صراحةً يفوز."""
import copy
import xml.etree.ElementTree as ET

ANDROID = 'http://schemas.android.com/apk/res/android'
TOOLS = 'http://schemas.android.com/tools'
ET.register_namespace('android', ANDROID)
ET.register_namespace('tools', TOOLS)
NAME_ATTRS = ('{%s}name' % ANDROID, 'name')
# عناصر تظهر مرة واحدة فقط في المانيفست: نطابقها بالوسم وحده
SINGLETONS = {'application', 'queries', 'supports-screens', 'uses-sdk', 'compatible-screens'}


def load(path):
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    return ET.parse(path, parser)


def save(tree, path):
    ET.indent(tree, space='    ')
    tree.write(path, encoding='utf-8', xml_declaration=True)


def _short(attr):
    return attr.split('}')[-1] if '}' in attr else attr


def _key(el):
    """مفتاح العنصر = الوسم + قيمة name (إن وجدت)"""
    for a in NAME_ATTRS:
        if a in el.attrib:
            return (el.tag, el.attrib[a])
    return None


def _canon(el):
    return (el.tag, tuple(sorted(el.attrib.items())), (el.text or '').strip(),
            tuple(_canon(c) for c in el if not callable(c.tag)))


def _find(dst, child):
    key = _key(child)
    for d in dst:
        if callable(d.tag):
            continue
        if child.tag in SINGLETONS:
            if d.tag == child.tag:
                return d
        elif key is not None:
            if _key(d) == key:
                return d
        elif _canon(d) == _canon(child):
            return d
    return None


def merge_element(dst, src, log, indent='   '):
    """يدمج src داخل dst (بدون حذف)"""
    for k, v in src.attrib.items():
        if dst.tag == 'manifest' and k == 'package':
            continue  # لا نغيّر package أبداً (يكسر namespace)
        if k not in dst.attrib:
            dst.set(k, v)
            log('%s➕ خاصية %s على <%s>' % (indent, _short(k), dst.tag))
        elif dst.attrib[k] != v:
            dst.set(k, v)
            log('%s🔀 تعديل خاصية %s على <%s>' % (indent, _short(k), dst.tag))
    if (src.text or '').strip() and (dst.text or '').strip() != src.text.strip():
        dst.text = src.text.strip()
    for child in list(src):
        if callable(child.tag):
            continue  # نتجاهل تعليقات المصدر
        match = _find(dst, child)
        label = '<%s>' % child.tag
        key = _key(child)
        if key:
            label += ' ' + key[1]
        if match is None:
            new = copy.deepcopy(child)
            pos = None
            if dst.tag == 'manifest' and child.tag != 'application':
                for i, d in enumerate(list(dst)):
                    if d.tag == 'application':
                        pos = i
                        break
            if pos is None:
                dst.append(new)
            else:
                dst.insert(pos, new)
            log('%s➕ %s' % (indent, label))
        else:
            merge_element(match, child, log, indent + '  ')


def merge_file(src_path, dst_path, log):
    src = load(src_path).getroot()
    tree = load(dst_path)
    merge_element(tree.getroot(), src, log)
    save(tree, dst_path)


def add_permissions(manifest_path, perms, log):
    """يضيف uses-permission بدون تكرار"""
    tree = load(manifest_path)
    src = ET.Element('manifest')
    for p in perms:
        ET.SubElement(src, 'uses-permission', {'{%s}name' % ANDROID: p})
    merge_element(tree.getroot(), src, log)
    save(tree, manifest_path)
