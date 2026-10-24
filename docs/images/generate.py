"""Draw README illustrations with fictional content. Requires Pillow and a CJK font."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import os
ROOT=Path(__file__).resolve().parents[2]
OUT=Path(__file__).parent
FONT=os.environ.get('POCKET_DOC_FONT','/System/Library/Fonts/Supplemental/Arial Unicode.ttf')
BLUE='#1677ff'; INK='#14213d'; MUTED='#67758c'; BG='#f4f7fc'; BORDER='#dfe7f3'
def font(n):return ImageFont.truetype(FONT,n)
def text(d,xy,s,size=25,fill=INK):d.text(xy,s,font=font(size),fill=fill)
def rect(d,box,fill='white',radius=22,outline=None):d.rounded_rectangle(box,radius,fill,outline,width=2)
def line(d,a,b,fill=BORDER,width=2):d.line([a,b],fill,width)
def pill(d,x,y,s,fill='#eaf2ff',color=BLUE):
 w=d.textlength(s,font=font(20))+32;rect(d,(x,y,x+w,y+38),fill,19);text(d,(x+16,y+5),s,20,color)
def arrow(d,x1,y,x2,color=BLUE):
 line(d,(x1,y),(x2,y),color,4);d.polygon([(x2,y),(x2-12,y-8),(x2-12,y+8)],fill=color)
def logo(im,x,y,size):
 img=Image.open(ROOT/'design/brand/codex-pocket-logo.png').convert('RGBA').resize((size,size))
 im.paste(img,(x,y),img)
im=Image.new('RGB',(1600,940),BG);d=ImageDraw.Draw(im)
logo(im,68,54,72);text(d,(162,52),'Codex Pocket',48);text(d,(164,111),'手机随时继续，任务留在电脑',24,MUTED)
pill(d,1190,72,'ANDROID + DESKTOP')
# Desktop illustration
rect(d,(62,230,1090,792),'#dfe7f5',28);rect(d,(72,240,1080,780),'white',22)
rect(d,(72,240,1080,296),'#f8faff',22)
for x,c in [(98,'#ff6b6b'),(120,'#ffce57'),(142,'#50c991')]:d.ellipse((x,260,x+12,272),fill=c)
text(d,(185,253),'Codex Pocket Connector',20)
line(d,(326,297),(326,779));text(d,(100,328),'你的工作空间',25)
for y,label,sub in [(393,'Demo Workspace','示例项目'),(481,'Mobile App','项目设计与开发'),(569,'Research','资料整理')]:
 rect(d,(91,y-9,307,y+63),'#eff5ff' if y==393 else 'white',12)
 text(d,(106,y),label,20);text(d,(106,y+32),sub,15,MUTED)
text(d,(371,330),'连接你的手机',34);pill(d,852,338,'已连接','#e5f7ee','#18885d')
for y,n,title,sub in [(408,'1','选择连接方式','局域网直连 / 公网远程'),(493,'2','连接 Codex','已识别并连接电脑上的 Codex'),(578,'3','手机扫码配对','在电脑确认允许，即可继续工作')]:
 rect(d,(371,y,421,y+50),'#eaf2ff',16);text(d,(387,y+6),n,27,BLUE)
 text(d,(441,y-1),title,24);text(d,(441,y+36),sub,17,MUTED)
rect(d,(371,690,1030,743),BLUE,14);text(d,(588,700),'连接成功 · 等待手机',24,'white')
# Phone illustration
rect(d,(1152,205,1515,850),'#172437',44);rect(d,(1162,215,1505,840),'white',36)
text(d,(1191,233),'9:41',15);d.rounded_rectangle((1293,228,1372,247),9,'#172437')
text(d,(1185,279),'‹  Demo Workspace',24);line(d,(1174,326),(1491,326))
pill(d,1189,346,'DEMO-PC · 已连接','#e5f7ee','#18885d')
rect(d,(1252,411,1480,480),'#eaf2ff',18);text(d,(1270,431),'继续优化移动端界面',19)
logo(im,1185,517,32);text(d,(1232,513),'已完成布局调整。',20)
text(d,(1232,550),'接下来检查工具调用记录。',16,MUTED)
rect(d,(1185,607,1480,680),'#f4f7fc',15);text(d,(1203,621),'›  工具调用 · 已完成',18)
text(d,(1203,651),'文件修改 / 命令执行',15,MUTED)
rect(d,(1185,720,1480,778),'white',24,BORDER);text(d,(1202,736),'继续发送指令…',19,MUTED)
d.ellipse((1433,730,1472,769),fill=BLUE);text(d,(1443,734),'↑',23,'white')
text(d,(1191,798),'模型   ·   推理   ·   权限',16,MUTED)
text(d,(70,859),'项目与对话  /  可见工具记录  /  多电脑切换  /  文件与网页预览',26)
text(d,(70,903),'界面示意 · 使用虚构数据，不含真实聊天、设备身份或配对码',17,MUTED)
im.save(OUT/'overview.png',optimize=True)
# Four steps
im=Image.new('RGB',(1600,480),BG);d=ImageDraw.Draw(im)
text(d,(64,42),'第一次连接，只需四步',40);text(d,(67,105),'局域网无需账号审批；公网先在手机登录已批准账号。',23,MUTED)
steps=[('1','准备电脑','安装并登录官方 Codex','打开电脑连接器'),('2','连接 Codex','选择局域网或公网','第二步成功后继续'),('3','手机扫码','安卓打开对应连接方式','扫描电脑配对码'),('4','电脑确认','允许这台手机连接','项目与对话自动加载')]
for i,(n,title,a,b) in enumerate(steps):
 x=64+i*377;rect(d,(x,174,x+345,410),'white',22,BORDER)
 rect(d,(x+24,197,x+74,247),BLUE,16);text(d,(x+41,201),n,28,'white')
 text(d,(x+25,267),title,29);text(d,(x+25,322),a,21,MUTED);text(d,(x+25,356),b,21,MUTED)
text(d,(66,437),'保持电脑、Codex 和连接器运行。无需输入聊天 ID 或服务器 root 密码。',21,MUTED)
im.save(OUT/'quick-start.png',optimize=True)
# Architecture
im=Image.new('RGB',(1600,660),BG);d=ImageDraw.Draw(im)
text(d,(64,43),'两种连接方式，任务都在电脑上执行',37)
text(d,(64,117),'公网远程',27);text(d,(64,153),'跨网络 · 账号审批',20,MUTED)
for box,title,subtitle in [((64,212,419,361),'安卓手机','项目 · 对话 · 工具记录'),((621,212,976,361),'HTTPS / WSS 中继','账号与设备授权 · 转发任务密文'),((1178,212,1533,361),'你的电脑','Pocket 连接器 + Codex')]:
 rect(d,box,'white',22,BORDER);text(d,(box[0]+29,box[1]+31),title,28);text(d,(box[0]+29,box[1]+89),subtitle,19,MUTED)
arrow(d,440,288,600);arrow(d,997,288,1157)
text(d,(448,245),'加密传输',18,BLUE);text(d,(1005,245),'加密传输',18,BLUE)
line(d,(64,404),(1534,404))
text(d,(64,437),'局域网直连',27);text(d,(64,479),'同一网络 · 无需账号审批',20,MUTED)
rect(d,(621,442,976,578),'white',22,BORDER);text(d,(653,467),'安卓手机',28);text(d,(653,516),'扫码后由电脑确认',19,MUTED)
rect(d,(1178,442,1533,578),'white',22,BORDER);text(d,(1210,467),'你的电脑',28);text(d,(1210,516),'无需公网中继',19,MUTED)
arrow(d,997,514,1157)
text(d,(65,615),'中继不执行任务；命令、文件读写与 Codex 运行均发生在电脑端。',22,MUTED)
im.save(OUT/'architecture.png',optimize=True)
print('Generated overview.png, quick-start.png, architecture.png')
