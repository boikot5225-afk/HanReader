#!/usr/bin/env python3
"""Pair frozen real examples where available; synthetic rows are explicitly marked.
No book text is copied into public fixtures. Run BEFORE inspecting contrast results.
"""
import json,sys
from pathlib import Path
root=Path(__file__).resolve().parents[2];w=root/'.work/context-audit'
gold=list(map(json.loads,(w/'gold.jsonl').read_text().splitlines()))
pairs=[]
def real(target,left,right,n):
 a=[g for g in gold if g['book']=='narco' and g['lemma']==target and left in g['expected']]
 b=[g for g in gold if g['book']=='narco' and g['lemma']==target and right in g['expected']]
 for x,y in list(zip(a,b))[:n]:pairs.append(dict(id=f'real-{target}-{len(pairs)}',left=x['id'],right=y['id'],provenance='epub'))
real('plaza','площад','территор',6);real('banda','банд','музык',4);real('capital','столиц','капитал',2);real('piso','этаж','квартир',1);real('cuota','дол','побор',2)
fixtures=[
('plaza','En la plaza central hay una fuente.','El capo controla la plaza y el tráfico de drogas.','площад','территор'),
('banda','Una banda toca música en el parque.','La banda del cártel secuestró a dos hombres.','музык|оркестр','банд'),
('halcón','El halcón vuela sobre el bosque.','El halcón informa a los sicarios del cártel.','сокол','наблюдател|осведомител'),
('mordida','La mordida del perro dejó una herida.','El policía pidió una mordida al conductor.','укус','взятк'),
('célula','La célula del tejido se divide.','La célula de los Zetas recluta sicarios.','клетк','ячейк'),
('cuota','La cuota de inscripción se paga cada mes.','La tienda paga una cuota al cártel bajo amenaza.','взнос|плат','побор|дань'),
('piso','El piso de arriba tiene una terraza.','Alquilamos un piso con muebles.','этаж','квартир'),
('capital','La capital del país tiene dos aeropuertos.','El capital de la empresa aumenta.','столиц','капитал'),
('orden','El orden de las palabras importa.','El soldado recibió una orden.','порядок','приказ'),
('corrido','El músico canta un corrido mexicano.','Es un hombre corrido y experimentado.','кор+идо|баллад|песн','бывал'),
('bajas','La guerra causó muchas bajas.','Las casas bajas tienen tejados rojos.','потер|погиб','низк'),
('durmientes','Los durmientes de la vía necesitan reparación.','Los durmientes roncaban en la habitación.','шпал','спящ'),
('desarmado','El motor quedó desarmado pieza por pieza.','El viajero estaba desarmado y sin protección.','разобран','безоруж'),
('blanqueo','La policía investiga el blanqueo de dinero.','Usaron cloro para el blanqueo de la tela.','отмыван','отбел'),
('mañana','La mañana era soleada.','Vendré mañana.','утро','завтра'),
('tarde','Aquella tarde llovía.','Llegaron tarde.','вечер','поздно'),
('poder','El poder del presidente crece.','Quiero poder leer.','власть','мочь'),
('banco','El banco concedió un préstamo.','Me senté en un banco del parque.','банк','скам|лав'),
('planta','La planta necesita agua.','La planta de montaje produce automóviles.','растен','завод'),
('manzana','Comió una manzana roja.','Una manzana de viviendas quedó vacía.','яблок','квартал'),
('derecho','Tiene derecho a votar.','Estudia derecho en la universidad.','право','право|юриспруд'),
('partido','El partido ganó las elecciones.','El partido de fútbol terminó.','парти','матч'),
('cola','El perro mueve la cola.','La cola para comprar entradas era larga.','хвост','очеред'),
('operación','La policía inició una operación contra el tráfico.','El cirujano realizó una operación de corazón.','операци','операци|хирург'),
('ejecutado','El prisionero fue ejecutado al amanecer.','El proyecto fue ejecutado según el plan.','казн|расстрел|уби','выполн|осуществ|исполн')
]
synthetic=[]
for n,(word,a,b,ra,rb) in enumerate(fixtures):
 ids=[]
 for side,text,expected in [('a',a,ra),('b',b,rb)]:
  start=text.lower().index(word.lower());uid=f'synthetic-{n}-{side}';ids.append(uid)
  synthetic.append(dict(id=uid,book='synthetic',chapter=-1,path='',chapter_title='',block=-1,start=start,end=start+len(word),text=text,target=word,lemma={'bajas':'baja|bajo','durmientes':'durmiente','ejecutado':'ejecutar|ejecutado'}.get(word,word),expected=expected,acceptable='',reject='',category='A',confidence='high',reason='Artificial minimal contrast; not a quotation.',provenance='synthetic',split='contrast',pos='context-dependent'))
 pairs.append(dict(id=f'synthetic-pair-{n}',left=ids[0],right=ids[1],provenance='synthetic',same_russian_gloss_allowed=word in ['derecho','operación']))
assert len(pairs)==40,len(pairs)
(root/'tools/context_audit/contrast_pairs.json').write_text(json.dumps(pairs,ensure_ascii=False,indent=2)+'\n')
(root/'tools/context_audit/synthetic.jsonl').write_text(''.join(json.dumps(g,ensure_ascii=False)+'\n' for g in synthetic))
(w/'contrast-gold.jsonl').write_text(''.join(json.dumps(g,ensure_ascii=False)+'\n' for g in gold+synthetic))
print(len(pairs),'pairs;',len(synthetic),'synthetic examples; actual book text stays private.')
