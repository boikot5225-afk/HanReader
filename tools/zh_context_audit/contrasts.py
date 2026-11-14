#!/usr/bin/env python3
"""40 fixed synthetic contrast pairs (60 examples), separate from real-book estimates.
Some pairs share an anchor. They are diagnostic, not 80 independent observations.
"""
from pathlib import Path
import json,subprocess,argparse
from score import assess
root=Path(__file__).resolve().parents[2];w=root/'.work/zh-context-audit';d=Path(__file__).parent
# Original sentences, never attributed to either supplied book.
GROUPS=[
('妖精',[('妖精守着银行的金库。','гоблин',''),('山里的妖精吓坏了村民。','нечист|демон',''),('传说中的妖精住在洞穴里。','нечист|демон','')]),
('体面',[('那是一栋非常体面的宅邸。','прилич|респектаб',''),('他忘记了体面。','достоинств|престиж',''),('那是一座很体面的房子。','прилич|респектаб','')]),
('小精灵',[('洛哈特把小精灵带进教室。','пикси',''),('树林里的小精灵唱着歌。','эльф',''),('那个小精灵躲在树后。','эльф','')]),
('混血',[('这位混血巫师很年轻。','смешанной крови|полукров',''),('那是一只混血的狗。','гибрид|смешанной крови',''),('他是混血的孩子。','смешанной крови|полукров','')]),
('地',[('他慢慢地、轻轻地放下杯子。','частиц|нареч','de'),('脚下的地，很硬。','земл|почв','dì'),('土地干燥了。','земл|почв','dì')]),
('得',[('你得马上回家。','должен|нужн|надо','děi'),('他说得很清楚。','частиц|степен|комплемент','de'),('他得到了奖励。','получ|приобр','dé')]),
('只',[('只有他一个人。','только|лишь','zhǐ'),('那只猫睡着了。','сч[её]тн|животн|птиц','zhī'),('一只狗跑过来。','сч[её]тн|животн|птиц','zhī')]),
('还',[('他还没有回来。','ещ[её]|также|по-прежнему','hái'),('请把书还给他。','возвращ|верн|отда','huán'),('他把钱还给我了。','возвращ|верн|отда','huán')]),
('长',[('孩子慢慢长大了。','раст|выраст|взросл','zhǎng'),('那条路很长。','длин|долг','cháng'),('他比我年长。','старш|возраст','zhǎng')]),
('乐',[('音乐从窗外传来。','музык','yuè'),('孩子很快乐。','рад|вес[её]л|счаст','lè'),('他感到快乐。','рад|вес[её]л|счаст','lè')]),
('重',[('箱子很重。','тяж|вес','zhòng'),('请重新写一遍。','снова|повтор','chóng'),('我们重新开始。','снова|повтор','chóng')]),
('行',[('这样做不行。','можно|год|подход|действ|идти','xíng'),('他写了一行字。','ряд|строк','háng'),('这一行字很小。','ряд|строк','háng')]),
('藏',[('他把钱藏起来。','прят|скрыв','cáng'),('他去西藏旅行。','Тибет|тибет','zàng'),('他们去了西藏。','Тибет|тибет','zàng')]),
('单',[('他买了单人票。','один|одиноч|един','dān'),('他的老师姓单。','Шань|фамил','shàn'),('单于率领军队。','шаньюй|вожд','chán')]),
('恶',[('恶人逃跑了。','зл|плох','è'),('这气味令人恶心。','тошн|отврат','ě'),('他厌恶谎言。','ненав|отвращ|не люб','wù')]),
('朝',[('他朝门口走去。','направлен|к|в сторону','cháo'),('朝阳照亮了小院。','утр|рассвет|восход','zhāo'),('朝阳慢慢升起。','утр|рассвет|восход','zhāo')]),
('传',[('大家传来这个消息。','переда|распростран','chuán'),('他读了一本传记。','биограф|жизнеопис','zhuàn'),('那是一篇传记。','биограф|жизнеопис','zhuàn')]),
('当',[('他想当老师。','быть|станов|служ|работ','dāng'),('她把它当礼物。','счита|приним|рассматр','dàng'),('不要把它当玩具。','счита|приним|рассматр','dàng')]),
('数',[('他正在数钱。','счит|подсчит','shǔ'),('数字写错了。','числ|цифр|колич','shù'),('这事数见不鲜。','част|неоднократ','shuò')]),
('为',[('我认为他是对的。','быть|делать|счит','wéi'),('他为了孩子努力工作。','ради|для|за|чтобы','wèi'),('他为了工作搬家。','ради|для|за|чтобы','wèi')])]
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--prepare',action='store_true');a=p.parse_args()
 if a.prepare:
  assert not (d/'synthetic.jsonl').exists(),'Fixture already frozen; do not replace after results.'
  cases=[];pairs=[]
  for n,(word,triple) in enumerate(GROUPS):
   ids=[]
   for side,(text,ru,py) in enumerate(triple):
    st=text.index(word);uid=f'synthetic:{n}:{side}';ids.append(uid)
    cases.append(dict(id=uid,book='synthetic',volume=0,chapter=-1,path='',block=-1,title='',text=text,start=st,end=st+len(word),char_start=st,char_end=st+len(word),target=word,expected_ru=ru,acceptable_ru='',expected_reading=py,unit='character' if len(word)==1 else 'word',category='reading' if py else 'sense',must_whole=False,confidence='provisional',provenance='synthetic',split='contrast'))
   for other in [1,2]:pairs.append(dict(id=f'pair:{n}:{other}',left=ids[0],right=ids[other],unit=word))
  (d/'synthetic.jsonl').write_text(''.join(json.dumps(g,ensure_ascii=False)+'\n' for g in cases))
  (d/'pairs.json').write_text(json.dumps(pairs,ensure_ascii=False,indent=2)+'\n')
  print('Prepared 40 synthetic pairs, 60 original examples.');raise SystemExit
 cases={g['id']:g for g in map(json.loads,(d/'synthetic.jsonl').read_text().splitlines())};pairs=json.loads((d/'pairs.json').read_text());results={}
 for stage in ['baseline','current']:
  output=w/f'synthetic-{stage}.jsonl'
  subprocess.run(['python',str(d/'run.py'),'evaluate',str(d/'synthetic.jsonl'),str(output),'--label',stage,'--assets',str(w/'baseline-assets') if stage=='baseline' else str(root/'app/src/main/assets/zh')],check=True)
  predictions={r['id']:r for r in map(json.loads,output.read_text().splitlines())}
  rows=[]
  for pair in pairs:
   status=[assess(cases[pair[k]],predictions[pair[k]])['status'] for k in ['left','right']]
   rows.append(dict(**pair,statuses=status,both_usable=all(s in ['CORRECT','ACCEPTABLE'] for s in status)))
  results[stage]=dict(total=len(rows),both_usable=sum(r['both_usable'] for r in rows),rows=rows)
 (w/'contrast-metrics.json').write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')
 for stage,r in results.items():print(stage,r['both_usable'],'/',r['total'])
