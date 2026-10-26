import importlib.util,io,json,tempfile,unittest,zipfile,hashlib
from unittest.mock import patch
from pathlib import Path
spec=importlib.util.spec_from_file_location('audit',Path(__file__).resolve().parents[1]/'audit_licenses.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
class LicenseAuditTests(unittest.TestCase):
 def test_parent_license_is_resolved(self):
  with tempfile.TemporaryDirectory() as d:
   base=Path(d)
   for g,n,v,text in [('g','child','1','<parent><groupId>g</groupId><artifactId>parent</artifactId><version>2</version></parent>'),('g','parent','2','<licenses><license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url></license></licenses>')]:
    p=base/g/n/v/'hash';p.mkdir(parents=True);(p/(n+'.pom')).write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'+text+'</project>')
   result,origin,digest=audit.licenses(base,'g','child','1');self.assertEqual('Apache-2.0',result[0]['name']);self.assertEqual('g:parent:2',origin);self.assertEqual(64,len(digest))
 def test_missing_license_does_not_silently_pass(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'g'/'x'/'1'/'hash';p.mkdir(parents=True);(p/'x.pom').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"/>')
   with self.assertRaisesRegex(ValueError,'No license'):audit.licenses(Path(d),'g','x','1')
 def test_embedded_notice_is_retained_but_copyright_class_is_not_text(self):
  jar=io.BytesIO()
  with zipfile.ZipFile(jar,'w') as z:z.writestr('META-INF/NOTICE.txt','Original attribution');z.writestr('icons/CopyrightKt.class',b'\xca\xfe\xba\xbe')
  aar=io.BytesIO()
  with zipfile.ZipFile(aar,'w') as z:z.writestr('classes.jar',jar.getvalue());z.writestr('META-INF/LICENSE.txt','License text')
  self.assertEqual([('META-INF/LICENSE.txt','License text'),('classes.jar!/META-INF/NOTICE.txt','Original attribution')],audit.embedded(aar.getvalue()))
 def test_unreadable_license_blocks_audit(self):
  jar=io.BytesIO()
  with zipfile.ZipFile(jar,'w') as z:z.writestr('META-INF/LICENSE',b'\xff\xff')
  with self.assertRaisesRegex(ValueError,'requires review'):audit.embedded(jar.getvalue())
 def test_report_digest_matches_distributed_notice_bytes(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);cache=root/'cache';p=cache/'g'/'lib'/'1'/'hash';p.mkdir(parents=True)
   (p/'lib.pom').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><licenses><license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url></license></licenses></project>')
   jar=root/'lib.jar'
   with zipfile.ZipFile(jar,'w') as z:z.writestr('META-INF/LICENSE','License line 1\n\nLicense line 2\n')
   artifact=root/'artifacts.json';artifact.write_text(json.dumps([dict(group='g',name='lib',version='1',file=str(jar),type='jar')]))
   with patch('sys.argv',['audit','--artifacts',str(artifact),'--cache',str(cache),'--root',str(root)]):audit.main()
   payload=json.loads((root/'docs/commercial/runtime-licenses.json').read_text())
   content=(root/'app/src/main/assets/THIRD_PARTY_NOTICES.txt').read_bytes()
   self.assertEqual(payload['noticeFileSha256'],hashlib.sha256(content).hexdigest())
   self.assertEqual(content,(root/'THIRD_PARTY_NOTICES.txt').read_bytes())
   self.assertNotIn(b'\r\n',content)
if __name__=='__main__':unittest.main()
