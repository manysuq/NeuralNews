#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Сбор датасета новостных заголовков с нуля.

REAL (~20k): Gazeta.ru, Lenta.ru, RIA Novosti, Meduza, The Moscow Times (ru)
ABSURD (~20k): ИА «Панорама» (panorama.pub, 13.9k) + шаблонная генерация
               в стиле «РПЦ-новости»/фельетонного абсурда + классика NeuralMeduza
"""
import json
import os
import random
import re
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor

BASE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(BASE, 'raw')
DATA = os.path.join(BASE, 'data')
os.makedirs(RAW, exist_ok=True)
os.makedirs(DATA, exist_ok=True)
random.seed(42)

UA = {'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/126 Safari/537.36'}


def fetch(url, timeout=20):
    req = urllib.request.Request(url, headers=UA)
    return urllib.request.urlopen(req, timeout=timeout).read()


# ---------- очистка ----------
BAD_PAT = re.compile(
    r'(?i)\b(читать далее|подробнее|фото|видео|срочно|обновлено|мнение|блог|афиша|'
    r'спецпроект|direct speech|online|lenta\.ru|meduza|ria\.ru|https?://|@)\b')
CYRILLIC = re.compile(r'[А-Яа-яЁё]')


def clean(title):
    t = title.replace('\xa0', ' ')
    t = re.sub(r'\s+', ' ', t).strip()
    t = t.strip('«»"\'' + '-–—|:;,')
    t = re.sub(r'^💢\s*', '', t)
    if not (25 <= len(t) <= 170):
        return None
    words = t.split()
    if not (5 <= len(words) <= 22):
        return None
    letters = sum(c.isalpha() for c in t)
    if letters and len(CYRILLIC.findall(t)) / letters < 0.7:
        return None
    if BAD_PAT.search(t):
        return None
    if t.isupper() and len(t) > 30:
        return None
    if re.search(r'(?i)\b(19|20)\d{2}\b', t):
        return None
    if sum(c.isdigit() for c in t) > 8:
        return None
    return t


def key(t):
    return re.sub(r'[^а-яёa-z0-9]', '', t.lower())


# ---------- RSS ----------
def rss_titles(url, limit=200, tag='item'):
    try:
        xml = fetch(url).decode('utf-8', 'ignore')
        titles = re.findall(r'<%s>(.*?)</%s>' % (tag, tag), xml, re.S)
        out = []
        for it in titles:
            m = re.search(r'<title>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</title>', it, re.S)
            if m:
                t = re.sub(r'<!\[CDATA\[|\]\]>', '', m.group(1)).strip()
                if t:
                    out.append(t)
            if len(out) >= limit:
                break
        return out
    except Exception as e:
        print('  [-] RSS fail %s: %s' % (url, e))
        return []


def hf_titles(dataset, split, title_field, quota):
    from datasets import load_dataset
    ds = load_dataset(dataset, split=split, streaming=True)
    out = []
    for row in ds:
        t = (row.get(title_field) or '').strip()
        if t:
            out.append(t)
        if len(out) >= quota:
            break
    return out


# ---------- Panorama ----------
def panorama_titles():
    import requests
    from concurrent.futures import ThreadPoolExecutor
    sm = os.path.join(RAW, 'panorama_sitemap.xml')
    if not os.path.exists(sm):
        data = fetch('https://panorama.pub/sitemap.xml').decode('utf-8')
        open(sm, 'w', encoding='utf-8').write(data)
    ns = {'s': 'http://www.sitemaps.org/schemas/sitemap/0.9'}
    root = ET.parse(sm).getroot()
    urls = [e.text for e in root.findall('.//s:loc', ns) if '/news/' in (e.text or '')]
    print('[*] Panorama: %d url в sitemap' % len(urls))

    out_path = os.path.join(RAW, 'panorama.txt')
    done = {}
    if os.path.exists(out_path):
        for l in open(out_path, encoding='utf-8'):
            l = l.strip()
            if l:
                done[len(done)] = l
        print('[*] resume: уже собрано %d' % len(done))
    todo = [(i, u) for i, u in enumerate(urls) if i not in done]
    print('[*] осталось %d' % len(todo))

    S = requests.Session()
    S.headers.update({'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/126'})
    lock = __import__('threading').Lock()
    t0 = time.time()
    fout = open(out_path, 'a', encoding='utf-8')

    def get(pair):
        i, u = pair
        for _ in range(3):
            try:
                r = S.get(u, timeout=30)
                m = re.search(r'property="og:title" content="([^"]+)"', r.text)
                if m:
                    return (i, m.group(1))
            except Exception:
                time.sleep(1)
        return (i, None)

    n_ok = 0
    with ThreadPoolExecutor(8) as ex:
        for k, (i, title) in enumerate(ex.map(get, todo), 1):
            if title:
                with lock:
                    fout.write(title + '\n')
                    fout.flush()
                n_ok += 1
            if k % 1000 == 0:
                el = (time.time() - t0) / 60
                print('    ... %d/%d ok=%d, %.1f мин, ~%.1f url/s' % (
                    k, len(todo), n_ok, el, k / max(time.time() - t0, 1)))
    fout.close()
    print('[+] Panorama: +%d заголовков за %.1f мин' % (n_ok, (time.time() - t0) / 60))
    return [l.strip() for l in open(out_path, encoding='utf-8') if l.strip()]


# ---------- шаблоны абсурда (РПЦ-стиль, фельетонный абсурд) ----------
AGENCY = ['В РПЦ', 'Русская православная церковь', 'Патриарх Кирилл', 'Синод', 'Митрополит Иларион',
          'В Госдуме', 'Кремль', 'Роскомнадзор', 'Минздрав', 'Собянин', 'Мэр Москвы',
          'Минкульт', 'Минтруд', 'Минцифры', 'Роспотребнадзор', 'Следственный комитет',
          'Центробанк', 'Генпрокуратура', 'Минобороны', 'Роскосмос', 'Минприроды',
          'В Екатеринбурге', 'В Челябинске', 'В Тюмени', 'В Омске', 'В Новосибирске',
          'В Саратове', 'В Пензе', 'В Барнауле', 'В Красноярске', 'В Рязани',
          'В Тольятти', 'В Казани', 'В Уфе', 'В Перми', 'В Воронеже', 'В Самаре',
          'В Кемерово', 'во Владивостоке', 'В Петербурге', 'В Крыму', 'В Сибири',
          'В Школе №14 города Наро-Фоминска', 'В Дагестане', 'В Калмыкии', 'В Бурятии']
VERB = ['призвали', 'потребовали', 'запретили', 'разрешили', 'поручили', 'признали',
        'предупредили', 'одобрили', 'заявили', 'предложили', 'выступили за',
        'запустили', 'приостановили', 'вернули', 'удвоили', 'упразднили']
WHAT = [
    'освящать хлеб перед нарезкой', 'писать жалобы на прохожих голосом',
    'ввести обязательный уроки хождения по граблям', 'перейти на правое богослужение',
    'выдавать справки о патриотизме домашним котам', 'замораживать грядки святой водой',
    'учитывать сны граждан при начислении пенсий', 'перевести Вселенскую на удаленку',
    'облагать десятиной тишину', 'проводить крестовые походы против гололеда',
    'выдать каждому россиянину личного ангела', 'запретить смех до обеда',
    'увеличить медленный список запрещенных мыслей', 'переписать Евангелие на Python',
    'обучить полицейских телепатии', 'лечить интернет молитвами по расписанию',
    'ввести налог на грустные лица в метро', 'зарегистрировать все облака',
    'выдать лицензии на чудеса', 'создать православный аналог Линукса',
    'проводить инвентаризацию святых', 'заменить иконостасы терминалами',
    'выдать визы бесам', 'удостоверить личность каждого бобра',
    'запретить индивидуальный воскресенья', 'национализировать мандрагору',
    'выдавать индульгенции за донаты', 'считать количество святых ежесекундно',
    'ввести третий глаз для пожарных', 'выдать каждому по бесплатному епископу',
]
END = ['до конца года', 'с января', 'в пилотном режиме', 'для экономии бюджета',
       'на всей территории России', 'в качестве эксперимента', 'по всей стране',
       'в связи с непростыми временами', 'для укрепления духовности',
       'ради безопасности', 'в трех регионах', 'с нового учебного года']


def gen_absurd(n):
    out = []
    for _ in range(n):
        a, b, w, e = random.choice(AGENCY), random.choice(VERB), random.choice(WHAT), random.choice(END)
        s = '%s %s %s %s' % (a, b, w, e)
        if random.random() < 0.5:
            s = '%s %s %s' % (a, b, w)
        out.append(s)
    return out


CLASSIC = [
    'Собянин потребовал от мэрии Москвы поставить на пьедестал двухметрового гуся',
    'Челябинский губернатор поручил создать «небольшие люди» для экономии бюджета',
    'В Госдуме предложили освятить интернет святой водой из Байкала',
    'Роскомнадзор пригрозил заблокировать гравитацию за распространение запрещенных падений',
    'Жительница Воронежа родила трактор после молитвы о хорошем урожае',
    'В Сколково разработали нано-лапти с поддержкой ГЛОНАСС и 5G',
    'РПЦ признала грехом покупку майонежа жирностью менее 67 процентов',
    'Госдума приняла в первом чтении закон об обязательной регистрации берез',
    'Роскосмос запустил на орбиту банку квашеной капусты',
    'Минцифры предложило заменить пароли на портале Госуслуг отрывками из «Войны и мира»',
    'В Тюмени коммунальщики заасфальтировали упавшую лужу',
    'В Перми памятник букве П признали экстремистским из-за намека на букву О',
    'В Уссурийске тракторист случайно вспахал соседнее государство',
    'В пункте выдачи Wildberries в Сызрани объявили о независимость от остального ТЦ',
    'В Челябинске школьники на уроках труда собрали ядерный реактор из старых батареек',
    'МВД объявило в розыск колобка за незаконное пересечение границы леса',
    'Церковный суд запретил верующим пользоваться калькулятором из-за сходства знака плюс с крестом',
    'В Барнауле пьяный медведь угнал велосипед и уехал устраиваться на работу в цирк',
    'Ученые из РАН доказали, что земля плоская только по понедельникам',
    'В Самаре открылся первый в мире музей очередей за колбасой',
]


def cached_lines(name):
    p = os.path.join(RAW, name)
    if os.path.exists(p):
        ls = [l.strip() for l in open(p, encoding='utf-8') if l.strip()]
        if ls:
            return ls
    return None


def save_lines(name, lines):
    open(os.path.join(RAW, name), 'w', encoding='utf-8').write('\n'.join(lines))


def fetch_hf_cached(name, dataset, split, title_field, quota):
    c = cached_lines(name)
    if c is not None:
        print('[*] %s из кэша: %d' % (name, len(c)))
        return c
    ls = hf_titles(dataset, split, title_field, quota)
    if ls:
        save_lines(name, ls)
    return ls


def fetch_rss_cached(name, url, limit=200):
    c = cached_lines(name)
    if c is not None:
        print('[*] %s из кэша: %d' % (name, len(c)))
        return c
    ls = rss_titles(url, limit=limit)
    if ls:
        save_lines(name, ls)
    return ls


def main():
    real, absurd = [], []

    print('=== REAL ===')
    real += fetch_hf_cached('gazeta.txt', 'IlyaGUsev/gazeta', 'train', 'title', 25000)
    print('[+] Gazeta.ru: %d' % len(real))
    real += fetch_hf_cached('lenta.txt', 'zloelias/lenta-ru-short', 'train', 'title', 5000)
    print('[+] Lenta short: %d' % len(real))
    real += fetch_hf_cached('lenta_full.txt', 'zloelias/lenta-ru', 'train', 'title', 6000)
    print('[+] Lenta full: %d' % len(real))
    real += fetch_rss_cached('ria.txt', 'https://ria.ru/export/rss2/archive/index.xml', 400)
    print('[+] RIA: %d' % len(real))
    for name, url in [
        ('meduza.txt', 'https://meduza.io/rss/news'),
        ('lenta_rss.txt', 'https://lenta.ru/rss/'),
        ('moscowtimes.txt', 'https://ru.themoscowtimes.com/rss/news'),
        ('tass.txt', 'https://tass.ru/rss/v2.xml'),
        ('interfax.txt', 'https://www.interfax.ru/rss.asp'),
        ('kommersant.txt', 'https://www.kommersant.ru/RSS/news.xml'),
    ]:
        real += fetch_rss_cached(name, url, 200)
        print('[+] %s: %d' % (name, len(real)))
    save_lines('real_raw.txt', real)

    print('=== ABSURD ===')
    pan_cache = os.path.join(RAW, 'panorama.txt')
    if os.path.exists(pan_cache):
        pan = [l for l in open(pan_cache, encoding='utf-8').read().splitlines() if l.strip()]
        print('[*] Panorama из кэша: %d' % len(pan))
    else:
        pan = panorama_titles()
        open(pan_cache, 'w', encoding='utf-8').write('\n'.join(pan))
    absurd += pan
    absurd += gen_absurd(3000) + CLASSIC * 60
    open(os.path.join(RAW, 'absurd_raw.txt'), 'w', encoding='utf-8').write('\n'.join(absurd))

    # ---------- финальная чистка ----------
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
        print('[=] %s: оставлено %d' % (label, kept))

    random.shuffle(rows)
    # сплит 99/1 для eval
    n_eval = max(200, int(len(rows) * 0.01))
    train, evalr = rows[n_eval:], rows[:n_eval]
    with open(os.path.join(DATA, 'train.jsonl'), 'w', encoding='utf-8') as f:
        for r in train:
            f.write(json.dumps(r, ensure_ascii=False) + '\n')
    with open(os.path.join(DATA, 'eval.jsonl'), 'w', encoding='utf-8') as f:
        for r in evalr:
            f.write(json.dumps(r, ensure_ascii=False) + '\n')
    with open(os.path.join(DATA, 'train.txt'), 'w', encoding='utf-8') as f:
        for r in train:
            f.write(r['text'] + '\n')

    print('\n[ИТОГО] train: %d, eval: %d (real=%d, absurd=%d)' % (
        len(train), len(evalr),
        sum(r['label'] == 'real' for r in train),
        sum(r['label'] == 'absurd' for r in train)))


if __name__ == '__main__':
    main()
