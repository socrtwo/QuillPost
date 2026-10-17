import glob, sys, json, concurrent.futures as cf
import dns.resolver
res = dns.resolver.Resolver(configure=False); res.nameservers = ['8.8.8.8', '1.1.1.1']; res.timeout = 4; res.lifetime = 8
def alive(domain):
    for rtype in ('A', 'MX', 'AAAA', 'NS'):
        try:
            if res.resolve(domain, rtype): return True
        except dns.resolver.NoAnswer:
            continue
        except (dns.resolver.NXDOMAIN, dns.resolver.NoNameservers):
            return False
        except Exception:
            continue
    return False
rows = []
for f in sorted(glob.glob('/home/user/quillpost/web/src/main/resources/brands/brands-*.tsv')):
    for line in open(f):
        line = line.rstrip('\n')
        if not line or line.startswith('#'): continue
        parts = line.split('\t')
        while len(parts) < 4: parts.append('')
        rows.append(parts)
domains = sorted({d.strip().lower() for r in rows for d in r[1].split(',') if d.strip()})
print('entries', len(rows), 'unique domains', len(domains))
with cf.ThreadPoolExecutor(64) as ex:
    status = dict(zip(domains, ex.map(alive, domains)))
dead = sorted(d for d, ok in status.items() if not ok)
print('dead domains', len(dead)); print('\n'.join(dead))
json.dump({'status': status}, open('brand-dns.json', 'w'))
