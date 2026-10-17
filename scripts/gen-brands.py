import glob
rows=[]
for f in sorted(glob.glob('/home/user/quillpost/web/src/main/resources/brands/brands-*.tsv')):
    for line in open(f):
        line=line.rstrip('\n')
        if not line or line.startswith('#'): continue
        p=line.split('\t')
        while len(p)<4: p.append('')
        name,domains,aliases,cat=[x.strip() for x in p[:4]]
        domains=','.join(sorted({d.strip().lower() for d in domains.split(',') if d.strip()}))
        aliases=','.join(a.strip() for a in aliases.split(',') if a.strip())
        assert '|' not in name+domains+aliases+cat and '"' not in name+aliases, line
        rows.append(f"{name}|{domains}|{aliases}|{cat}")
text='\n'.join(rows)
# split into chunks under 60 KB (JVM constant-pool limit is 65535 bytes per string)
chunks=[]; cur=[]; size=0
for r in rows:
    if size+len(r.encode())+1 > 60000: chunks.append('\n'.join(cur)); cur=[]; size=0
    cur.append(r); size+=len(r.encode())+1
chunks.append('\n'.join(cur))
def kt(pkg):
    out=[f"package {pkg}", "",
         "/**", " * Extended organisation table (generated from the brands-N.tsv files under web/src/main/resources/brands by",
         " * scripts/gen-brands.py — edit the TSV files, not this file). Each line is",
         " * name|domains|aliases|category. Domains were checked to resolve in DNS when generated.", " */",
         "object BrandKnowledgeBaseExtra {", ""]
    for i,c in enumerate(chunks):
        out.append(f'    private const val CHUNK_{i} = """')
        out.append(c)
        out.append('"""'); out.append("")
    out.append("    private val chunks: List<String> get() = listOf(" + ", ".join(f"CHUNK_{i}" for i in range(len(chunks))) + ")")
    out.append("")
    out.append("    val brands: List<Brand> by lazy {")
    out.append("        chunks.asSequence().flatMap { it.lineSequence() }.mapNotNull { line ->")
    out.append("            val p = line.split('|')")
    out.append("            if (p.size < 4 || p[0].isBlank() || p[1].isBlank()) return@mapNotNull null")
    out.append("            val name = p[0].trim()")
    out.append("            val aliases = (listOf(name) + p[2].split(',')).map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()")
    out.append("            Brand(name, aliases, p[1].split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }, p[3].trim())")
    out.append("        }.toList()")
    out.append("    }")
    out.append("}")
    return '\n'.join(out)+'\n'
open('/home/user/quillpost/web/src/main/kotlin/info/socrtwo/quillpost/web/spam/BrandKnowledgeBaseExtra.kt','w').write(kt('info.socrtwo.quillpost.web.spam'))
# The Android app compiles the web sources directly, so one generated file serves every platform.
print('rows', len(rows), 'chunks', len(chunks), [len(c.encode()) for c in chunks])
