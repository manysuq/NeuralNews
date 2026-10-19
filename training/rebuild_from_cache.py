#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Пересборка train/eval из готовых raw-кэшей с исправленными фильтрами
(слова «видео»/«фото» больше не отбраковывают заголовки)."""
import json
import os
import random
import re

BASE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(BASE, 'raw')
DATA = os.path.join(BASE, 'data')
random.seed(42)

BAD_PAT = re.compile(
    r'(?i)(читать далее|подробнее|^срочно\b|^мнение\b|^блог\b|афиша|'
    r'спецпроект|lenta\.ru|meduza|ria\.ru|ria novosti|https?://)')
CYRILLIC = re.compile(r'[А-Яа-яЁё]')


def clean(t):
    t = t.replace('\xa0', ' ')
    t = re.sub(r'\s+', ' ', t).strip()
    t = t.strip('«»"\'' + '-–—|:;,')
    if not (20 <= len(t) <= 170):
        return None
    words = t.split()
    if not (4 <= len(words) <= 22):
        return None
    letters = sum(c.isalpha() for c in t)
    if letters and len(CYRILLIC.findall(t)) / letters < 0.7:
        return None
    if BAD_PAT.search(t):
        return None
    if t.isupper() and len(t) > 30:
        return None
    if sum(c.isdigit() for c in t) > 10:
        return None
    return t


def key(t):
    return re.sub(r'[^а-яёa-z0-9]', '', t.lower())


def load(name):
    p = os.path.join(RAW, name)
    return [l.strip() for l in open(p, encoding='utf-8') if l.strip()] if os.path.exists(p) else []


real = load('real_raw.txt')
absurd = load('panorama.txt')
sys_path = __import__('sys').path
sys_path.insert(0, BASE)
from build_dataset import gen_absurd, CLASSIC  # noqa: E402

absurd += gen_absurd(3000) + CLASSIC * 60

seen, rows = {}, []
for label, src in (('real', real), ('absurd', absurd)):
    kept = 0
    for t in src:
        c = clean(t)
        if not c:
            continue
        k = key(c)
        if k in seen:
            continue
        seen[k] = label
        rows.append({'text': c, 'label': label})
        kept += 1
    print('[=] %s: %d -> %d' % (label, len(src), kept))

random.shuffle(rows)
n_eval = 250
train, evalr = rows[n_eval:], rows[:n_eval]
with open(os.path.join(DATA, 'train.jsonl'), 'w', encoding='utf-8') as f:
    for r in train:
        f.write(json.dumps(r, ensure_ascii=False) + '\n')
with open(os.path.join(DATA, 'eval.jsonl'), 'w', encoding='utf-8') as f:
    for r in evalr:
        f.write(json.dumps(r, ensure_ascii=False) + '\n')
print('\n[ИТОГО] train: %d, eval: %d' % (len(train), len(evalr)))
