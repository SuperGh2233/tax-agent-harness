"""Read-only OOXML extraction. Run with --check to verify committed seed."""
from pathlib import Path
from zipfile import ZipFile
from decimal import Decimal
import xml.etree.ElementTree as E
import hashlib
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / '赛题二：财税Harness初赛材料包-v1.1.0'
S = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
expected = {'02_示例数据.xlsx': 'edb13b2a52d2f875033258836e8fc23c3e1353c46d101184048bac289e52ed0f',
            '03_业务规则.docx': '93b580f83b94fca4b795ec8e1336e22d799f5a91d581895f0562d29be1b84aa0'}
for name, digest in expected.items():
    assert hashlib.sha256((BASE / name).read_bytes()).hexdigest() == digest, name

with ZipFile(BASE / '02_示例数据.xlsx') as z:
    def rows(sheet, start, end, columns):
        root = E.fromstring(z.read(f'xl/worksheets/sheet{sheet}.xml'))
        result = []
        for row in root.findall(f'{S}sheetData/{S}row'):
            if start <= int(row.get('r')) <= end:
                cells = {c.get('r').rstrip('0123456789'): c.findtext(f'{S}v', '') for c in row}
                result.append([cells.get(c, '') for c in columns])
        return result
    enterprises = rows(2, 6, 7, 'ABC')
    users = rows(2, 12, 13, 'ABCD')
    invoices = rows(3, 6, 45, 'ABCDEFGH')
    bank = rows(4, 6, 53, 'ABCDEFG')
for records, amount_col in [(invoices, 5), (bank, 4)]:
    for row in records:
        row[2] = row[2][:10]
        cents = Decimal(row[amount_col]) * 100
        assert cents == cents.to_integral_value()
        row[amount_col] = int(cents)

with ZipFile(BASE / '03_业务规则.docx') as z:
    root = E.fromstring(z.read('word/document.xml'))
    table = root.find(f'.//{W}tbl')
    metadata = [[''.join(t.text or '' for t in c.iter(f'{W}t')) for c in r.findall(f'{W}tc')]
                for r in table.findall(f'{W}tr')][1:]
    paragraphs = [''.join(t.text or '' for t in p.iter(f'{W}t'))
                  for p in root.find(f'{W}body').findall(f'{W}p')]
rules = []
for i, row in enumerate(metadata):
    heading = next(p for p in paragraphs if p.startswith(f'{row[0]} {row[1]} {row[2]}版'))
    start = paragraphs.index(heading)
    end = next((j for j in range(start + 1, len(paragraphs)) if paragraphs[j].startswith(('R001 ', 'R002 ', 'R003 ', 'R004 '))), len(paragraphs))
    rules.append([row[0], row[2], row[1], row[4], row[5], '\n'.join(paragraphs[start:end])])
seed = {'sources': expected, 'enterprises': enterprises,
        'users': [u[:2] for u in users],
        'permissions': [[u[0], c, u[j]] for u in users for c, j in [('C001', 2), ('C002', 3)]],
        'invoices': invoices, 'bank_entries': bank, 'rules': rules,
        'rule_enterprises': [[r[0], r[1], c] for r in rules for c in
                             (['C001'] if r[0] == 'R002' else ['C002'] if r[0] == 'R003' else ['C001', 'C002'])]}
target = ROOT / 'src/main/resources/seed/official-v1.1.0.json'
content = json.dumps(seed, ensure_ascii=False, indent=2) + '\n'
if '--check' in sys.argv:
    assert target.read_text() == content, 'seed differs from original'
else:
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content)
print('PASS: original hashes and all seed fields verified')
