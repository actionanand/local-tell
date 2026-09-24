#!/usr/bin/env python3
"""Build a LocalTell offline pack from an already-enriched CSV.

CSV columns:
mcc,mnc,radio,area_code,cell_id,area_name,district,state,confidence,last_seen

This intentionally separates *cell-source/locality enrichment* from the compact
runtime-pack format. OpenCellID/OSM processing can evolve without changing the app.
"""
import argparse, csv, gzip, hashlib, shutil, sqlite3, time
from pathlib import Path

p=argparse.ArgumentParser(); p.add_argument('csv'); p.add_argument('output'); p.add_argument('--id', required=True); p.add_argument('--name', required=True); p.add_argument('--version', type=int, required=True); args=p.parse_args()
out=Path(args.output); out.parent.mkdir(parents=True, exist_ok=True); out.unlink(missing_ok=True)
schema=(Path(__file__).with_name('schema.sql')).read_text()
con=sqlite3.connect(out); con.executescript(schema)
con.executemany('INSERT INTO pack_meta(key,value) VALUES(?,?)', [('schema_version','1'),('pack_id',args.id),('pack_name',args.name),('pack_version',str(args.version)),('generated_at',str(int(time.time())))])
area_ids={}
batch=[]
row_count=0
def flush():
    global batch
    if batch:
        con.executemany('INSERT OR REPLACE INTO cell_lookup VALUES(?,?,?,?,?,?,?,?)', batch)
        batch=[]

with open(args.csv, newline='', encoding='utf-8') as f:
    for r in csv.DictReader(f):
        area_key=(r['area_name'], r.get('district') or '', r.get('state') or '')
        area_id=area_ids.get(area_key)
        if area_id is None:
            con.execute('INSERT OR IGNORE INTO area(area_name,district,state) VALUES(?,?,?)', area_key)
            area_id=con.execute('SELECT id FROM area WHERE area_name=? AND district=? AND state=?', area_key).fetchone()[0]
            area_ids[area_key]=area_id
        radio = {'UMTS': 'WCDMA'}.get(r['radio'].upper(), r['radio'].upper())
        batch.append((int(r['mcc']),int(r['mnc']),radio,int(r.get('area_code') or -1),int(r['cell_id']),area_id,int(r.get('confidence') or 50),int(r.get('last_seen') or 0) or None))
        row_count += 1
        if len(batch) >= 50000:
            flush()
flush()
con.commit(); con.execute('ANALYZE'); con.execute('VACUUM'); con.close()
gz=Path(str(out)+'.gz')
with open(out,'rb') as src, gzip.open(gz,'wb',compresslevel=9) as dst: shutil.copyfileobj(src,dst,1024*1024)
hobj=hashlib.sha256()
with open(gz,'rb') as hf:
    for chunk in iter(lambda: hf.read(1024*1024), b''): hobj.update(chunk)
h=hobj.hexdigest()
print(f'rows={row_count} db={out.stat().st_size} compressed={gz.stat().st_size} sha256={h}')
