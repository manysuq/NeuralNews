#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LoRA-дообучение Qwen3.5-0.8B на новостных заголовках (реальные + абсурдные)."""
import json
import os

import torch
from datasets import Dataset
from peft import LoraConfig, get_peft_model
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    DataCollatorForLanguageModeling,
    Trainer,
    TrainingArguments,
)

BASE = os.path.dirname(os.path.abspath(__file__))
MODEL_ID = 'Qwen/Qwen3.5-0.8B'
OUTPUT_DIR = os.path.join(BASE, 'output')
MERGED_DIR = os.path.join(BASE, 'merged_model')

print('=== 1. Токенизатор и модель ===')
tokenizer = AutoTokenizer.from_pretrained(MODEL_ID, trust_remote_code=True)
if tokenizer.pad_token is None:
    tokenizer.pad_token = tokenizer.eos_token

model = AutoModelForCausalLM.from_pretrained(
    MODEL_ID,
    torch_dtype=torch.bfloat16,
    device_map='cuda',
    trust_remote_code=True,
)
model.config.use_cache = False

print('=== 2. LoRA ===')
peft_config = LoraConfig(
    r=16,
    lora_alpha=32,
    target_modules=['q_proj', 'k_proj', 'v_proj', 'o_proj', 'gate_proj', 'up_proj', 'down_proj'],
    lora_dropout=0.05,
    bias='none',
    task_type='CAUSAL_LM',
)
model = get_peft_model(model, peft_config)
model.print_trainable_parameters()
model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={'use_reentrant': False})

print('=== 3. Данные ===')


def load(path):
    rows = [json.loads(l) for l in open(path, encoding='utf-8')]
    return [{'text': r['text']} for r in rows]


train_ds = Dataset.from_list(load(os.path.join(BASE, 'data', 'train.jsonl')))
eval_ds = Dataset.from_list(load(os.path.join(BASE, 'data', 'eval.jsonl')))
print('train:', len(train_ds), 'eval:', len(eval_ds))


def tokenize(batch):
    texts = [t + tokenizer.eos_token for t in batch['text']]
    return tokenizer(texts, truncation=True, max_length=48)


train_tok = train_ds.map(tokenize, batched=True, remove_columns=['text'])
eval_tok = eval_ds.map(tokenize, batched=True, remove_columns=['text'])

print('=== 4. Обучение ===')
args = TrainingArguments(
    output_dir=OUTPUT_DIR,
    per_device_train_batch_size=8,
    gradient_accumulation_steps=4,
    num_train_epochs=3,
    learning_rate=3e-4,
    lr_scheduler_type='cosine',
    warmup_steps=100,
    bf16=True,
    logging_steps=25,
    eval_strategy='epoch',
    save_strategy='no',
    save_total_limit=1,
    report_to='none',
    optim='adamw_torch_fused',
    dataloader_num_workers=0,
    seed=42,
)

collator = DataCollatorForLanguageModeling(tokenizer=tokenizer, mlm=False)

trainer = Trainer(
    model=model,
    args=args,
    train_dataset=train_tok,
    eval_dataset=eval_tok,
    data_collator=collator,
)

trainer.train()

print('=== 5. Слияние и сохранение ===')
model.config.use_cache = True
merged = model.merge_and_unload()
merged.save_pretrained(MERGED_DIR, safe_serialization=True)
tokenizer.save_pretrained(MERGED_DIR)
print('ГОТОВО ->', MERGED_DIR)
