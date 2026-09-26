#!/usr/bin/env python3
"""Build a LocalTell schema-v2 offline pack from cell and tower CSVs.

Cell CSV columns:
mcc,mnc,radio,area_code,cell_id,source_site_id,pci,arfcn,confidence,last_seen

Tower CSV columns:
source_site_id,latitude,longitude,area_name,district,state,address,source

The source site ID connects each radio cell to a physical tower site. PCI and ARFCN
are supporting metadata; runtime matching continues to use the cell identity.
"""
import argparse, csv, gzip, hashlib, shutil, sqlite3, time
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('csv', help='Cell-to-tower CSV')
p.add_argument('output')
p.add_argument('--towers', required=True, help='Tower site CSV')
p.add_argument('--id', required=True)
p.add_argument('--name', required=True)
p.add_argument('--version', type=int, required=True)
args=p.parse_args()
out=Path(args.output); out.parent.mkdir(parents=True, exist_ok=True); out.unlink(missing_ok=True)
schema=(Path(__file__).with_name('schema.sql')).read_text()
con=sqlite3.connect(out); con.executescript(schema)
con.executemany('INSERT INTO pack_meta(key,value) VALUES(?,?)', [('schema_version','2'),('pack_id',args.id),('pack_name',args.name),('pack_version',str(args.version)),('generated_at',str(int(time.time())))])
tower_ids={}

with open(args.towers, newline='', encoding='utf-8') as f:
    for r in csv.DictReader(f):
        site_id=(r.get('source_site_id') or '').strip()
        if not site_id:
            raise ValueError('Tower CSV contains a row without source_site_id')
        if site_id in tower_ids:
            raise ValueError(f'Duplicate source_site_id: {site_id}')
        con.execute(
            '''INSERT INTO tower_site(source_site_id,latitude,longitude,area_name,district,state,address,source)
               VALUES(?,?,?,?,?,?,?,?)''',
            (
                site_id,
                float(r['latitude']) if r.get('latitude') else None,
                float(r['longitude']) if r.get('longitude') else None,
                (r.get('area_name') or '').strip(),
                (r.get('district') or '').strip(),
                (r.get('state') or '').strip(),
                (r.get('address') or '').strip(),
                (r.get('source') or '').strip(),
            ),
        )
        tower_ids[site_id]=con.execute('SELECT id FROM tower_site WHERE source_site_id=?', (site_id,)).fetchone()[0]

batch=[]
row_count=0
def flush():
    global batch
    if batch:
        con.executemany('INSERT OR REPLACE INTO cell_lookup VALUES(?,?,?,?,?,?,?,?,?,?)', batch)
        batch=[]

with open(args.csv, newline='', encoding='utf-8') as f:
    for r in csv.DictReader(f):
        site_id=(r.get('source_site_id') or '').strip()
        tower_id=tower_ids.get(site_id)
        if tower_id is None:
            raise ValueError(f'Unknown source_site_id in cell CSV: {site_id or "(blank)"}')
        radio = {'UMTS': 'WCDMA'}.get(r['radio'].upper(), r['radio'].upper())
        batch.append((
            int(r['mcc']), int(r['mnc']), radio, int(r.get('area_code') or -1), int(r['cell_id']), tower_id,
            int(r['pci']) if r.get('pci') else None, int(r['arfcn']) if r.get('arfcn') else None,
            int(r.get('confidence') or 50), int(r.get('last_seen') or 0) or None,
        ))
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
print(f'rows={row_count} towers={len(tower_ids)} db={out.stat().st_size} compressed={gz.stat().st_size} sha256={h}')
