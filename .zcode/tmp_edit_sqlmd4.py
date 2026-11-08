p='sql.md'
s=open(p,encoding='utf-8').read()
s=s.replace("从 post + 窄表 + 审核记录清空重灌","从 post + 窄表清空重灌")
s=s.replace("商品在售改价立即生效不进审核，改价事务必须同事务旁路同步","商品在售改价立即生效，改价事务必须同事务旁路同步")
open(p,'w',encoding='utf-8',newline='').write(s)
print("done")
