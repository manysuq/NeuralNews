#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Генерация новостных заголовков дообученной моделью."""
import argparse
import logging
import random
import os

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

logging.getLogger('transformers').setLevel(logging.ERROR)

BASE = os.path.dirname(os.path.abspath(__file__))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--model', default=os.path.join(BASE, 'merged_model'))
    ap.add_argument('--n', type=int, default=30)
    ap.add_argument('--temperature', type=float, default=0.9)
    ap.add_argument('--top-p', type=float, default=0.95)
    ap.add_argument('--seed', type=int, default=None)
    ap.add_argument('--prompt', default=' ',
                    help='стартовый токен (по умолчанию пробел = генерация с нуля)')
    args = ap.parse_args()

    if args.seed is not None:
        torch.manual_seed(args.seed)

    tok = AutoTokenizer.from_pretrained(args.model, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        args.model, torch_dtype=torch.bfloat16, device_map='cuda', trust_remote_code=True)
    model.eval()

    print('=== Сгенерированные заголовки ===')
    seen = set()
    shown = 0
    tries = 0
    while shown < args.n and tries < args.n * 6:
        tries += 1
        prefix = args.prompt if tries == 1 else random.choice([' ', 'В', 'Путин', 'Госдума', 'Собянин',
                                                             'Роскомнадзор', 'В Москве'])
        enc = tok(prefix, return_tensors='pt', add_special_tokens=False).to('cuda')
        with torch.no_grad():
            out = model.generate(
                **enc,
                max_new_tokens=200,
                do_sample=True,
                temperature=args.temperature,
                top_p=args.top_p,
                pad_token_id=tok.eos_token_id,
                use_cache=True,
            )
        text = tok.decode(out[0][enc['input_ids'].shape[1]:], skip_special_tokens=True)
        head = text.split('\n')[0].strip()
        if len(head) < 15 or head.lower() in seen:
            continue
        seen.add(head.lower())
        shown += 1
        print('%2d. %s' % (shown, head))
    print('--- проверено %d генераций' % tries)


if __name__ == '__main__':
    main()
