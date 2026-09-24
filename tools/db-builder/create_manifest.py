#!/usr/bin/env python3
import argparse, hashlib, json
from pathlib import Path
p=argparse.ArgumentParser(); p.add_argument('--base-url', required=True); p.add_argument('--generated-at', required=True); p.add_argument('packs', nargs='+', help='ID:Name:Version:path-to-db.gz')
a=p.parse_args(); result=[]
for spec in a.packs:
    id_,name,version,path=spec.split(':',3); f=Path(path); db=Path(str(f)[:-3]) if str(f).endswith('.gz') else None
    h=hashlib.sha256()
    with open(f,'rb') as hf:
        for chunk in iter(lambda: hf.read(1024*1024), b''): h.update(chunk)
    result.append({'id':id_,'name':name,'version':int(version),'downloadUrl':a.base_url.rstrip('/')+'/'+f.name,'sha256':h.hexdigest(),'compressedBytes':f.stat().st_size,'uncompressedBytes':db.stat().st_size if db and db.exists() else None})
print(json.dumps({'schemaVersion':1,'generatedAt':a.generated_at,'packs':result},indent=2))
