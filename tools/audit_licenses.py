"""Audit the resolved Release runtime, including inherited Maven licenses and embedded notices."""
from pathlib import Path
import argparse,hashlib,io,json,zipfile,xml.etree.ElementTree as ET
NS={'m':'http://maven.apache.org/POM/4.0.0'}
def sha(b):return hashlib.sha256(b).hexdigest()
def pom_for(cache,g,n,v):
    ps=sorted((cache/g/n/v).glob('**/*.pom'))
    if not ps:raise ValueError(f'Missing POM: {g}:{n}:{v}')
    return ps[0]
def licenses(cache,g,n,v,seen=None):
    seen=set() if seen is None else seen
    key=(g,n,v)
    if key in seen:raise ValueError(f'POM parent cycle: {key}')
    seen.add(key);p=pom_for(cache,*key);r=ET.parse(p).getroot()
    found=[{'name':x.findtext('m:name',namespaces=NS),'url':x.findtext('m:url',namespaces=NS)} for x in r.findall('m:licenses/m:license',NS)]
    if found:return found,':'.join(key),sha(p.read_bytes())
    parent=r.find('m:parent',NS)
    if parent is None:raise ValueError(f'No license or parent: {key}')
    return licenses(cache,*(parent.findtext('m:'+x,namespaces=NS) for x in ['groupId','artifactId','version']),seen)
def embedded(data,prefix=''):
    result=[]
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for n in sorted(z.namelist()):
            if n.endswith('/'):continue
            leaf=n.rsplit('/',1)[-1].lower()
            if not leaf.endswith(('.class','.kotlin_metadata')) and (leaf.startswith(('license','notice','copying','copyright')) or leaf in ('al2.0','lgpl2.1')):
                b=z.read(n)
                try:t=b.decode('utf-8-sig')
                except UnicodeDecodeError:raise ValueError(f'Non-text license requires review: {prefix+n}')
                result.append((prefix+n,t))
            elif n=='classes.jar' or n.startswith('libs/') and n.endswith('.jar'):
                result+=embedded(z.read(n),prefix+n+'!/')
    return result
def main():
    ap=argparse.ArgumentParser();ap.add_argument('--artifacts',type=Path,required=True);ap.add_argument('--cache',type=Path,required=True);ap.add_argument('--root',type=Path,default=Path('.'));a=ap.parse_args()
    rows=json.loads(a.artifacts.read_text(encoding='utf-8-sig'));report=[];texts={};links=[];internal=[]
    for x in rows:
        coord=f"{x['group']}:{x['name']}:{x['version']}";p=Path(x['file'])
        if x['group']=='DecibelEvi' and x['name']=='core':internal.append(coord);continue
        ls,origin,pomhash=licenses(a.cache,x['group'],x['name'],x['version'])
        if any('apache' not in (l['name'] or '').lower() or not any(t in (l['name'] or '') for t in ['2.0','2']) for l in ls):raise ValueError(f'License requires manual review: {coord}: {ls}')
        refs=[]
        for path,text in embedded(p.read_bytes()):
            digest=sha(text.encode());texts.setdefault(digest,{'text':text,'sources':[]})['sources'].append(coord+'!/'+path);refs.append({'path':path,'textSha256':digest})
        report.append({'coordinate':coord,'licenses':ls,'licenseEvidencePom':origin,'licenseEvidencePomSha256':pomhash,'artifactSha256':sha(p.read_bytes()),'embeddedNotices':refs})
    if not texts:raise ValueError('No distributable license text found')
    content=['第三方开源许可 / Third-party notices','本文件仅说明第三方组件的许可，不将整个应用源码声明为开源。','范围：Release运行时已解析的全部外部构件（含间接依赖）。Debug工具、构建工具及模拟器镜像不属于此运行时清单。','依赖组件：']
    for r in report:content.append(r['coordinate']+' — '+', '.join(l['name'] for l in r['licenses']))
    content.append('以下保留从实际构件读取的许可证及声明；相同文本合并展示，其来源列于前方。')
    for digest,t in texts.items():content+=['\n'+'='*60,'来源：\n'+'\n'.join(t['sources']),t['text']]
    output='\n\n'.join(content)+'\n';asset=a.root/'app/src/main/assets/THIRD_PARTY_NOTICES.txt';asset.parent.mkdir(parents=True,exist_ok=True);asset.write_bytes(output.encode('utf-8'));(a.root/'THIRD_PARTY_NOTICES.txt').write_bytes(output.encode('utf-8'))
    folder=a.root/'docs/commercial';folder.mkdir(parents=True,exist_ok=True)
    payload={'scope':'releaseRuntimeClasspath','reviewDate':'2026-10-24','totalArtifacts':len(rows),'externalArtifacts':len(report),'internalArtifacts':internal,'licenseFamilies':['Apache-2.0'],'noticeTextCount':len(texts),'noticeFileSha256':sha(output.encode()),'components':report}
    (folder/'runtime-licenses.json').write_text(json.dumps(payload,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'PASS: {len(report)} external artifacts; inherited POM licenses resolved; {len(texts)} unique embedded notices retained.')
if __name__=='__main__':main()
