"""Small observable Android UI/debug helper; outputs stay in the project .tools folder."""
import argparse, json, os, re, shutil, subprocess, sys, uuid, xml.etree.ElementTree as ET
from pathlib import Path
sys.stdout.reconfigure(encoding='utf-8')
SDK=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
ADB=Path(SDK)/'platform-tools'/('adb.exe' if os.name=='nt' else 'adb') if SDK else Path(shutil.which('adb') or 'adb')
SERIAL=os.environ.get('ANDROID_SERIAL','emulator-5554')
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'.tools/runtime-check'
OUT.mkdir(parents=True,exist_ok=True)

def adb(*args, binary=False, timeout=40):
    result=subprocess.run([str(ADB),'-s',SERIAL,*args],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if result.returncode: raise RuntimeError(result.stderr.decode('utf-8',errors='replace'))
    return result.stdout if binary else result.stdout.decode('utf-8',errors='replace')

def dump():
    target='/sdcard/decibelevidence-ui-'+uuid.uuid4().hex+'.xml'
    try:
        result=adb('shell','uiautomator','dump',target)
        if 'dumped to: '+target not in result:
            raise RuntimeError('UI dump failed; refusing cached state: '+result.strip())
        xml=adb('exec-out','cat',target)
    finally:
        adb('shell','rm','-f',target)
    (OUT/'current-ui.xml').write_text(xml,encoding='utf-8')
    root=ET.fromstring(xml)
    nodes=[]
    for node in root.iter('node'):
        a=node.attrib
        if a.get('text') or a.get('content-desc'):
            nodes.append({k:a.get(k,'') for k in ['text','content-desc','resource-id','bounds','clickable','enabled','package']})
    return nodes

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('action',choices=['dump','shot','tap','logs'])
    parser.add_argument('value',nargs='?',default='screen.png')
    a=parser.parse_args()
    if a.action=='dump':
        print('\n'.join((n['text'] or n['content-desc'])+' | '+n['bounds']+' | enabled='+n['enabled'] for n in dump()))
    elif a.action=='shot':
        p=OUT/Path(a.value).name;p.write_bytes(adb('exec-out','screencap','-p',binary=True));print(str(p))
    elif a.action=='tap':
        nodes=dump();matches=[n for n in nodes if (n['text']==a.value or n['content-desc']==a.value) and n['enabled']=='true']
        if len(matches)!=1:raise RuntimeError(f'Expected unique visible text/description {a.value!r}; found {len(matches)}')
        values=[int(v) for v in re.findall(r'\d+',matches[0]['bounds'])]
        if len(values)!=4:raise RuntimeError('Node has no usable bounds')
        x=(values[0]+values[2])//2;y=(values[1]+values[3])//2
        print(adb('shell','input','tap',str(x),str(y)))
        print('\n'.join((n['text'] or n['content-desc'])+' | '+n['bounds']+' | enabled='+n['enabled'] for n in dump()))
    elif a.action=='logs':
        text=adb('logcat','-d','-t','1000');(OUT/'logcat.txt').write_text(text,encoding='utf-8')
        print('\n'.join(line for line in text.splitlines() if any(s in line for s in ['FATAL EXCEPTION','AndroidRuntime','com.decibelevidence','EvidenceCamera','IllegalStateException'])))
